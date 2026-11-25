package com.sdmu.guanlitong.data.pu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 上游 `LoginApi.java` 的移植（与业务接口同一主机 `apis.pocketuni.net`，
 * 上游 `LoginApi` 用 `Api.BASE + PATH`）。
 *
 * 错误文案与上游逐字一致：学号/密码为空、go_id 非法、`code != 0`、
 * 缺少 data、token 不是 32 位十六进制、非 JSON 响应、网络错误。
 * 密码只存在于一次请求体内，不落盘、不打印（§6.4 第 5 条）。
 */
object PuLoginApi {

    const val PATH = "/uc/user/login"

    private const val HOST = "https://apis.pocketuni.net"
    private const val CONNECT_MS = 12000
    private const val READ_MS = 20000
    private const val LOGIN_UA = "Dalvik/2.1.0 (Linux; U; Android 15; 23127PN0CC Build/fc0a665.0)"
    private val TOKEN_RE = Regex("[0-9A-Fa-f]{32}")

    suspend fun login(account: String, password: String, schoolId: Long): PuSession =
        withContext(Dispatchers.IO) {
            val name = account.trim()
            if (name.isEmpty()) throw PuException("学号不能为空", 400)
            if (password.isEmpty()) throw PuException("密码不能为空", 400)
            if (schoolId <= 0L) throw PuException("学校 go_id 必须是正整数", 400)

            val body = JSONObject()
                .put("sid", schoolId)
                .put("type", 0)
                .put("device", "app")
                .put("userName", name)
                .put("password", password)

            val conn = (URL(HOST + PATH).openConnection() as HttpURLConnection)
            try {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_MS
                conn.readTimeout = READ_MS
                conn.doInput = true
                conn.doOutput = true
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", LOGIN_UA)
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }

                val status = conn.responseCode
                val text = readAll(if (status >= 400) conn.errorStream else conn.inputStream)
                val json = (if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull())
                    ?: throw PuException("登录接口返回非 JSON: HTTP $status", 502)

                val code = json.optInt("code", 0)
                if (code != 0) {
                    val msg = json.optString("message", json.optString("msg", ""))
                    throw PuException(msg.ifBlank { "登录失败 code=$code" }, 401)
                }
                val data = json.optJSONObject("data")
                    ?: throw PuException("登录返回缺少 data", 502)

                val token = data.optString("token", "").trim()
                if (!TOKEN_RE.matches(token)) {
                    throw PuException("登录返回的 token 格式异常（应为 32 位十六进制）", 502)
                }
                val goId = data.opt("sid")?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                    ?: schoolId.toString()
                val base = data.optJSONObject("baseUserInfo")
                val realName = base?.optString("realname", "").orEmpty().trim()
                    .ifBlank { base?.optString("nickname", "").orEmpty().trim() }
                val userName = base?.optString("username", "").orEmpty().trim()
                    .ifBlank { name }

                return@withContext PuSession(
                    token = token,
                    goId = goId,
                    name = realName,
                    account = userName,
                )
            } catch (e: PuException) {
                throw e
            } catch (e: IOException) {
                throw PuException("网络错误: ${e.message ?: e.javaClass.simpleName}", 502)
            } finally {
                conn.disconnect()
            }
        }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        return runCatching { stream.bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
    }
}