package com.sdmu.guanlitong.data.zhs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 课程问答（列表 / 回答 / 提交），对照参考实现 `get_qa_list` / `get_qa_answers` /
 * `save_qa_answer`。
 *
 * 问答走独立的 creditqa 系统：`c/hasV2` 用 RSA module=10 换 AES 密钥，
 * 报文 `dateFormate=<ms>&secretStr=<aes>`，请求头带 `Authorization: jt-cas`。
 * 密钥缓存在 [ZhsSession.qaKey]；缓存密钥失效时自动重换一次。
 */
object ZhsQaApi {

    internal const val QA_ORIGIN = "https://qah5.zhihuishu.com"
    internal const val HAS_V2_URL =
        "https://appcomm-user.zhihuishu.com/app-commserv-user/c/hasV2"
    private const val QA_BASE = "https://creditqa-api.zhihuishu.com/creditqa/gateway/t/v1/web/qa"
    internal const val HOT_LIST_URL = "$QA_BASE/getHotQuestionList"
    internal const val RECOMMEND_LIST_URL = "$QA_BASE/getRecommendList"
    internal const val ANSWER_LIST_URL = "$QA_BASE/getAnswerInInfoOrderByTime"
    internal const val SAVE_ANSWER_URL = "$QA_BASE/saveAnswer"

    suspend fun questions(
        session: ZhsSession,
        course: ZhsCourse,
        newest: Boolean,
        pageIndex: Int = 0,
        pageSize: Int = 50,
    ): ZhsQaPage = withContext(Dispatchers.IO) {
        val payload = questionPayload(course.courseId, course.recruitId, pageIndex, pageSize)
        val endpoint = if (newest) RECOMMEND_LIST_URL else HOT_LIST_URL
        val (body, used) = requestWithRetry(session, payload, endpoint)
        ZhsQaPage(ZhsQaParser.questions(body), used)
    }

    suspend fun answers(
        session: ZhsSession,
        course: ZhsCourse,
        questionId: String,
        pageIndex: Int = 0,
        pageSize: Int = 20,
    ): ZhsAnswerPage = withContext(Dispatchers.IO) {
        val payload = answersPayload(questionId, course.courseId, course.recruitId, pageIndex, pageSize)
        val (body, used) = requestWithRetry(session, payload, ANSWER_LIST_URL)
        val page = ZhsQaParser.answers(body)
        page.copy(session = used)
    }

    /** 提交回答（调用方负责内容与确认；错误以 [ZhsException] 抛出）。 */
    suspend fun submitAnswer(
        session: ZhsSession,
        course: ZhsCourse,
        questionId: String,
        content: String,
    ): ZhsSession = withContext(Dispatchers.IO) {
        val used = ensureKey(session)
        val payload = savePayload(questionId, course.courseId, course.recruitId, content)
        val body = post(used, payload, SAVE_ANSWER_URL)
        checkSaveResult(body)
        used
    }

    /** 密钥缺失时先换；请求失败且是缓存密钥导致时，清掉重换一次。 */
    private fun requestWithRetry(
        session: ZhsSession,
        payload: String,
        endpoint: String,
    ): Pair<String, ZhsSession> {
        val hadKey = session.qaKey.isNotBlank()
        val ready = ensureKey(session)
        return try {
            post(ready, payload, endpoint) to ready
        } catch (e: ZhsException) {
            if (!hadKey || e.kind == ZhsErrorKind.EXPIRED || e.kind == ZhsErrorKind.NETWORK) throw e
            val refreshed = ensureKey(ready.copy(qaKey = ""))
            post(refreshed, payload, endpoint) to refreshed
        }
    }

    /** 保证会话里有可用的问答密钥；缺了就换，换不到就抛错。 */
    internal fun ensureKey(session: ZhsSession): ZhsSession {
        if (!session.hasTicket) throw ZhsLearnApi.expired()
        if (session.qaKey.isNotBlank()) return session
        val key = exchangeKey(session)
        return session.copy(qaKey = key)
    }

    /** `c/hasV2` module=10 换问答系统 AES 密钥。 */
    internal fun exchangeKey(session: ZhsSession): String {
        val http = ZhsHttp(LinkedHashMap(session.cookies))
        val encrypted = ZhsCrypto.rsaEncryptForExchange(module = 10)
        val url = "$HAS_V2_URL?uid=${ZhsHttp.urlEncode(encrypted)}"
        val response = ZhsLearnApi.network(
            { http.get(url, keyHeaders()) },
            "问答密钥获取失败，请稍后重试",
        )
        ZhsLearnApi.checkAuth(response)
        val sl = runCatching {
            JSONObject(response.body).optJSONObject("rt")?.optString("sl")
        }.getOrNull().orEmpty()
        if (sl.isBlank()) throw ZhsException("问答密钥交换失败，请稍后重试", ZhsErrorKind.SERVER)
        return ZhsCrypto.rsaDecryptKey(sl)?.takeIf { it.isNotBlank() }
            ?: throw ZhsException("问答密钥解析失败，请稍后重试", ZhsErrorKind.SERVER)
    }

    internal fun questionPayload(
        courseId: Long,
        recruitId: Long,
        pageIndex: Int,
        pageSize: Int,
    ): String = buildString {
        append("{\"courseId\":\"").append(courseId).append("\",")
        append("\"pageIndex\":").append(pageIndex).append(',')
        append("\"pageSize\":").append(pageSize).append(',')
        append("\"recruitId\":\"").append(recruitId).append("\"}")
    }

    internal fun answersPayload(
        questionId: String,
        courseId: Long,
        recruitId: Long,
        pageIndex: Int,
        pageSize: Int,
    ): String = buildString {
        append("{\"questionId\":").append(ZhsCrypto.jsonString(questionId)).append(',')
        append("\"sourceType\":\"2\",")
        append("\"courseId\":\"").append(courseId).append("\",")
        append("\"recruitId\":\"").append(recruitId).append("\",")
        append("\"pageIndex\":").append(pageIndex).append(',')
        append("\"pageSize\":").append(pageSize).append('}')
    }

    internal fun savePayload(
        questionId: String,
        courseId: Long,
        recruitId: Long,
        content: String,
    ): String = buildString {
        append("{\"annexs\":\"[]\",")
        append("\"qid\":").append(ZhsCrypto.jsonString(questionId)).append(',')
        append("\"source\":\"2\",")
        append("\"aContent\":").append(ZhsCrypto.jsonString(content)).append(',')
        append("\"courseId\":\"").append(courseId).append("\",")
        append("\"recruitId\":\"").append(recruitId).append("\",")
        append("\"saveSource\":1}")
    }

    private fun post(session: ZhsSession, payload: String, endpoint: String): String {
        val http = ZhsHttp(LinkedHashMap(session.cookies))
        val date = System.currentTimeMillis()
        val form = linkedMapOf(
            "dateFormate" to date.toString(),
            "secretStr" to ZhsCrypto.aesEncrypt(payload, session.qaKey),
        )
        val response = ZhsLearnApi.network(
            { http.postForm(endpoint, form, headers(session)) },
            "问答加载失败，请稍后重试",
        )
        ZhsLearnApi.checkAuth(response)
        ZhsLearnApi.rejectHtml(response.body)
        return response.body
    }

    private fun headers(session: ZhsSession): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("Origin", QA_ORIGIN)
        put("Referer", "$QA_ORIGIN/")
        session.cookies["jt-cas"]?.takeIf { it.isNotBlank() }?.let { put("Authorization", it) }
    }

    private fun keyHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Origin" to QA_ORIGIN,
        "Referer" to "$QA_ORIGIN/",
        "X-Requested-With" to "XMLHttpRequest",
    )

    /** 上游没有统一成功字段；只在响应里明确写了失败时抛错。 */
    private fun checkSaveResult(body: String) {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ZhsException("回答提交失败，请稍后重试", ZhsErrorKind.PARSE)
        val message = json.optString("msg").ifBlank { json.optString("message") }
        if (message.isNotEmpty() &&
            (message.contains("失败") || message.contains("错误") || message.contains("频繁"))
        ) {
            throw ZhsException(message, ZhsErrorKind.SERVER)
        }
    }
}