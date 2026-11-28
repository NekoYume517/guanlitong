package com.sdmu.guanlitong.data.zhs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 智慧树课程列表（共享课 + 翻转课），对照 zhs_server.py 的
 * `exchange_key` / `get_server_time` / `get_share_courses` / `get_courses`。
 *
 * 接口参数都要用服务器下发的 AES 密钥加密：
 * `secretStr=<aesBase64>&date=<毫秒时间戳>`。密钥换一次缓存在会话里；
 * 如果缓存密钥失效（响应结构不对），自动重换一次再试。
 */
object ZhsCourseApi {

    internal const val KEY_EXCHANGE_URL = "https://appcomm-user.zhihuishu.com/app-commserv-user/c/has"
    internal const val COURSE_API =
        "https://hike-teaching-api.zhihuishu.com/gateway/t/v1/student/online/course/getCourseList"
    internal const val SHARE_COURSE_API =
        "https://onlineservice-api.zhihuishu.com/gateway/t/v1/student/course/share/queryShareCourseInfo"
    private const val SERVER_TIME_API =
        "https://onlineservice-api.zhihuishu.com/gateway/t/v1/student/home/index/getServerTime"

    internal const val PAGE_SIZE = 5

    suspend fun fetch(session: ZhsSession): ZhsCourseList = withContext(Dispatchers.IO) {
        if (!session.hasTicket) {
            throw ZhsException("登录已过期，请重新登录", ZhsErrorKind.EXPIRED)
        }
        val http = ZhsHttp(LinkedHashMap(session.cookies))
        var key = session.aesKey.takeIf { it.isNotBlank() } ?: exchangeKey(http)

        var sharedBody: String? = null
        var flippedBody: String? = null

        suspend fun attempt(): Pair<String?, String?> {
            // 服务器时间：接口要求先取一次，失败忽略。
            bestEffort { http.get("$SERVER_TIME_API?secretStr=${q(aes("{}", key))}&date=${now()}") }
            val shared = http.postForm(
                SHARE_COURSE_API,
                mapOf("secretStr" to aes(sharePayload(), key), "date" to now().toString()),
                apiHeaders(http),
            )
            val flipped = http.get(
                "$COURSE_API?secretStr=${q(aes(flippedPayload(), key))}&date=${now()}",
                apiHeaders(http),
            )
            checkAuth(shared.body)
            checkAuth(flipped.body)
            // 被重定向到 passport 登录页说明会话已经失效。
            if (shared.url.contains("passport.zhihuishu.com") ||
                flipped.url.contains("passport.zhihuishu.com")
            ) {
                throw ZhsException("登录已过期，请重新登录", ZhsErrorKind.EXPIRED)
            }
            return shared.body to flipped.body
        }

        var (sharedText, flippedText) = attempt()
        if (!looksLikeShared(sharedText) || !looksLikeFlipped(flippedText)) {
            // 密钥可能已轮换，重新换一次密钥再请求。
            key = exchangeKey(http)
            val retried = attempt()
            sharedText = retried.first
            flippedText = retried.second
        }

        ZhsCourseList(
            shared = ZhsCourseParser.parseShared(sharedText ?: ""),
            flipped = ZhsCourseParser.parseFlipped(flippedText ?: ""),
            session = session.copy(
                cookies = LinkedHashMap(http.cookies),
                aesKey = key,
            ),
        )
    }

    /** `/c/has` → `rt.sl` → 公钥「解密」→ `cKey`。 */
    internal suspend fun exchangeKey(http: ZhsHttp): String = withContext(Dispatchers.IO) {
        val encrypted = ZhsCrypto.rsaEncryptForExchange(13)
        val response = http.get(
            "$KEY_EXCHANGE_URL?uid=${q(encrypted)}",
            mapOf(
                "Accept" to "application/json",
                "Origin" to "https://onlineweb.zhihuishu.com",
                "Referer" to "https://onlineweb.zhihuishu.com/",
                "X-Requested-With" to "XMLHttpRequest",
            ) + auth(http),
        )
        val sl = parseKeySl(response.body)
            ?: throw ZhsException("课程密钥交换失败，请稍后重试", ZhsErrorKind.SERVER)
        ZhsCrypto.rsaDecryptKey(sl)
            ?: throw ZhsException("课程密钥解析失败，请稍后重试", ZhsErrorKind.PARSE)
    }

    internal fun parseKeySl(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("rt")?.optString("sl")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun sharePayload(): String = "{\"status\":0,\"pageNo\":1,\"pageSize\":$PAGE_SIZE}"

    private fun flippedPayload(): String = "{\"pageNum\":1,\"pageSize\":$PAGE_SIZE}"

    internal fun aes(payload: String, key: String): String = ZhsCrypto.aesEncrypt(payload, key)

    private fun apiHeaders(http: ZhsHttp): Map<String, String> =
        mapOf(
            "Accept" to "application/json",
            "Origin" to "https://onlineweb.zhihuishu.com",
            "Referer" to "https://onlineweb.zhihuishu.com/",
            "X-Requested-With" to "XMLHttpRequest",
        ) + auth(http)

    /** `jt-cas` 是后续业务接口的 Authorization 票据，有就带上。 */
    private fun auth(http: ZhsHttp): Map<String, String> =
        http.cookies["jt-cas"].orEmpty().takeIf { it.isNotBlank() }
            ?.let { mapOf("Authorization" to it) }
            ?: emptyMap()

    private fun checkAuth(body: String) {
        if (zhsAuthRequired(body)) {
            throw ZhsException("登录已过期，请重新登录", ZhsErrorKind.EXPIRED)
        }
    }

    private fun looksLikeShared(body: String?): Boolean =
        body != null && runCatching { JSONObject(body).optJSONObject("result") != null }.getOrDefault(false)

    private fun looksLikeFlipped(body: String?): Boolean =
        body != null && runCatching { JSONObject(body).optJSONObject("rt") != null }.getOrDefault(false)

    private fun q(value: String): String = ZhsHttp.urlEncode(value)

    private fun now(): Long = System.currentTimeMillis()

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}