package com.sdmu.guanlitong.data.srun

import org.json.JSONException
import org.json.JSONObject

/**
 * 门户响应的解析，对照 Go 版的 `parseJSONP / initGetip / getToken / login / checkOnline`。
 *
 * 两处与 Go 对齐的「脾气」：
 * - `res`、`error` 这些字段只有真的是字符串才算数。Go 用的是类型断言，
 *   门户回数字时拿到的就是空串；
 * - 登录成功的判据只看 `res` 与 `access_token`，不看提示文字。
 */
internal object SrunParser {

    private val USER_IP = Regex("""id="user_ip" value="(.*?)"""")
    private val QUERY_STRING = Regex("""\?[^\s"'<>]*""")

    /** 门户首页里的 `<input id="user_ip" value="…">`。 */
    fun parseUserId(body: String): String? = USER_IP.find(body)?.groupValues?.getOrNull(1)

    /** 剥掉 JSONP 的回调包装，取括号里的对象；连括号都没有就当整段是 JSON。 */
    fun parseJsonp(body: String): JSONObject {
        var text = body.trim()
        val open = text.indexOf('(')
        if (open >= 0) {
            val close = text.lastIndexOf(')')
            if (close > open) text = text.substring(open + 1, close)
        }
        return try {
            // Go 的 json.Unmarshal 遇到字面量 null 不报错、给一个空 map，后面各字段都读成空。
            if (text == "null") JSONObject() else JSONObject(text)
        } catch (e: JSONException) {
            throw SrunException(
                "门户返回的内容认不出来（原始：${snippet(body)}）",
                SrunErrorKind.PARSE,
                e,
            )
        }
    }

    fun parseLogin(body: String): SrunLoginResult {
        val json = parseJsonp(body)
        val res = json.stringOrNull("res")
        val error = json.stringOrNull("error")
        val hasToken = json.has("access_token")
        return SrunLoginResult(
            accepted = res == "ok" || res == "success" ||
                (hasToken && (error.isEmpty() || error == "ok")),
            error = error,
            errorMsg = json.stringOrNull("error_msg"),
            sucMsg = json.stringOrNull("suc_msg"),
            summary = "res=${json.display("res")} error=${json.display("error")} " +
                "error_msg=${json.display("error_msg")} suc_msg=${json.display("suc_msg")}",
        )
    }

    fun parseOnline(body: String): SrunOnlineStatus {
        val json = parseJsonp(body)
        val error = json.display("error")
        if (error.contains("not_online")) return SrunOnlineStatus(online = false, detail = error)
        val user = json.stringOrNull("user_name")
        if (user.isNotEmpty()) {
            return SrunOnlineStatus(
                online = true,
                userName = user,
                onlineIp = json.display("online_ip"),
                deviceTotal = json.display("online_device_total"),
                addTime = json.display("add_time"),
                allBytes = json.display("all_bytes"),
                detail = error,
            )
        }
        return SrunOnlineStatus(online = false, detail = "unknown: " + snippet(body))
    }

    /** 注销的应答：门户明说 ok / 不 ok；两个字段都没有就返回 null。认不出会抛。 */
    fun parseLogout(body: String): Boolean? {
        val json = parseJsonp(body)
        val res = json.stringOrNull("res")
        val error = json.stringOrNull("error")
        return when {
            res == "ok" || error == "ok" -> true
            json.has("res") || json.has("error") -> false
            else -> null
        }
    }

    /** 截到 [max] 个字，超了加省略号（Go 的 `trunc`）。 */
    fun trunc(text: String, max: Int): String {
        val trimmed = text.trim()
        return if (trimmed.length <= max) trimmed else trimmed.take(max) + "..."
    }

    /**
     * 认不出响应时带在提示里的正文片段：[trunc] 之后把 URL 的查询串抹掉。
     * 门户页面会把带密码的请求地址原样嵌在 HTML 里，不能整段回显到界面上。
     */
    fun snippet(body: String): String = QUERY_STRING.replace(trunc(body, 200)) { "${it.value[0]}…" }
}

/** 字段值是字符串才返回，否则是空串（同 Go 的类型断言）。 */
internal fun JSONObject.stringOrNull(key: String): String =
    if (has(key)) opt(key) as? String ?: "" else ""

/** 同 Go 的 `%v`：字符串原样，数字按十进制，缺失或 null 是 `<nil>`。 */
internal fun JSONObject.display(key: String): String {
    val value = if (has(key)) opt(key) else return "<nil>"
    return when {
        value == null || value == JSONObject.NULL -> "<nil>"
        value is Number -> numberText(value)
        else -> value.toString()
    }
}

/** Go 把数字都解析成 float64 再打印，大数会变成 1.7e+09；这里照常显示整数。 */
private fun numberText(value: Number): String {
    val double = value.toDouble()
    return if (double == Math.rint(double) && Math.abs(double) < 1e15) {
        double.toLong().toString()
    } else {
        double.toString()
    }
}
