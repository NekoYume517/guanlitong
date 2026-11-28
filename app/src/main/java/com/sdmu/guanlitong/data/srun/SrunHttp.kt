package com.sdmu.guanlitong.data.srun

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 一次 GET 的发送方。[SrunPortal] 只拼 URL、解析响应，不关心请求怎么发出去；
 * 单测里换成假实现，整条认证流程就不碰网络。
 */
internal fun interface SrunTransport {

    /** 返回响应正文；不判断状态码（与 Go 版 `client.get` 一致），连不上就抛 [IOException]。 */
    @Throws(IOException::class)
    fun get(url: String): String
}

/**
 * 门户的 HTTP 客户端（HttpURLConnection，不引入第三方依赖），对照 Go 版的 `newClient` / `client.get`：
 * - 请求头只带 UA，正文最多读 1 MiB；
 * - 不看状态码，4xx/5xx 的正文也照常交给上层去解析；
 * - HTTPS 不校验证书与主机名：Go 版是 `InsecureSkipVerify: true`，这里保持一致，
 *   否则 https 永远失败，每次都要多走一趟明文 http 的兜底。
 *
 * 没有移植的部分：Go 版会用公共 DNS 解析门户的真实 IP，并全程直连那个 IP，
 * 为的是绕开路由器上 clash 的 fake-ip；手机上没有这个前提，直接用系统解析。
 */
internal class HttpSrunTransport(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 8_000,
) : SrunTransport {

    override fun get(url: String): String {
        val connection = SrunHttp.open(url, connectTimeoutMs, readTimeoutMs, followRedirects = true)
        return try {
            SrunHttp.readBody(connection)
        } finally {
            connection.disconnect()
        }
    }
}

/** 门户与探测共用的连接设置和读正文。 */
internal object SrunHttp {

    private const val MAX_BODY_BYTES = 1 shl 20
    private val CHARSET = Regex("""charset=([^; ]+)""", RegexOption.IGNORE_CASE)

    /** 信任所有证书（同 Go 版 `InsecureSkipVerify`；本项目其他校方接口也是这个口径）。 */
    private val trustEverything = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val lenientContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustEverything), SecureRandom())
        }
    }

    private val lenientHostname = HostnameVerifier { _, _ -> true }

    /**
     * 建好一条连接但不发请求。[followRedirects] 为 false 时跳转状态码原样返回，
     * 探测靠它看见门户劫持的 301/302。
     */
    fun open(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeoutMs
            this.readTimeout = readTimeoutMs
            instanceFollowRedirects = followRedirects
            useCaches = false
            setRequestProperty("User-Agent", SRUN_USER_AGENT)
            if (this is HttpsURLConnection) {
                sslSocketFactory = lenientContext.socketFactory
                hostnameVerifier = lenientHostname
            }
        }

    /** 发请求并读正文，最多 1 MiB；字符集跟 `Content-Type`，没写就按 UTF-8。 */
    fun readBody(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream: InputStream? = if (code >= 400) connection.errorStream else connection.inputStream
        val bytes = stream?.use { readLimited(it, MAX_BODY_BYTES) } ?: ByteArray(0)
        val charset = CHARSET.find(connection.contentType.orEmpty())
            ?.let { runCatching { Charset.forName(it.groupValues[1].trim('"')) }.getOrNull() }
            ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var size = 0
        while (size < limit) {
            val read = stream.read(buffer, size, limit - size)
            if (read < 0) break
            size += read
        }
        return buffer.copyOf(size)
    }
}

/**
 * 联网探测，对照 Go 版的 `probeOnce / probeCode`。
 *
 * 门户说「在线」不代表真能上网（会话脏了就是这样），所以另外问一次公网的 204 接口。
 * 只认 204：没认证时门户劫持页是 200，跳转是 301/302，这些都算不通。
 *
 * Go 版在 HTTP 不通时还会再查一次公共 DNS 来区分原因，那部分是给路由器的守护模式用的，没有移植。
 */
internal object SrunProbe {

    private const val TIMEOUT_MS = 3_000
    private const val ATTEMPTS = 3

    /** 探测一次，返回状态码；连不上返回 0（Go 版的 "000"）。 */
    fun codeOnce(url: String = SRUN_PROBE_URL): Int {
        val connection = SrunHttp.open(url, TIMEOUT_MS, TIMEOUT_MS, followRedirects = false)
        return try {
            connection.responseCode
        } catch (e: IOException) {
            0
        } finally {
            connection.disconnect()
        }
    }

    /** 最多探三次，拿到 204 就算能上网。 */
    suspend fun isReachable(url: String = SRUN_PROBE_URL): Boolean = withContext(Dispatchers.IO) {
        for (attempt in 0 until ATTEMPTS) {
            if (codeOnce(url) == 204) return@withContext true
            if (attempt < ATTEMPTS - 1) delay(150)
        }
        false
    }
}
