package com.sdmu.guanlitong.data.zhs

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 智慧树的最小 HTTP 客户端（HttpURLConnection，不引入第三方依赖）。
 *
 * - 手动跟随 3xx：CAS 认证链每一跳的 `Set-Cookie` 都要收下（比如第一跳的 CASTGC）；
 * - Cookie 按「名字 → 值」全局共享，和参考实现一次性带上全部 Cookie 的行为一致；
 * - 所有域名共用一份 Cookie，不做域匹配——这些接口都在 zhihuishu.com 下。
 */
internal class ZhsHttp(
    val cookies: MutableMap<String, String> = LinkedHashMap(),
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {

    data class Response(val code: Int, val url: String, val body: String)

    fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
        execute("GET", url, null, headers)

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): Response {
        val body = form.entries.joinToString("&") { (key, value) ->
            urlEncode(key) + "=" + urlEncode(value)
        }
        return execute(
            "POST",
            url,
            body,
            // 调用方可以覆盖 Content-Type（stuonline 需要带 charset）。
            mapOf("Content-Type" to "application/x-www-form-urlencoded") + headers,
        )
    }

    private fun execute(
        method: String,
        url: String,
        body: String?,
        extraHeaders: Map<String, String>,
    ): Response {
        var current = URL(url)
        var currentMethod = method
        var currentBody = body
        var hops = 0
        while (true) {
            val connection = open(currentMethod, current, currentBody, extraHeaders)
            val code = connection.responseCode
            collectCookies(connection)
            val location = connection.getHeaderField("Location")
            if (code in REDIRECT_CODES && location != null && hops < MAX_REDIRECTS) {
                connection.disconnect()
                current = URL(current, location)
                // CAS 链路后续跳转都是 GET；POST 的表单不能再带一遍。
                currentMethod = "GET"
                currentBody = null
                hops++
                continue
            }
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            return Response(code, current.toString(), text)
        }
    }

    private fun open(method: String, url: URL, body: String?, extra: Map<String, String>): HttpURLConnection {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", userAgent)
            if (cookies.isNotEmpty()) {
                setRequestProperty("Cookie", cookieHeader())
            }
            extra.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return connection
    }

    private fun collectCookies(connection: HttpURLConnection) {
        val fields = connection.headerFields ?: return
        for ((name, values) in fields) {
            if (name == null || !name.equals("Set-Cookie", ignoreCase = true)) continue
            for (raw in values ?: continue) {
                val pair = parseSetCookie(raw) ?: continue
                cookies[pair.first] = pair.second
            }
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 10

        /**
         * 只取 `name=value` 部分；`Path` / `Domain` / `Expires` 一律忽略，
         * 属性里带逗号的 Expires 也不会被误切。值缺失（`name=`）视为删除。
         */
        internal fun parseSetCookie(raw: String): Pair<String, String>? {
            val pair = raw.substringBefore(';').trim()
            val index = pair.indexOf('=')
            if (index <= 0) return null
            val name = pair.substring(0, index).trim()
            val value = pair.substring(index + 1).trim()
            if (name.isBlank()) return null
            return if (value.isBlank()) name to "" else name to value
        }

        internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

/** 网络层异常的常见包装：统一成带中文提示的 [ZhsException]。 */
internal fun ioError(cause: Throwable, fallback: String): ZhsException = when (cause) {
    is ZhsException -> cause
    else -> ZhsException(
        message = cause.zhsMessage().ifBlank { fallback },
        kind = ZhsErrorKind.NETWORK,
        cause = cause,
    )
}

/**
 * 401 / "invalid session" 判定。选择区间只扫描状态字段与固定文案，
 * 不把课程内容里的任意 "401" 当过期信号。
 */
internal fun zhsAuthRequired(body: String): Boolean {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.equals("invalid session", ignoreCase = true)) return true
    if (!trimmed.startsWith("{")) return false
    return runCatching {
        val json = org.json.JSONObject(trimmed)
        if (json.optBoolean("auth_required", false)) return@runCatching true
        for (key in arrayOf("code", "status", "statusCode", "status_code")) {
            if (json.opt(key)?.toString() == "401") return@runCatching true
        }
        val message = json.optString("msg").ifBlank { json.optString("message") }
        (message.contains("登录") && (message.contains("过期") || message.contains("失效"))) ||
            message.contains("未登录")
    }.getOrDefault(false)
}