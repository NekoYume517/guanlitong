package com.sdmu.guanlitong.data.jwxt

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/** 教务系统固定入口。学校只有一个教务域名，不再做多地址适配。 */
object JwxtOrigin {
    const val ORIGIN = "https://jwxt.sdmu.edu.cn"
    const val BASE = "$ORIGIN/jwglxt"
    const val LOGIN_URL = "$BASE/xtgl/login_slogin.html"
}

class JwxtLoginException(message: String) : Exception(message)

data class JwxtLoginResult(
    val success: Boolean,
    val message: String,
    val cookies: String = "",
    /** 验证码错误时界面自动换一张。 */
    val captchaWrong: Boolean = false,
)

/**
 * 教务系统原生登录（移植自 sdmu-website-2 的 `app/jwxt_login.py`）。
 *
 * 流程：登录页取 csrftoken → `/kaptcha` 取验证码 → `/xtgl/login_getPublicKey.html`
 * 取 RSA 公钥 → PKCS#1 v1.5 加密密码 → POST `login_slogin.html`。
 * 成功后把会话 Cookie 交给 WebView，原生课表与网页共用同一份登录态。
 *
 * 页面 GET 不带 `X-Requested-With`，否则学校网关会直接返回 901。
 */
class JwxtLoginClient(serviceUrl: String = JwxtOrigin.LOGIN_URL) {

    val baseUrl: String = JwxtClient.baseOf(serviceUrl)
        .ifBlank { JwxtOrigin.BASE }

    private val origin: String = runCatching {
        val url = URL(baseUrl)
        val port = if (url.port > 0) ":${url.port}" else ""
        "${url.protocol}://${url.host}$port"
    }.getOrDefault(JwxtOrigin.ORIGIN)

    private val cookies = linkedMapOf<String, String>()

    private var csrftoken: String = ""

    /** 登录页本身被 302 到首页，说明当前会话仍然有效。 */
    var alreadyLoggedIn: Boolean = false
        private set

    suspend fun openLoginPage(): Unit = withContext(Dispatchers.IO) {
        csrftoken = ""
        alreadyLoggedIn = false
        val response = execute(
            url = "$baseUrl/xtgl/login_slogin.html",
            method = "GET",
            accept = HTML_ACCEPT,
        )
        if (response.code == 302 || response.code == 301) {
            if (isSuccessLocation(response.location)) {
                alreadyLoggedIn = true
                return@withContext
            }
            throw JwxtLoginException("登录页跳转到了 ${response.location.ifBlank { "未知地址" }}")
        }
        if (response.code !in 200..299) {
            throw JwxtLoginException("无法打开教务登录页（HTTP ${response.code}）")
        }
        csrftoken = extractCsrfToken(response.text)
            ?: extractCsrfTokenFromScript(response.text)
            ?: ""
    }

    /** 每次都换一张新的验证码；调用前必须先 [openLoginPage]（或登录失败后保持同一会话）。 */
    suspend fun fetchCaptcha(): ByteArray = withContext(Dispatchers.IO) {
        val response = execute(
            url = "$baseUrl/kaptcha?time=${System.currentTimeMillis()}",
            method = "GET",
            accept = "image/avif,image/webp,image/png,image/*,*/*;q=0.8",
        )
        if (response.code !in 200..299) {
            throw JwxtLoginException("获取验证码失败（HTTP ${response.code}）")
        }
        if (response.bytes.isEmpty()) {
            throw JwxtLoginException("验证码图片为空")
        }
        response.bytes
    }

    suspend fun login(
        username: String,
        password: String,
        captcha: String,
    ): JwxtLoginResult = withContext(Dispatchers.IO) {
        val (modulus, exponent) = fetchPublicKey()
        val encrypted = rsaEncrypt(password, modulus, exponent)

        val form = linkedMapOf(
            "yhm" to username,
            "mm" to encrypted,
            "yzm" to captcha,
            "csrftoken" to csrftoken,
            "language" to "zh_CN",
            "dlmmsfknt" to "0",
            "ydappsfbldlzt" to "0",
            "yzcskz" to "-1",
            "mmsfjm" to "1",
            "mmsrddcshkzfs" to "0",
            "dlsbsdsj" to "3",
        )
        val response = execute(
            url = "$baseUrl/xtgl/login_slogin.html?time=${System.currentTimeMillis()}",
            method = "POST",
            fields = form,
            accept = HTML_ACCEPT,
        )

        when (response.code) {
            301, 302 -> {
                val location = response.location
                if (isSuccessLocation(location)) {
                    return@withContext success()
                }
                // 正方部分版本会先跳回登录页再跳首页，跟随一跳再判断。
                if (location.contains("login_slogin", ignoreCase = true)) {
                    val secondUrl = if (location.startsWith("http")) {
                        location
                    } else {
                        "$origin$location"
                    }
                    val second = execute(secondUrl, "GET", accept = HTML_ACCEPT)
                    if (isSuccessLocation(second.location)) {
                        return@withContext success()
                    }
                }
                JwxtLoginResult(
                    success = false,
                    message = "登录失败：服务器跳转到了 ${location.ifBlank { "未知地址" }}",
                )
            }

            200 -> parseLoginError(response.text)

            else -> JwxtLoginResult(
                success = false,
                message = "登录失败（HTTP ${response.code}）",
            )
        }
    }

    private fun success() = JwxtLoginResult(
        success = true,
        message = "登录成功",
        cookies = exportCookies(),
    )

    private fun parseLoginError(html: String): JwxtLoginResult {
        val message = when {
            html.contains("用户名或密码不正确") || html.contains("用户名或密码错误") ->
                "用户名或密码不正确"

            html.contains("验证码错误") || html.contains("验证码不正确") -> "验证码错误"
            html.contains("密码已到期") || html.contains("密码过期") ||
                html.contains("修改密码") -> "密码已到期，请先修改密码"

            html.contains("用户不存在") || html.contains("用户名不存在") -> "用户名不存在"
            else -> "登录失败，请检查账号、密码和验证码"
        }
        return JwxtLoginResult(
            success = false,
            message = message,
            captchaWrong = message.contains("验证码"),
        )
    }

    private suspend fun fetchPublicKey(): Pair<String, String> {
        val response = execute(
            url = "$baseUrl/xtgl/login_getPublicKey.html?time=${System.currentTimeMillis()}",
            method = "GET",
            accept = "application/json, text/javascript, */*; q=0.01",
        )
        if (response.code !in 200..299) {
            throw JwxtLoginException("获取登录公钥失败（HTTP ${response.code}）")
        }
        val json = runCatching { JSONObject(response.text) }.getOrNull()
            ?: throw JwxtLoginException("登录公钥格式异常")
        val modulus = json.optString("modulus")
        val exponent = json.optString("exponent")
        if (modulus.isBlank() || exponent.isBlank()) {
            throw JwxtLoginException("登录公钥为空")
        }
        return modulus to exponent
    }

    private fun rsaEncrypt(password: String, modulusBase64: String, exponentBase64: String): String {
        val modulus = BigInteger(1, Base64.decode(modulusBase64, Base64.NO_WRAP))
        val exponent = BigInteger(1, Base64.decode(exponentBase64, Base64.NO_WRAP))
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun exportCookies(): String = synchronized(cookies) {
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    // ── HTTP ────────────────────────────────────────────────

    private data class Response(
        val code: Int,
        val location: String,
        val bytes: ByteArray,
    ) {
        val text: String = bytes.toString(Charsets.UTF_8)
    }

    private fun execute(
        url: String,
        method: String,
        fields: Map<String, String>? = null,
        accept: String,
    ): Response {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            setRequestProperty("Referer", "$baseUrl/xtgl/login_slogin.html")
            setRequestProperty("Accept", accept)
            val cookieHeader = exportCookies()
            if (cookieHeader.isNotEmpty()) {
                setRequestProperty("Cookie", cookieHeader)
            }
            if (fields != null) {
                doOutput = true
                setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8",
                )
            }
        }

        try {
            if (fields != null) {
                val body = fields.entries.joinToString("&") {
                    "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            captureCookies(connection)
            android.util.Log.d(
                "JwxtLoginClient",
                "$method ${url.substringBefore('?')} -> $code",
            )
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Response(
                code = code,
                location = connection.getHeaderField("Location").orEmpty(),
                bytes = bytes,
            )
        } catch (e: JwxtLoginException) {
            throw e
        } catch (e: IOException) {
            throw JwxtLoginException("无法连接教务系统：${e.message ?: "网络错误"}")
        } finally {
            connection.disconnect()
        }
    }

    private fun captureCookies(connection: HttpURLConnection) {
        val setCookies = connection.headerFields.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value.orEmpty() }
        synchronized(cookies) {
            for (header in setCookies) {
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=', "").trim()
                if (name.isBlank()) continue
                val value = pair.substringAfter('=', "").trim()
                if (value.isEmpty()) {
                    // Max-Age=0 / 过期删除
                    cookies.remove(name)
                } else {
                    cookies[name] = value
                }
            }
        }
    }

    private fun isSuccessLocation(location: String): Boolean {
        val lowered = location.lowercase()
        return lowered.contains("index_initmenu") ||
            lowered.contains("auth_loginsuccess") ||
            lowered.contains("loginsuccess")
    }

    private fun extractCsrfToken(html: String): String? {
        val patterns = listOf(
            Regex(
                """<input[^>]*name\s*=\s*["']csrftoken["'][^>]*value\s*=\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """<input[^>]*value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']csrftoken["']""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """<meta[^>]*name\s*=\s*["']csrf-token["'][^>]*content\s*=\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE,
            ),
        )
        for (pattern in patterns) {
            pattern.find(html)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun extractCsrfTokenFromScript(html: String): String? {
        val pattern = Regex(
            """csrf[_-]?token["']?\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.find(html)?.groupValues?.get(1)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8"
    }
}