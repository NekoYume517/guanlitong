package com.sdmu.guanlitong.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 与 sdmu-website-2（抢课助手）后端通信的最小客户端。
 *
 * 后端已提供 `POST /api/login`（JSON）与 `GET /api/user/info`，
 * 登录成功后 Set-Cookie 里的 Flask session 会被注入 WebView，
 * 于是原生账号管理和网页端共用同一份登录态。
 */
object ApiClient {

    data class LoginResult(
        val success: Boolean,
        val message: String,
        val cookie: String? = null,
    )

    suspend fun login(baseUrl: String, username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            val base = normalizeBaseUrl(baseUrl)
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$base/api/login").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                val payload = JSONObject()
                    .put("username", username)
                    .put("password", password)
                    .toString()
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val ok = code in 200..299 && json?.optBoolean("success", false) == true
                val message = json?.optString("message").orEmpty().ifBlank {
                    if (ok) "登录成功" else "登录失败（HTTP $code）"
                }
                LoginResult(
                    success = ok,
                    message = message,
                    cookie = if (ok) extractSessionCookie(conn) else null,
                )
            } catch (t: Throwable) {
                LoginResult(
                    success = false,
                    message = "无法连接服务：${t.message ?: t.javaClass.simpleName}",
                )
            } finally {
                conn?.disconnect()
            }
        }

    private fun extractSessionCookie(conn: HttpURLConnection): String? {
        val headers = conn.headerFields ?: return null
        val cookieHeaders = headers.entries
            .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
            .flatMap { it.value.orEmpty() }
        if (cookieHeaders.isEmpty()) return null
        // 取 Flask 的 session cookie（也可能有 remember_token 等），拼成一次写入的字符串。
        return cookieHeaders
            .mapNotNull { it.substringBefore(";").trim().takeIf(String::isNotBlank) }
            .joinToString("; ")
            .ifBlank { null }
    }

    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isBlank()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://$s"
        }
        return s
    }
}
