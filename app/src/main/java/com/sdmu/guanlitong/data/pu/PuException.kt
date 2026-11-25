package com.sdmu.guanlitong.data.pu

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * PU 层统一异常。
 *
 * [code] 语义：
 * - 401 —— 登录失效（服务端返回 HTTP 200 + body `code:401`，§2.4）；
 * - 502 —— 网络 / 上游接口异常（`Api.java` 的约定）；
 * - 400 —— 参数校验失败（`LoginApi.java` / `ApiProxy.java` 的约定）；
 * - 0   —— 其余（本地签名库不可用、编码失败等）。
 */
class PuException(message: String, val code: Int = 0) : Exception(message)

/**
 * 任意异常 → 给用户看的一句话。
 *
 * `PuException` 的 message 已经是上游整理过的中文文案，直接透出；
 * 原生网络异常换成不暴露堆栈的说明；其余保留 message。
 */
fun Throwable.puMessage(): String = when (this) {
    is PuException -> message?.takeIf { it.isNotBlank() } ?: "第二课堂请求失败"
    is UnknownHostException -> "网络不可用，请检查网络后重试"
    is SocketTimeoutException -> "连接超时，请稍后重试"
    is IOException -> "网络错误，请稍后重试"
    else -> message?.takeIf { it.isNotBlank() } ?: "第二课堂请求失败"
}