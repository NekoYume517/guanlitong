package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 学习通账号登录（直连，不走 Worker / 代理）。
 *
 * 对照实现：D:\code\chaoxing-fenbushi\server\app\cx_session.py 的 `_do_login`
 * 与 `_fetch_mooc_cookies`。两条关键协议细节：
 * - 登录 POST 必须关闭自动跟随重定向，否则拿不到第一跳的 Set-Cookie；
 * - `{"status": true, "url": ...}` 是跳转响应，不算登录成功，要继续看 Cookie。
 *
 * 密码只出现在这一次请求的表单体里，不打印、不落盘。
 */
object CxLoginApi {

    private const val LOGIN_URL = "https://passport2.chaoxing.com/fanyalogin"
    private const val REFER =
        "https://passport2.chaoxing.com/login?refer=https%3A%2F%2Fi.chaoxing.com"
    private const val MOOC_URL = "https://mooc1.chaoxing.com/visit/interaction"
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 12; SM-G991B) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.6099.144 Mobile Safari/537.36"

    /** 出现任意一个就认为登录成功后拿到了可用会话。 */
    private val KEY_COOKIES = setOf(
        "fid", "UID", "_uid", "JSESSIONID", "DESSIONID",
        "cx_p_token", "xxtNum", "lv", "xxtreviewCount",
    )

    private val HTML_ERROR_RE = Regex(
        """(?:id=["']?\bmsg\b["']?[^>]*>|class=["'][^"']*error[^"']*["'][^>]*>)([^<]{2,80})""",
        RegexOption.IGNORE_CASE,
    )

    suspend fun login(phone: String, password: String): CxSession = withContext(Dispatchers.IO) {
        val uname = phone.trim()
        if (uname.isBlank()) throw CxException("请输入手机号", 400)
        if (password.isBlank()) throw CxException("请输入密码", 400)

        val form = listOf(
            "fid" to "-1",
            "uname" to uname,
            "password" to password,
            "refer" to REFER,
            "t" to "true",
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }

        val connection = (URL(LOGIN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false // 登录成功靠 302 上的 Set-Cookie，不跟第二跳
            connectTimeout = 12_000
            readTimeout = 20_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("User-Agent", MOBILE_UA)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Referer", REFER)
            setRequestProperty("Origin", "https://passport2.chaoxing.com")
            setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
        }

        val status: Int
        val body: String
        val cookies: MutableMap<String, String>
        try {
            val bytes = form.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            status = connection.responseCode
            body = readAll(
                if (status >= 400) connection.errorStream else connection.inputStream,
            )
            cookies = parseSetCookies(connection.headerFields)
        } finally {
            connection.disconnect()
        }

        // 1. 优先看 JSON：status=true 且没有 url 才是成功；
        //    status=true 带 url 是跳转，交给下面的 Cookie 判定。
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            // status 也可能是数字 1，统一走宽容判定，避免登录成功被误判失败。
            val ok = json.cxFlag("status")
            val redirectUrl = json.optString("url").takeUnless {
                it.isBlank() || it.equals("null", ignoreCase = true)
            }
            if (ok && redirectUrl == null) {
                val merged = fetchMoocCookies(cookies)
                if (merged.isEmpty()) {
                    throw CxException("登录失败，未拿到会话 Cookie", 502)
                }
                return@withContext sessionOf(uname, merged)
            }
            if (!ok) {
                val message = cleanText(json.optString("mes"))
                    .ifBlank { cleanText(json.optString("message")) }
                    .ifBlank { "手机号或密码错误" }
                throw CxException(message, 401)
            }
        }

        // 2. 200 / 302 + 关键 Cookie → 成功。
        if (status in setOf(200, 302) && cookies.keys.any { it in KEY_COOKIES }) {
            return@withContext sessionOf(uname, fetchMoocCookies(cookies))
        }

        // 3. 从 HTML 里抠错误提示。
        val htmlError = HTML_ERROR_RE.find(body)?.groupValues?.get(1)?.trim()
        if (!htmlError.isNullOrBlank()) throw CxException(htmlError, 401)

        throw CxException("登录失败（HTTP $status），请稍后重试", 502)
    }

    /**
     * 登录后补 `vc` / `vc3` 等课程页需要的 Cookie。
     * 失败不影响登录结果（参考实现的 `_fetch_mooc_cookies` 也是静默降级）。
     */
    private fun fetchMoocCookies(cookies: MutableMap<String, String>): Map<String, String> {
        runCatching {
            val connection = (URL(MOOC_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 10_000
                useCaches = false
                setRequestProperty("User-Agent", MOBILE_UA)
                setRequestProperty("Cookie", cookieHeader(cookies))
                setRequestProperty("Referer", "https://mooc1.chaoxing.com/")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            }
            try {
                connection.responseCode
                readAll(connection.inputStream)
                cookies.putAll(parseSetCookies(connection.headerFields))
            } finally {
                connection.disconnect()
            }
        }
        return cookies
    }

    private fun sessionOf(phone: String, cookies: Map<String, String>): CxSession =
        CxSession(
            phone = phone,
            cookies = cookies,
            uid = cookies["UID"] ?: cookies["_uid"].orEmpty(),
            fid = cookies["fid"].orEmpty(),
        )

    private fun cookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun cleanText(raw: String): String =
        raw.trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()

    private fun parseSetCookies(fields: Map<String, List<String>>?): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        fields.orEmpty().forEach { (name, values) ->
            if (!name.equals("Set-Cookie", ignoreCase = true)) return@forEach
            values.forEach { raw ->
                val first = raw.substringBefore(';').trim()
                val index = first.indexOf('=')
                if (index > 0) {
                    out[first.substring(0, index).trim()] = first.substring(index + 1).trim()
                }
            }
        }
        return out
    }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        return runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }
}