package com.sdmu.guanlitong.data.zhs

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 问答响应解析（纯函数）。
 *
 * 上游列表有两种外层结构（`questionInfoList` / `pageList`），还可能裹在
 * `rt/result/data/body` 里，这里按参考实现 `_qa_questions` 逐层找非空数组。
 * 问题与回答正文去 HTML 标签、反转义、压空白（`_question_text`）。
 */
internal object ZhsQaParser {

    private val TAG = Regex("</?[a-zA-Z][^>]*>")
    private val WHITESPACE = Regex("\\s+")
    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val SUCCESS_MESSAGES = setOf("ok", "success", "成功", "操作成功", "请求成功")

    fun questions(body: String): List<ZhsQuestion> {
        val json = parse(body, "问答列表格式异常，请稍后重试")
        val rows = findList(json, LIST_KEYS)
        rows ?: run {
            ZhsCourseParser.extractMessage(json)
                ?.takeIf { it.lowercase() !in SUCCESS_MESSAGES }
                ?.let { throw ZhsException(it, ZhsErrorKind.PARSE) }
            return emptyList()
        }
        return rows.mapNotNull(::questionOf)
    }

    fun answers(body: String): ZhsAnswerPage {
        val json = parse(body, "回答列表格式异常，请稍后重试")
        val rt = json.optJSONObject("rt")
        val rows = findList(json, ANSWER_LIST_KEYS).orEmpty()
        val content = cleanText(
            rt?.optString("questionContent").orEmpty().ifBlank {
                json.optJSONObject("body")?.optString("questionContent").orEmpty()
            },
        ).orEmpty()
        return ZhsAnswerPage(
            questionContent = content,
            answers = rows.mapNotNull(::answerOf),
        )
    }

    internal fun questionOf(raw: JSONObject): ZhsQuestion? {
        val id = text(raw, "questionId") ?: text(raw, "qid") ?: text(raw, "id") ?: return null
        val content = cleanText(
            text(raw, "content") ?: text(raw, "questionContent") ?: text(raw, "title"),
        ) ?: return null
        return ZhsQuestion(
            id = id,
            content = content,
            answerCount = int(raw, "answerNum") ?: int(raw, "answerAmount")
                ?: int(raw, "answerCount") ?: 0,
            time = formatTime(text(raw, "createTime") ?: text(raw, "questionTime") ?: text(raw, "time")),
            asker = raw.optJSONObject("userDto")?.let { text(it, "username") }
                ?: text(raw, "userName") ?: text(raw, "askerName") ?: text(raw, "ownerName") ?: "",
        )
    }

    internal fun answerOf(raw: JSONObject): ZhsAnswer? {
        val content = cleanText(
            text(raw, "answerContent") ?: text(raw, "content") ?: text(raw, "answer"),
        ) ?: return null
        return ZhsAnswer(
            id = text(raw, "answerId") ?: text(raw, "id") ?: "",
            content = content,
            time = formatTime(
                text(raw, "createTimeString") ?: text(raw, "createTime")
                    ?: text(raw, "answerTime") ?: text(raw, "time"),
            ),
            // 实际响应里回答人是 `userMap.userName`（大写 N），旧参考实现写的是 `username`，两种都认。
            author = raw.optJSONObject("userMap")?.let { text(it, "userName") ?: text(it, "username") }
                ?: text(raw, "answerName") ?: text(raw, "userName") ?: text(raw, "ownerName") ?: "",
            teacher = (int(raw, "answerFromTeacher") ?: 0) == 1 || text(raw, "role") == "teacher" ||
                raw.optJSONObject("userMap")?.optBoolean("isTeacher") == true,
        )
    }

    /**
     * 去 HTML 标签 → 反转义 → 压空白。参考实现的顺序是
     * `re.sub(r'\s+', ' ', html.unescape(re.sub(r'</?[a-zA-Z][^>]*>', '', value))).strip()`。
     */
    fun cleanText(value: String?): String? {
        if (value == null) return null
        val stripped = TAG.replace(value, "")
        val unescaped = ENTITY.replace(stripped) { match ->
            decodeEntity(match.groupValues[1])
        }
        return WHITESPACE.replace(unescaped, " ").trim().takeIf { it.isNotEmpty() }
    }

    private fun decodeEntity(body: String): String = when {
        body.startsWith("#x", ignoreCase = true) ->
            body.substring(2).toIntOrNull(16)?.let(::codePoint) ?: "&$body;"

        body.startsWith("#") ->
            body.substring(1).toIntOrNull()?.let(::codePoint) ?: "&$body;"

        else -> when (body.lowercase()) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> "&$body;"
        }
    }

    /** 码点 → 字符串；兼容 emoji 这类超出 BMP 的实体。 */
    private fun codePoint(value: Int): String? {
        if (value <= 0 || value > Character.MAX_CODE_POINT) return null
        return runCatching { String(Character.toChars(value)) }.getOrNull()
    }

    /** 数字毫秒 → `yyyy-MM-dd HH:mm`；其他格式原样保留。 */
    internal fun formatTime(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw == "null") return ""
        val millis = raw.toLongOrNull() ?: return raw
        val normalized = if (millis < 10_000_000_000L) millis * 1000 else millis
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(normalized))
        }.getOrDefault(raw)
    }

    /**
     * 按 key 顺序找非空数组；本层没有就递归进 `rt/result/data/body`。
     * 全空时返回空列表（页面显示空态），不是 null。
     */
    internal fun findList(json: JSONObject, keys: List<String>, depth: Int = 0): List<JSONObject>? {
        if (depth > 4) return null
        var empty: List<JSONObject>? = null
        for (key in keys) {
            val rows = json.optJSONArray(key) ?: continue
            val parsed = objects(rows)
            if (parsed.isNotEmpty()) return parsed
            empty = parsed
        }
        for (key in NESTED_KEYS) {
            val nested = json.optJSONObject(key) ?: continue
            val parsed = findList(nested, keys, depth + 1)
            if (parsed != null) {
                if (parsed.isNotEmpty()) return parsed
                empty = parsed
            }
        }
        return empty
    }

    private fun objects(rows: JSONArray): List<JSONObject> =
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }

    private fun parse(body: String, fallback: String): JSONObject =
        runCatching { JSONObject(body) }.getOrNull()
            ?: throw ZhsException(fallback, ZhsErrorKind.PARSE)

    private fun text(raw: JSONObject, key: String): String? =
        raw.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

    private fun int(raw: JSONObject, key: String): Int? =
        (raw.opt(key) as? Number)?.toInt()
            ?: (raw.optString(key).trim().toIntOrNull())

    internal val LIST_KEYS = listOf("questionInfoList", "pageList")
    internal val ANSWER_LIST_KEYS = listOf(
        "answerInfos",
        "answerInfoList",
        "answerList",
        "pageList",
        "list",
        "rows",
    )
    private val NESTED_KEYS = listOf("rt", "result", "data", "body")
}