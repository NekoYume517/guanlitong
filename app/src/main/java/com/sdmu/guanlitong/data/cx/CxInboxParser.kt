package com.sdmu.guanlitong.data.cx

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 收件箱 JSON 解析（纯函数，JVM 单测直接跑）。
 *
 * 对照老项目 `cx_api.py:1242 get_inbox_list`：
 * - `notices.list` 与顶层 `topNotices` 合并（置顶往往是重要通知），按 `idCode` 去重；
 * - `sourceType` → 类型/emoji；`isread` 镜像为 `remoteRead`（不回写超星）；
 * - `attachment` 可能是 JSON 字符串或数组，取第一条 `att_web` 的 url/logo/content；
 * - `insertTime` 可能是毫秒时间戳或日期字符串。
 */
internal object CxInboxParser {

    fun parse(body: String): CxInboxPage {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw CxException("收件箱返回格式异常", 502)
        }
        val notices = root.optJSONObject("notices") ?: JSONObject()
        val rawList = notices.optJSONArray("list")
        val topList = root.optJSONArray("topNotices")
        val statusOk = statusOk(root.opt("status"))

        // 老项目 `if not j.get('status'): return []`：从没有消息时可能返回 status=false，
        // 不能当失败（每 30 分钟一次 502 + 错误日志）；但 status=false 还带列表，说明接口
        // 行为异常，仍按 502 处理。空页把正文前 200 字带出去，真机排查用。
        if (!statusOk) {
            val empty = (rawList == null || rawList.length() == 0) &&
                (topList == null || topList.length() == 0)
            if (!empty) throw CxException("收件箱返回 status=false 但带有列表", 502)
            return CxInboxPage(
                items = emptyList(),
                lastGetId = notices.optString("lastGetId", ""),
                lastPage = notices.optInt("lastPage", 0) == 1,
                statusOk = false,
                rawSnippet = body.take(200),
            )
        }

        val seen = LinkedHashSet<String>()
        val items = ArrayList<CxInboxItem>()

        // 先置顶、后普通列表：同一 idCode 只留第一次出现的那条。
        for (array in listOf(topList, rawList)) {
            if (array == null) continue
            for (i in 0 until array.length()) {
                val item = parseItem(array.optJSONObject(i) ?: continue)
                if (item.idCode.isBlank()) continue
                if (seen.add(item.idCode)) items += item
            }
        }

        return CxInboxPage(
            items = items,
            lastGetId = notices.optString("lastGetId", ""),
            lastPage = notices.optInt("lastPage", 0) == 1,
        )
    }

    private fun parseItem(raw: JSONObject): CxInboxItem {
        val sourceType = stringOf(raw, "sourceType").ifBlank { "0" }
        val title = stringOf(raw, "title")
        var content = stringOf(raw, "content")
        var actionUrl = ""
        var logoUrl = stringOf(raw, "logo")

        val attachment = attachmentList(raw.opt("attachment"))
        val web = attachment?.optJSONObject(0)?.opt("att_web")
        when (web) {
            is JSONObject -> {
                actionUrl = stringOf(web, "url")
                if (logoUrl.isBlank()) logoUrl = stringOf(web, "logo")
                if (content.isBlank()) content = stringOf(web, "content")
            }
            is String -> {
                // 少数页面把 att_web 再序列化成字符串。
                val parsed = runCatching { JSONObject(web) }.getOrNull()
                if (parsed != null) {
                    actionUrl = stringOf(parsed, "url")
                    if (logoUrl.isBlank()) logoUrl = stringOf(parsed, "logo")
                    if (content.isBlank()) content = stringOf(parsed, "content")
                }
            }
        }

        return CxInboxItem(
            idCode = stringOf(raw, "idCode"),
            type = CxInboxType.fromSourceType(sourceType),
            title = title,
            content = content,
            creator = stringOf(raw, "createrName"),
            remoteRead = boolOf(raw, "isread", fallback = true),
            insertTimeMs = parseInsertTime(raw.opt("insertTime")),
            actionUrl = actionUrl,
            logoUrl = logoUrl,
        )
    }

    /** `attachment` 可能是 JSON 字符串，也可能已经是数组；解析失败就当没有。 */
    private fun attachmentList(raw: Any?): JSONArray? = when (raw) {
        is JSONArray -> raw
        is String -> if (raw.isBlank()) null else runCatching { JSONArray(raw) }.getOrNull()
        else -> null
    }

    private fun parseInsertTime(raw: Any?): Long? {
        val ms = when (raw) {
            null, JSONObject.NULL -> return null
            is Number -> {
                val v = raw.toLong()
                // 10 位是秒级时间戳（2010 年前后），统一成毫秒。
                if (v in 1_000_000_000L..9_999_999_999L) v * 1_000 else v
            }
            else -> return CxDateTime.parseMs(raw.toString())
        }
        return ms.takeIf { it > 0 }
    }

    private fun statusOk(raw: Any?): Boolean = when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() == 1
        is String -> raw == "1" || raw.equals("true", ignoreCase = true)
        else -> false
    }

    private fun stringOf(obj: JSONObject, key: String): String =
        obj.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim().orEmpty()

    /** `isread` 可能是 `1/0`、`"1"/"0"`，也可能是 JSON 布尔；缺省按老项目当已读。 */
    private fun boolOf(obj: JSONObject, key: String, fallback: Boolean): Boolean =
        when (val v = obj.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() == 1
            is String -> v.trim() == "1" || v.trim().equals("true", ignoreCase = true)
            else -> fallback
        }
}