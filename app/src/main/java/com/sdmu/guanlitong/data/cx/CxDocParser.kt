package com.sdmu.guanlitong.data.cx

import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * 资料目录 / 元信息 / 心跳签名的纯函数，对照老项目 main.py：
 * `Browser._parse`（242）、`meta`（323）、`compute_enc`（314）、`hb`（334）。
 *
 * 心跳的 JSON 必须手写：超星的 `enc` 是 `MD5(percentEncode(json) + "readPoint" +
 * ts + uid + SALT)`，字段顺序、数字/字符串形态与缩进都会改变签名，不能用
 * JSONObject 重复序列化去"碰"。
 */
internal object CxDocParser {

    private const val ENC_SALT = "NrRzLDpWB2JkeodIVAn4"
    private val HEX = "0123456789ABCDEF".toCharArray()

    /** stucoursemiddle / 目录页里的 hidden input：`id` 与 `value` 顺序不固定。 */
    fun parseParams(html: String): CxDocParams = CxDocParams(
        enc = hiddenValue(html, "enc"),
        cpi = hiddenValue(html, "cpi"),
        openc = hiddenValue(html, "openc"),
    )

    /**
     * 解析 `stu-datalist` 返回的目录 HTML。
     *
     * 每个 `<ul class="dataBody_td" ...>` 是一条：`dataName`（URL 编码）、`type`、
     * `id`、`objectId`；`dataBody_size_stu` 与 `dataBody_creater_stu` 按出现顺序对齐。
     * 页面里若带 hidden `enc`，随 [CxDocPage.enc] 返回给下一层请求。
     */
    fun parseList(html: String): CxDocPage {
        val items = mutableListOf<CxDocItem>()
        for (match in UL_RE.findAll(html)) {
            val tag = match.value
            val rawName = attr(tag, "dataName").orEmpty()
            items += CxDocItem(
                name = decode(rawName).ifBlank { "?" },
                type = attr(tag, "type").orEmpty().ifBlank { "?" },
                id = attr(tag, "id").orEmpty().ifBlank { "0" },
                objectId = attr(tag, "objectId").orEmpty(),
            )
        }
        val sizes = SIZE_RE.findAll(html).map { it.groupValues[1].trim() }.toList()
        val creators = CREATOR_RE.findAll(html).map { it.groupValues[1].trim() }.toList()
        val parsed = items.mapIndexed { index, item ->
            item.copy(
                size = sizes.getOrElse(index) { "" },
                creator = creators.getOrElse(index) { "" },
            )
        }
        return CxDocPage(parsed, enc = hiddenValue(html, "enc"))
    }

    /** `ananas/status` 的 JSON；不是对象就返回 null，由调用方补默认值。 */
    fun parseMeta(body: String): CxDocMeta? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return CxDocMeta(
            objectId = json.optString("objectid"),
            pageNum = json.optString("pagenum").toIntOrNull() ?: 0,
            wordCount = json.optString("wordcount").toIntOrNull() ?: 0,
            filename = json.optString("filename"),
        )
    }

    /**
     * 心跳的 `d` 原文（紧凑 JSON，字段顺序即老项目 dict 的插入顺序）。
     *
     * `ext` 是嵌套 JSON 字符串：`{"_from_":"cid_clz_uid_fileEnc"}`；没有 fileEnc 时
     * 退到 `cid_clz_uid_dataId`。
     */
    fun heartbeatJson(beat: CxDocBeat, uid: String): String {
        val from = if (beat.fileEnc.isNotBlank()) {
            "${beat.courseId}_${beat.clazzId}_${uid}_${beat.fileEnc}"
        } else {
            "${beat.courseId}_${beat.clazzId}_${uid}_${beat.dataId}"
        }
        val ext = "{\"_from_\":\"$from\"}"
        return buildString {
            append("{\"r\":\"").append(beat.objectId).append("\",")
            append("\"t\":\"doc\",")
            append("\"l\":1,\"f\":4,\"p\":1,\"tp\":1,")
            append("\"wc\":").append(beat.wordCount).append(',')
            append("\"ic\":").append(beat.pageNum).append(',')
            append("\"v\":2,")
            append("\"s\":").append(beat.state).append(',')
            append("\"h\":").append(beat.height).append(',')
            append("\"ext\":\"").append(ext.replace("\"", "\\\"")).append("\"}")
        }
    }

    /** `enc = MD5(percentEncode(json) + "readPoint" + ts + uid + SALT)`。 */
    fun signHeartbeat(json: String, timestampMs: Long, uid: String): String {
        val raw = percentEncode(json) + "readPoint" + timestampMs + uid + ENC_SALT
        return MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * Python `urllib.parse.quote` 的等价实现：只保留 `A-Z a-z 0-9 - _ . ~`
     * （`allowSlash` 时保留 `/`），空格编码为 `%20` 而不是 `+`。
     */
    fun percentEncode(value: String, allowSlash: Boolean = false): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            val safe = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~' || (allowSlash && ch == '/')
            if (safe) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
            }
        }
        return out.toString()
    }

    private fun hiddenValue(html: String, fieldId: String): String {
        for (tag in INPUT_RE.findAll(html).map { it.value }) {
            if (attr(tag, "id") == fieldId) return attr(tag, "value").orEmpty()
        }
        return ""
    }

    /** 属性匹配要求前面是空白，避免把 `dataType=` 当成 `type=`。 */
    private fun attr(tag: String, name: String): String? =
        Regex("""[\s]$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    private fun decode(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private val UL_RE = Regex("""<ul\b[^>]*\bclass="[^"]*\bdataBody_td\b[^"]*"[^>]*>""")
    private val INPUT_RE = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SIZE_RE = Regex("""<li class="dataBody_size_stu">\s*([^<]+)""")
    private val CREATOR_RE = Regex("""<li class="dataBody_creater_stu">\s*<span[^>]*>([^<]+)""")
}