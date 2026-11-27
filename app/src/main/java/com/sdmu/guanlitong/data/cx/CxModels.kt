package com.sdmu.guanlitong.data.cx

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 学习通会话。
 *
 * 只保留登录后拿到的 Cookie 与展示用的手机号；密码只在一次登录请求里用过，
 * 不进入这个对象、不落盘（与 PU 同一条约束）。
 */
data class CxSession(
    val phone: String,
    val cookies: Map<String, String>,
    val uid: String = "",
    val fid: String = "",
)

/** 「我的课程」列表项，对应课程列表页里的一个 `li.course`。 */
data class CxCourse(
    val courseId: String,
    val clazzId: String,
    val personId: String,
    val name: String,
    val teacher: String,
    val coverUrl: String,
    val link: String,
)

/**
 * 学习通层统一异常。
 *
 * [code] 语义沿用项目其他服务的约定：
 * - 400 —— 参数校验失败（手机号 / 密码为空）；
 * - 401 —— 登录失效（Cookie 被清空或页面跳到登录页）；
 * - 403 —— 学习通要求验证或拦截了请求；
 * - 502 —— 上游接口返回了非 200 / 解析失败；
 * - 503 —— 网络层重试耗尽（超时、DNS、连接重置）；扫描时按「该章未取到」处理；
 * - 0   —— 其余。
 */
class CxException(message: String, val code: Int = 0) : Exception(message)

/** [CxException.code]：网络层重试耗尽。区别于 502 的业务失败，调用方可降级为「部分失败」。 */
const val CX_CODE_NETWORK = 503

/** [CxException.code]：登录失效。Repository 的单飞重登已失败一次，调用方必须上抛。 */
const val CX_CODE_AUTH = 401

/** [CxException.code]：学习通要求验证/拦截（202 与验证码页都归一到这个码）。 */
const val CX_CODE_RISK = 403

/** 任意异常 → 给用户看的一句话，不暴露堆栈。 */
fun Throwable.cxMessage(): String = when (this) {
    is CxException -> message?.takeIf { it.isNotBlank() } ?: "学习通请求失败"
    is UnknownHostException -> "网络不可用，请检查网络后重试"
    is SocketTimeoutException -> "连接超时，请稍后重试"
    is IOException -> "网络错误，请稍后重试"
    else -> message?.takeIf { it.isNotBlank() } ?: "学习通请求失败"
}

/** 手机号脱敏：13812345678 → 138****5678。只用于界面展示。 */
fun maskPhone(phone: String): String {
    val value = phone.trim()
    if (value.length <= 4) return "****"
    return value.take(3) + "****" + value.takeLast(4)
}