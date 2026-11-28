package com.sdmu.guanlitong.data.zhs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条作业提醒（旧项目「我的作业」卡片）。 */
data class ZhsHomework(
    val title: String,
    val courseName: String,
    /** 截止时间（epoch 毫秒）；服务端没给则为 null。 */
    val deadline: Long?,
)

/**
 * 作业提醒列表，对照参考实现的 `get_homework`：
 * `findImportantReminderList?uuid=…&date=…`，列表在响应 `rt` 数组里。
 */
internal object ZhsHomeworkApi {

    private const val URL = "https://hike-examstu.zhihuishu.com/zhsathome/homework/findImportantReminderList"
    private const val ORIGIN = "https://onlineweb.zhihuishu.com"

    suspend fun fetch(session: ZhsSession): List<ZhsHomework> = withContext(Dispatchers.IO) {
        val http = ZhsHttp(LinkedHashMap(session.cookies))
        val uuid = ZhsBrushCodec.casUuid(session.cookies["CASLOGC"])
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val response = ZhsLearnApi.network(
            {
                http.get(
                    "$URL?uuid=$uuid&date=$date",
                    buildMap {
                        put("Accept", "application/json")
                        put("Origin", ORIGIN)
                        put("Referer", "$ORIGIN/")
                        session.cookies["jt-cas"]?.takeIf { it.isNotBlank() }?.let { put("Authorization", it) }
                    },
                )
            },
            "作业列表加载失败",
        )
        ZhsLearnApi.checkAuth(response)
        ZhsLearnApi.rejectHtml(response.body)
        parseList(response.body)
    }

    /** 响应 → 作业列表；`rt` 缺失按空列表处理，字段缺失有兜底文案。 */
    internal fun parseList(body: String): List<ZhsHomework> {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw ZhsException("知到返回数据格式异常，请稍后重试", ZhsErrorKind.PARSE)
        }
        val list = json.optJSONArray("rt") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                add(
                    ZhsHomework(
                        title = item.optString("title").ifBlank { "未命名作业" },
                        courseName = item.optString("courseName").ifBlank { "课程待更新" },
                        deadline = parseDeadline(item.opt("endTime")),
                    ),
                )
            }
        }.sortedBy { it.deadline ?: Long.MAX_VALUE }
    }

    private fun parseDeadline(value: Any?): Long? = when (value) {
        null -> null
        is Number -> value.toLong().let { if (it in 1..99_999_999_999L) it * 1000 else it }
        is String -> if (value.isBlank()) {
            null
        } else {
            runCatching { Instant.parse(value).toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
                .getOrNull()
        }
        else -> null
    }
}