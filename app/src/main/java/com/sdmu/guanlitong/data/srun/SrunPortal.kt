package com.sdmu.guanlitong.data.srun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 门户接口，对照 Go 版 `client` 的四个动作：
 * `initGetip`、`getToken` + `login`、`checkOnline`、`logout`。
 * [SrunRepository] 只通过这个接口碰门户，单测里可以整个换成假的。
 */
interface SrunPortalApi {

    /** 门户侧看到的本机 IP；登录、查询、注销都带着它。 */
    val ip: String

    /** 打开门户首页取 `user_ip`；已经有 IP 时什么都不做，拿不到页面也不报错。 */
    suspend fun initIp()

    /** 走一遍 `get_challenge` + `srun_portal?action=login`；连不上或回包认不出才抛。 */
    suspend fun login(username: String, password: String): SrunLoginResult

    /** 门户的在线记录。查询失败不抛，返回 `failed = true` 的状态。 */
    suspend fun checkOnline(): SrunOnlineStatus

    /** 发注销请求，返回门户的原始响应；连不上才抛。 */
    suspend fun logout(username: String): String
}

/**
 * 深澜门户客户端。
 *
 * 请求参数逐项对照 Go 版：
 * - `get_challenge` 只发 callback / username / ip / `_`；
 * - `srun_portal` 严格 14 个参数，不多不少（尤其不带空的 mac / nas_ip）；
 * - 先按配置的 scheme 请求，失败再换另一个 scheme（`getBoth`）——没认证时
 *   https 常被打断，但门户的 http 端口往往还通。
 *
 * [ip] 是这个实例的可变状态：首页的 `user_ip` 先填上，`get_challenge` 的
 * `client_ip` 再覆盖（本机在 NAT 后面时只有后者是对的）。
 */
internal class SrunPortal(
    config: SrunConfig,
    private val transport: SrunTransport = HttpSrunTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SrunPortalApi {

    private val settings = config.normalized()

    /** 已去掉末尾斜杠的门户地址。 */
    private val base = settings.initUrl

    override var ip: String = settings.ip
        private set

    override suspend fun initIp() {
        withContext(Dispatchers.IO) {
            if (ip.isNotBlank()) return@withContext
            val body = getOrNull(SrunPaths.PAGE, "") ?: return@withContext
            SrunParser.parseUserId(body)?.let { ip = it }
        }
    }

    override suspend fun login(username: String, password: String): SrunLoginResult =
        withContext(Dispatchers.IO) {
            val token = challenge(username)
            val info = SrunCrypto.encodeInfo(username, password, ip, settings.acId, token, settings.enc)
            val hmd5 = SrunCrypto.md5Hex(password)
            val chksum = SrunCrypto.chksum(
                token, username, hmd5, settings.acId, ip, settings.n, settings.type, info,
            )
            val body = getBoth(
                SrunPaths.PORTAL,
                SrunCrypto.encodeQuery(
                    listOf(
                        "callback" to callback(),
                        "action" to "login",
                        "username" to username,
                        "password" to "{MD5}$hmd5",
                        "ac_id" to settings.acId,
                        "ip" to ip,
                        "chksum" to chksum,
                        "info" to info,
                        "n" to settings.n,
                        "type" to settings.type,
                        "os" to SrunCrypto.DEFAULT_OS,
                        "name" to SrunCrypto.DEFAULT_NAME,
                        "double_stack" to "0",
                        "_" to clock().toString(),
                    ),
                ),
            )
            SrunParser.parseLogin(body)
        }

    override suspend fun checkOnline(): SrunOnlineStatus = withContext(Dispatchers.IO) {
        val query = SrunCrypto.encodeQuery(
            listOf("callback" to callback(), "ip" to ip, "_" to clock().toString()),
        )
        val body = try {
            getBoth(SrunPaths.RAD_USER_INFO, query)
        } catch (e: SrunException) {
            return@withContext SrunOnlineStatus(
                online = false,
                detail = "查询失败：${e.srunMessage()}",
                failed = true,
            )
        }
        try {
            SrunParser.parseOnline(body)
        } catch (e: SrunException) {
            SrunOnlineStatus(
                online = false,
                detail = "解析失败：${SrunParser.snippet(body)}",
                failed = true,
            )
        }
    }

    override suspend fun logout(username: String): String = withContext(Dispatchers.IO) {
        getBoth(
            SrunPaths.PORTAL,
            SrunCrypto.encodeQuery(
                listOf(
                    "callback" to callback(),
                    "action" to "logout",
                    "username" to username,
                    "ip" to ip,
                    "ac_id" to settings.acId,
                ),
            ),
        )
    }

    /** 取 challenge，顺便用门户识别到的 IP 覆盖 [ip]。 */
    private fun challenge(username: String): String {
        val body = getBoth(
            SrunPaths.CHALLENGE,
            SrunCrypto.encodeQuery(
                listOf(
                    "callback" to callback(),
                    "username" to username,
                    "ip" to ip,
                    "_" to clock().toString(),
                ),
            ),
        )
        val json = SrunParser.parseJsonp(body)
        val token = json.stringOrNull("challenge")
        if (token.isEmpty()) {
            throw SrunException(
                "门户没有下发 challenge（原始：${SrunParser.snippet(body)}）",
                SrunErrorKind.PARSE,
            )
        }
        // 门户识别的真实 IP 优先（本机在 NAT 后面时必需）。
        val clientIp = json.stringOrNull("client_ip")
        val onlineIp = json.stringOrNull("online_ip")
        when {
            clientIp.isNotEmpty() -> ip = clientIp
            onlineIp.isNotEmpty() -> ip = onlineIp
        }
        return token
    }

    private fun callback(): String = SrunCrypto.jsonpCallback(clock())

    /** 首页取不到就算了（Go 版只打印一行）；两个 scheme 都失败才返回 null。 */
    private fun getOrNull(path: String, rawQuery: String): String? =
        runCatching { getBoth(path, rawQuery) }.getOrNull()

    private fun attempt(url: String): Result<String> = runCatching { transport.get(url) }

    /**
     * 先按配置的 scheme 请求，失败才换另一个 scheme；都失败就把两个原因一起报出来。
     * 成功的那次不会多发请求。
     */
    private fun getBoth(path: String, rawQuery: String): String {
        val target = if (rawQuery.isEmpty()) path else "$path?$rawQuery"
        val httpsFirst = base.startsWith("https://")
        val primary = attempt(base + target)
        primary.getOrNull()?.let { return it }
        val fallback = attempt(flipScheme(base) + target)
        fallback.getOrNull()?.let { return it }
        throw SrunException(
            "${schemeName(httpsFirst)}：${primary.exceptionOrNull().srunMessage()}；" +
                "${schemeName(!httpsFirst)}：${fallback.exceptionOrNull().srunMessage()}",
            SrunErrorKind.NETWORK,
            primary.exceptionOrNull(),
        )
    }

    private fun flipScheme(url: String): String =
        if (url.startsWith("https://")) "http://" + url.removePrefix("https://")
        else "https://" + url.removePrefix("http://")

    private fun schemeName(https: Boolean): String = if (https) "https" else "http"
}
