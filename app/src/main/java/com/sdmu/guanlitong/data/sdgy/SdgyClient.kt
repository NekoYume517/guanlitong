package com.sdmu.guanlitong.data.sdgy

import android.os.Build
import com.sdmu.guanlitong.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 山管 App（移动校园）协议客户端，全部内置在管院通里，不依赖任何分包或远程脚本。
 *
 * 调用方负责放到 IO 线程执行；一个实例对应一次登录会话。
 */
class SdgyClient(
    private val deviceId: String,
    initial: SdgySession? = null,
) {

    var session: SdgySession = initial ?: SdgySession(deviceId = deviceId)

    init {
        if (session.deviceId.isBlank()) session.deviceId = deviceId
    }

    // ---------- 登录 / 会话 ----------

    /** RSA/AES 握手：sk 由本地生成，onlyCode 由服务端下发。 */
    fun handshake() {
        // 旧 HTTP 会话可能已经被顶号，必须连同 Cookie 一起重置。
        session.cookies.clear()
        val (_, keyText) = open(
            SdgyConst.SERVER + SdgyConst.PATH_KEY,
            "GET",
            null,
            mapOf("Asym-Key" to "public"),
        )
        val publicKey = runCatching { JSONObject(keyText).optString("data") }
            .getOrNull()
            .orEmpty()
        if (publicKey.isBlank()) {
            throw SdgyException("山管服务器握手失败，请稍后再试")
        }

        val sk = SdgyCrypto.newAesKey()
        val payload = JSONObject().put("sk", sk).toString()
        val encrypted = SdgyCrypto.rsaEncryptBlocks(payload.toByteArray(Charsets.UTF_8), publicKey)
        val (_, response) = open(
            SdgyConst.SERVER + SdgyConst.PATH_KEY,
            "POST",
            encrypted.toByteArray(Charsets.UTF_8),
            mapOf("Asym-Key" to "private"),
        )
        val onlyCode = runCatching {
            SdgyCrypto.aesDecrypt(JSONObject(response).optString("data"), sk)
        }.getOrNull().orEmpty()
        if (onlyCode.isBlank()) {
            throw SdgyException("山管服务器握手失败，请稍后再试")
        }

        session.sk = sk
        session.onlyCode = onlyCode
    }

    /**
     * 账号密码登录。
     *
     * 成功返回新的会话；账号需要先在官方 App 重置密码时抛出
     * [SdgyPasswordResetException]，其余业务失败抛出 [SdgyException]。
     */
    fun login(userId: String, password: String): SdgySession {
        handshake()
        val body = JSONObject().apply {
            put("userId", userId)
            put("passw", password)
            put("schoolId", SdgyConst.SCHOOL_ID)
            put("province", "")
            put("city", "")
            put("clientVersion", SdgyConst.CLIENT_VERSION)
            put("serverApiVersion", SdgyConst.CLIENT_VERSION)
            put("androidClientVersionCode", SdgyConst.VERSION_CODE)
            put("deviceModel", Build.MODEL)
            put("deviceType", "1")
            put("deviceVersion", Build.VERSION.RELEASE)
            put("latitude", "")
            put("longitude", "")
            put("imei", "")
            put("deviceId", deviceId)
        }
        val result = encryptedCall(SdgyConst.PATH_LOGIN, "POST", body = body)
        val user = result as? JSONObject ?: JSONObject()
        session.user = user
        session.savedAt = System.currentTimeMillis()

        val resetUrl = user.optString("updateSecret_url")
        if (resetUrl.isNotEmpty()) throw SdgyPasswordResetException(resetUrl)
        if (session.sessionKey.isBlank()) {
            throw SdgyException("登录响应缺少会话，请稍后再试")
        }
        return session
    }

    /** 会话探测：任一加密接口成功即在线；被顶号时服务端返回 1000/1003/1006。 */
    fun ping() {
        encryptedCall(SdgyConst.PATH_SYSINFO, "GET")
    }

    fun logout() {
        runCatching { encryptedCall(SdgyConst.PATH_LOGOUT, "POST", body = JSONObject()) }
        session.user = JSONObject()
    }

    // ---------- 内置业务接口（后续山管页面直接调用） ----------

    fun appList(type: String = "1"): JSONArray {
        val result = encryptedCall(
            SdgyConst.PATH_APP_LIST,
            "GET",
            params = JSONObject()
                .put("zdlx", "3")
                .put("khdbb", SdgyConst.CLIENT_VERSION)
                .put("type", type),
        )
        return when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("DATA")
                ?: result.optJSONArray("data")
                ?: JSONArray()
            else -> JSONArray()
        }
    }

    fun termInfo(): JSONObject = encryptedCall(SdgyConst.PATH_TERM_INFO, "GET") as JSONObject

    fun scheduleOfTerm(term: String, termId: String): JSONArray = encryptedCall(
        SdgyConst.PATH_SCHEDULE_TERM,
        "GET",
        params = JSONObject().put("xn", term).put("xq", termId),
    ) as JSONArray

    /** 节次时间表：djj → (上课时间, 下课时间)。 */
    fun courseSections(): JSONArray = encryptedCall(
        SdgyConst.PATH_COURSE_SECTIONS,
        "GET",
    ) as JSONArray

    /** 课程详情（挂科率、平均分、上课人数、教师地点等）。 */
    fun courseDetail(xlh: String, djz: String, xqj: Int): JSONObject = encryptedCall(
        SdgyConst.PATH_COURSE_DETAIL,
        "GET",
        params = JSONObject().put("xlh", xlh).put("djz", djz).put("xqj", xqj.toString()),
    ) as JSONObject

    fun classmates(detail: JSONObject): JSONArray = encryptedCall(
        SdgyConst.PATH_CLASSMATES,
        "GET",
        params = JSONObject()
            .put("xn", detail.optString("xn"))
            .put("xq", detail.optString("xq"))
            .put("dm", detail.optString("dm"))
            .put("djz", detail.optString("djz"))
            .put("xqj", detail.optString("xqj"))
            .put("djj", detail.optString("djj")),
    ) as JSONArray

    fun topScorers(detail: JSONObject): JSONArray = encryptedCall(
        SdgyConst.PATH_TOP_SCORERS,
        "GET",
        params = JSONObject()
            .put("xn", detail.optString("xn"))
            .put("xq", detail.optString("xq"))
            .put("kcmc", detail.optString("kcmc"))
            .put("zgfzscj", detail.optString("zgfzscj")),
    ) as JSONArray

    fun springboardUrl(appId: String): String =
        "${SdgyConst.SERVER}${SdgyConst.PATH_SPRINGBOARD}?yyid=" +
            URLEncoder.encode(appId, "UTF-8")

    /** 应用图标的下载地址：YYTB 可能是完整 URL、相对路径或图标 id。 */
    fun imageUrl(iconId: String): String = when {
        iconId.isBlank() -> ""
        iconId.startsWith("http://") || iconId.startsWith("https://") -> iconId
        iconId.startsWith("/") -> SdgyConst.SERVER + iconId
        else -> "${SdgyConst.SERVER}${SdgyConst.PATH_IMAGE}/$iconId"
    }

    /** 内置 WebView 的免登凭据（官方 WebViewActivity.Y2 / LightAppActivity.g3 同款）。 */
    fun webAuth(): SdgyWebAuth {
        if (session.sk.isBlank()) handshake()
        val token = SdgyCrypto.aesEncrypt(session.identityJson(), session.sk)
        return SdgyWebAuth(
            headers = baseHeaders(session.sk),
            cookies = mapOf(
                "accessToken" to token,
                "uniqueCode" to session.onlyCode,
                "AppType" to SdgyConst.APP_TYPE,
            ),
        )
    }

    /** LX=8 第三方 SSO：把凭据直接拼在目标地址的查询串里。 */
    fun thirdPartySsoUrl(target: String, appId: String): String {
        if (target.isBlank()) return target
        if (session.sk.isBlank()) handshake()
        val token = SdgyCrypto.aesEncrypt(session.identityJson(), session.sk)
        val sep = if (target.contains('?')) '&' else '?'
        return target + sep + "accessToken=" + URLEncoder.encode(token, "UTF-8") +
            "&uniqueCode=" + URLEncoder.encode(session.onlyCode, "UTF-8") +
            "&yyid=" + URLEncoder.encode(appId, "UTF-8")
    }

    // ---------- HTTP ----------

    private fun encryptedCall(
        path: String,
        method: String,
        params: JSONObject? = null,
        body: JSONObject? = null,
    ): Any {
        if (session.sk.isBlank()) handshake()
        val sk = session.sk
        var url = SdgyConst.SERVER + path
        var payload: ByteArray? = null
        if (method == "GET" && params != null && params.length() > 0) {
            url += "?data=" + URLEncoder.encode(
                SdgyCrypto.aesEncrypt(params.toString(), sk),
                "UTF-8",
            )
        }
        if (body != null) {
            payload = SdgyCrypto.aesEncrypt(body.toString(), sk).toByteArray(Charsets.UTF_8)
        }
        val (status, text) = open(url, method, payload, baseHeaders(sk))
        if (status !in 200..299) {
            throw SdgyException("山管服务器返回 HTTP $status", status.toString())
        }
        val envelope = runCatching { JSONObject(text) }.getOrNull()
            ?: throw SdgyException("山管服务器返回了无法识别的数据")
        return parseEnvelope(envelope, sk)
    }

    private fun baseHeaders(sk: String): Map<String, String> = mapOf(
        "User-Agent" to "guanlitong/${BuildConfig.VERSION_NAME}",
        "AppType" to SdgyConst.APP_TYPE,
        "Unique-Code" to session.onlyCode,
        "Access-Token" to SdgyCrypto.aesEncrypt(session.identityJson(), sk),
    )

    private fun parseEnvelope(envelope: JSONObject, sk: String): Any {
        val code = envelope.optString("code")
        if (code != "0") {
            throw SdgyException(envelope.optString("msg").ifBlank { "业务错误 $code" }, code)
        }
        val data = envelope.opt("data")
        if (data !is String || data.isEmpty()) return JSONObject()
        val plain = SdgyCrypto.aesDecrypt(data, sk)
        return if (plain.trimStart().startsWith("[")) JSONArray(plain) else JSONObject(plain)
    }

    private fun open(
        url: String,
        method: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (session.cookies.isNotEmpty()) {
                setRequestProperty(
                    "Cookie",
                    session.cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" },
                )
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            absorbCookies(connection.headerFields)
            return status to text
        } finally {
            connection.disconnect()
        }
    }

    private fun absorbCookies(headers: Map<String, List<String>>?) {
        headers?.forEach { (key, values) ->
            if (!key.equals("Set-Cookie", ignoreCase = true)) return@forEach
            values?.forEach { line ->
                val pair = line.substringBefore(';')
                val name = pair.substringBefore('=', "").trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty()) session.cookies[name] = value
            }
        }
    }

    companion object {
        val deviceModel: String
            get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}