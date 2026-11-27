package com.sdmu.guanlitong.data.cx

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 作业接口，对照老项目 `cx_api.py: fetch_work_detail / submit_work`。
 *
 * - [fetchDetail] 从收件箱作业消息的 `actionUrl`（intoexamorwork）出发，手动跟随
 *   302 到 doWork 页；状态归一复用 [CxApi.ensurePageOk]（302/401/登录页 → 401，
 *   202/验证码 → 403，其他非 200 → 502），401 由 [CxRepository.withSession] 单飞重登。
 * - [submit] 把提交页的 hidden 参数与 answer* 一起 POST 到 form action；Referer 与老
 *   项目一致。成功判定同样对齐老项目：优先读 JSON 的 status/success，其次看 msg
 *   是否含「成功」；非 JSON 时才退回「HTTP 200 且正文含成功」。
 *
 * [CxApi.getFollowing] / [CxApi.postForm] 自己不做调度切换，所以两个入口整体包
 * [Dispatchers.IO]：UI 线程直接调进来时，否则会撞 `NetworkOnMainThreadException`
 * （message 为 null，界面只会显示通用的「学习通请求失败」）。
 */
class CxWorkApi(
    private val api: CxApi,
    private val referer: String = DEFAULT_REFERER,
) {

    suspend fun fetchDetail(actionUrl: String): CxWorkDetail = withContext(Dispatchers.IO) {
        var url = actionUrl.trim()
        val (status, body) = api.getFollowing(url, referer)
        api.ensurePageOk(status, body, "获取作业详情")

        var detail = CxWorkParser.parse(body, url)
        if (detail.questions.isNotEmpty()) return@withContext detail

        // 部分入口是 200 的 meta refresh / JS 跳转，自动跟随不会发生；再跟一次。
        val next = CxWorkParser.findClientRedirect(body, url)
        if (next != null && next != url) {
            Log.d(TAG, "页面无题目，跟随客户端跳转：$url -> $next")
            url = next
            val (nextStatus, nextBody) = api.getFollowing(next, referer)
            api.ensurePageOk(nextStatus, nextBody, "获取作业详情")
            detail = CxWorkParser.parse(nextBody, next)
        }

        if (detail.questions.isEmpty()) {
            // 真实页面与本解析器不一致时，留足线上诊断信息（不含 Cookie）。
            Log.d(
                TAG,
                "未解析到题目 url=$url status=$status len=${body.length} " +
                    "submitUrl=${detail.submitUrl} snippet=${body.take(500).replace('\n', ' ')}",
            )
        }
        detail
    }

    /**
     * @param answers qid → 各空答案。选择题/判断题/简答题只放一个元素；
     *   填空多空按顺序放多个元素（空着的中间空也要保留位置）。
     *
     * 提交字段规则对齐老项目：多空填空写 `answerEditor{qid}1..N` 且 `answer{qid}`
     * 取第一空；其余题只写 `answer{qid}`。`answerwqbid` 为全部题目 id 逗号分隔。
     */
    suspend fun submit(
        detail: CxWorkDetail,
        answers: Map<String, List<String>>,
    ): CxWorkResult = withContext(Dispatchers.IO) {
        if (detail.submitUrl.isBlank()) {
            throw CxException("没有找到作业提交地址，页面结构可能已变化", 502)
        }

        val form = LinkedHashMap(detail.submitParams)
        for (question in detail.questions) {
            val values = answers[question.id].orEmpty().map { it.trim() }
            if (values.none { it.isNotBlank() }) continue
            if (values.size > 1) {
                values.forEachIndexed { index, value ->
                    form["answerEditor${question.id}${index + 1}"] = value
                }
                form["answer${question.id}"] = values.firstOrNull().orEmpty()
            } else {
                form["answer${question.id}"] = values.first()
            }
        }

        val questionIds = if (detail.questions.isNotEmpty()) {
            detail.questions.map { it.id }
        } else {
            form.keys.filter { CxWorkParser.isAnswerField(it) }
                .map { it.removePrefix("answer") }
        }
        form["answerwqbid"] = questionIds.joinToString(",")

        val (status, body) = api.postForm(detail.submitUrl, form, SUBMIT_REFERER)

        when {
            status == 202 || (status == 200 && ("验证码" in body || "processVerify" in body)) ->
                throw CxException("学习通要求验证，请稍后再试", CX_CODE_RISK)

            status == HTTP_UNAUTHORIZED ||
                status == HTTP_MOVED_PERM ||
                status == HTTP_MOVED_TEMP ->
                throw CxException(AUTH_LOST, CX_CODE_AUTH)
        }

        val outcome = parseSubmitResult(status, body)
        Log.d(TAG, "提交 status=$status len=${body.length} success=${outcome.success}")
        outcome
    }

    private fun parseSubmitResult(status: Int, body: String): CxWorkResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONObject(body.substringAfter('(', "").substringBeforeLast(')')) }.getOrNull()

        if (json != null) {
            val flag = json.opt("status") ?: json.opt("success")
            val message = json.optString("msg").ifBlank { json.optString("message") }
            val success = truthy(flag) || message.contains("成功")
            return CxWorkResult(
                success = success,
                message = message.ifBlank { if (success) "提交成功" else "提交失败" },
            )
        }

        // 老项目兜底：非 JSON 时只看 HTTP 200 + 正文特征。
        val success = status == HTTP_OK &&
            (body.contains("成功") || body.contains("success", ignoreCase = true))
        return CxWorkResult(
            success = success,
            message = body.take(200).ifBlank {
                if (success) "提交成功" else "提交失败（HTTP $status）"
            },
        )
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> {
            val text = value.trim()
            text == "1" || text.equals("true", ignoreCase = true) ||
                text.toIntOrNull()?.let { it != 0 } == true
        }

        else -> false
    }

    companion object {
        const val DEFAULT_REFERER =
            "https://notice.chaoxing.com/res/pc/mobileHtml/html/inbox.html"
        const val SUBMIT_REFERER =
            "https://mooc1-api.chaoxing.com/mooc-ans/mooc2/work/dowork"
        private const val TAG = "CxWorkApi"
        private const val AUTH_LOST = "登录已失效，请重新登录"
        private const val HTTP_OK = 200
        private const val HTTP_MOVED_PERM = 301
        private const val HTTP_MOVED_TEMP = 302
        private const val HTTP_UNAUTHORIZED = 401
    }
}