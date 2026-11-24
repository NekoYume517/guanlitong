package com.sdmu.guanlitong.data.sdgy

import org.json.JSONObject
import java.util.UUID

/**
 * 山管 App（联奕「移动校园」）的协议常量。
 *
 * 全部来自对官方 SDGY_3.2.0 的逆向分析：服务端固定、客户端版本号必须取
 * versionName 下划线后的部分，否则会被判成 0001 系统异常。
 */
internal object SdgyConst {
    const val SERVER = "http://222.206.86.42:8081"
    const val SCHOOL_ID = "SDGY"
    const val API = "mobileapi_ydxy"
    const val APP_TYPE = "ydxy"
    const val CLIENT_VERSION = "3.2.0"
    const val VERSION_CODE = "68"

    const val PATH_KEY = "/$API/open/security/asym/key"
    const val PATH_LOGIN = "/$API/open/auth/login"
    const val PATH_LOGOUT = "/$API/open/auth/logout"
    const val PATH_SYSINFO = "/$API/api/globalInfo/getInfo"
    const val PATH_APP_LIST = "/$API/api/app/list"
    const val PATH_TERM_INFO = "/$API/api/schedule/termInfo"
    const val PATH_SCHEDULE_TERM = "/$API/api/schedule/scheduleOfTerm"
    const val PATH_COURSE_SECTIONS = "/$API/api/schedule/courseSections"
    const val PATH_COURSE_DETAIL = "/$API/api/schedule/courseDetail"
    const val PATH_CLASSMATES = "/$API/api/schedule/matchGateMsg"
    const val PATH_TOP_SCORERS = "/$API/api/schedule/highestScoreMsg"
    const val PATH_SPRINGBOARD = "/$API/api/lapp/springboard"
    const val PATH_IMAGE = "/$API/open/image"
}

/** 内置网页取到的免登凭据：请求头 + Cookie 双通道，官方两种都认。 */
class SdgyWebAuth(
    val headers: Map<String, String>,
    val cookies: Map<String, String>,
) {
    /** 拼成 HTTP Cookie 请求头；WebView 要逐条 setCookie，不能整串塞进去。 */
    val cookie: String
        get() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/** 山管服务器返回的业务错误。code 非 0 即失败。 */
class SdgyException(message: String, val code: String = "") : Exception(message) {
    /** 会话被顶号 / 过期时服务端会返回这些码。 */
    val sessionLost: Boolean
        get() = code in SESSION_LOST_CODES

    companion object {
        private val SESSION_LOST_CODES = setOf("1000", "1003", "1006", "0006")
    }
}

/** 登录成功但账号还没在官方 App 里重置密码，服务端只回 updateSecret_url。 */
class SdgyPasswordResetException(val resetUrl: String) :
    Exception("请先在官方山管 App 重置密码")

/**
 * 一次山管登录的完整会话。
 *
 * sk 与 Cookie 是绑定在同一个 HTTP 会话上的：只保留其中一个，后续接口会被判失效，
 * 所以两者必须一起保存、一起恢复。
 */
class SdgySession(
    var sk: String = "",
    var onlyCode: String = UUID.randomUUID().toString(),
    val cookies: MutableMap<String, String> = mutableMapOf(),
    var user: JSONObject = JSONObject(),
    var deviceId: String = "",
    var savedAt: Long = 0L,
) {
    val sessionKey: String
        get() = text("session_key")

    val userId: String
        get() = text("userId")

    val displayName: String
        get() = text("nickname").ifBlank { text("userName") }.ifBlank { userId }

    val hasSession: Boolean
        get() = sk.isNotBlank() && sessionKey.isNotBlank()

    /** Access-Token 请求头的明文（官方 HeadParamsEntity 的同款字段）。 */
    fun identityJson(): String = JSONObject().apply {
        put("userId", text("userId"))
        put("userType", text("userType"))
        put("sessionKey", text("session_key"))
        put("xxdm", text("schoolId").ifBlank { SdgyConst.SCHOOL_ID })
        put("clientVersion", SdgyConst.CLIENT_VERSION)
        put("deviceModel", SdgyClient.deviceModel)
        put("deviceId", text("deviceId").ifBlank { deviceId })
    }.toString()

    /** 服务端把部分字段返回成 JSON null 时，org.json 的 optString 会给出字面量 "null"。 */
    private fun text(key: String): String {
        val value = user.opt(key) ?: return ""
        if (value == JSONObject.NULL) return ""
        return value.toString()
            .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            .orEmpty()
    }

    fun toJson(): JSONObject {
        val cookieJson = JSONObject()
        cookies.forEach { (name, value) -> cookieJson.put(name, value) }
        return JSONObject()
            .put("sk", sk)
            .put("onlyCode", onlyCode)
            .put("cookies", cookieJson)
            .put("user", user)
            .put("deviceId", deviceId)
            .put("savedAt", savedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): SdgySession {
            val cookies = mutableMapOf<String, String>()
            json.optJSONObject("cookies")?.let { obj ->
                obj.keys().forEach { name -> cookies[name] = obj.optString(name) }
            }
            return SdgySession(
                sk = json.optString("sk"),
                onlyCode = json.optString("onlyCode").ifBlank { UUID.randomUUID().toString() },
                cookies = cookies,
                user = json.optJSONObject("user") ?: JSONObject(),
                deviceId = json.optString("deviceId"),
                savedAt = json.optLong("savedAt"),
            )
        }
    }
}