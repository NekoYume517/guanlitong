package com.sdmu.guanlitong.data.zhs

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 智慧树（知到）会话。
 *
 * [cookies] 是 CAS 登录链路上收到的全部 Cookie（按名字存一份，跨子域共用）——
 * 上游接口分布在 passport / onlineweb / onlineservice-api / hike-teaching-api
 * 等多个域名，参考实现也是一次性带上全部 Cookie，这里保持一致。
 *
 * [aesKey] 是从 `/app-commserv-user/c/has` 换回来的课程接口密钥，按登录态缓存，
 * 接口偶发过期时由 [ZhsCourseApi] 重新换取。
 */
data class ZhsSession(
    val account: String,
    val uuid: String = "",
    val cookies: Map<String, String> = emptyMap(),
    /** 课程接口 AES 密钥（`c/has` module 13）。 */
    val aesKey: String = "",
    /** 问答接口 AES 密钥（`c/hasV2` module 10）；旧会话没有这个字段，为空。 */
    val qaKey: String = "",
    /** 学习系统 AES 密钥（`c/has` module 11）；旧会话没有这个字段，为空。 */
    val studyKey: String = "",
    val loggedAt: Long = 0L,
) {
    /** CAS 票据；登录成功的唯一判据。 */
    val hasTicket: Boolean
        get() = cookies["CASTGC"].orEmpty().isNotBlank()
}

/**
 * 课程列表里的一项（共享课 / 翻转课共用）。
 *
 * 上游字段可能缺失：课程名、教师、进度都做了兜底，缺课程标识的条目在解析时丢弃，
 * 因为点进去需要 courseId + recruitId 才能继续。
 */
data class ZhsCourse(
    val courseId: Long,
    val recruitId: Long,
    val name: String,
    val teacher: String,
    val school: String,
    /** 0..100；上游没给或不是数字时为 null（界面显示「待更新」）。 */
    val progress: Int?,
    /** true = 共享课，false = 翻转课。 */
    val shared: Boolean,
    /** 智慧课程（courseType/mapCourse == 7），列表上打个角标。 */
    val smart: Boolean,
    /**
     * 共享课列表里的 `recruitAndCourseId`（课程 secret）。不参与列表展示，
     * 但课程级接口（学习报告、视频列表）都要用它，所以随课程一起保留。
     */
    val secret: String = "",
)

/** 一次课程列表刷新的结果（附带可能被刷新过的 AES 密钥）。 */
data class ZhsCourseList(
    val shared: List<ZhsCourse>,
    val flipped: List<ZhsCourse>,
    val session: ZhsSession,
)

enum class ZhsErrorKind {
    /** 账号密码错、滑块验证没过、CAS 失败。 */
    AUTH,

    /** 会话过期 / 接口 401，需要重新登录。 */
    EXPIRED,

    NETWORK,
    SERVER,
    PARSE,
}

/** 智慧树请求失败。[message] 直接用于界面提示。 */
class ZhsException(
    message: String,
    val kind: ZhsErrorKind = ZhsErrorKind.SERVER,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 登录请求的响应：`status == 1` 才算通过，`msg` 是上游给的中文提示。 */
internal data class ZhsLoginTicket(
    val pwd: String,
    val uuid: String,
)

/** 账号脱敏：13812345678 → 138****5678。只用于界面展示。 */
fun maskZhsAccount(account: String): String {
    val value = account.trim()
    if (value.length <= 4) return "****"
    return value.take(3) + "****" + value.takeLast(4)
}

fun Throwable.zhsMessage(): String = when (this) {
    is ZhsException -> message?.takeIf { it.isNotBlank() } ?: "知到请求失败"
    is UnknownHostException -> "网络不可用，请检查网络后重试"
    is SocketTimeoutException -> "连接超时，请稍后重试"
    is IOException -> "网络错误，请稍后重试"
    else -> message?.takeIf { it.isNotBlank() } ?: "知到请求失败"
}