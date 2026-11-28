package com.sdmu.guanlitong.data.srun

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/** 本校深澜（srun）门户，与 Go 版 `Config.base()` 的兜底地址一致。 */
const val SRUN_DEFAULT_PORTAL = "https://wlrz.sdmu.edu.cn"

/**
 * Go 版 `userAgent` 原样保留。
 * 认证参数里的 os/name 报的是 windows+10 / windows，跟它配套，不做「美化」。
 */
const val SRUN_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/63.0.3239.26 Safari/537.36"

/** 联网探测地址（Go 版 `ProbeURL` 的默认值）：返回 204 才算真的能上网。 */
const val SRUN_PROBE_URL = "http://connect.rom.miui.com/generate_204"

/** 门户接口路径，与 Go 版各函数里写死的路径一致。 */
internal object SrunPaths {
    /** 门户首页：还没拿到本机 IP 时从这里取 `user_ip`。 */
    const val PAGE = "/"
    const val CHALLENGE = "/cgi-bin/get_challenge"
    const val PORTAL = "/cgi-bin/srun_portal"
    const val RAD_USER_INFO = "/cgi-bin/rad_user_info"
}

/**
 * 门户配置，对应 Go 版的 `config.json`；默认值与它的 `loadConfig` 一致。
 *
 * [ip] 是手动指定的「门户侧看到的本机 IP」（Go 的 `-i`），平时留空，
 * 由首页的 `user_ip` 和 `get_challenge` 的 `client_ip` 自动填。
 * 自动取到的不落盘：换一个 Wi-Fi 它就变了。
 */
data class SrunConfig(
    val initUrl: String = SRUN_DEFAULT_PORTAL,
    val n: String = "200",
    val type: String = "1",
    val acId: String = "1",
    val enc: String = SrunCrypto.ENC_VERSION,
    val ip: String = "",
) {

    /** Go 版 `loadConfig` 的空值兜底加上 `Config.base()`。 */
    fun normalized(): SrunConfig {
        var base = initUrl.trim().removeSuffix("/")
        if (base.isEmpty()) base = SRUN_DEFAULT_PORTAL
        if (!base.startsWith("http")) base = "https://$base"
        return SrunConfig(
            initUrl = base,
            n = n.ifBlank { "200" },
            type = type.ifBlank { "1" },
            acId = acId.ifBlank { "1" },
            enc = enc.ifBlank { SrunCrypto.ENC_VERSION },
            ip = ip.trim(),
        )
    }

    /** 门户域名，只用于界面展示。 */
    val host: String
        get() = runCatching { URI(initUrl).host }.getOrNull().orEmpty().ifBlank { initUrl }
}

/** 门户说了什么。 */
enum class SrunErrorKind {
    /** 连不上门户（两个 scheme 都失败）。 */
    NETWORK,

    /** 门户回了个认不出的东西（被劫持成了 HTML 页等）。 */
    PARSE,
}

class SrunException(
    message: String,
    val kind: SrunErrorKind,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * 给人看的失败原因。
 *
 * 请求的 URL 里带着密码的 MD5 和加密后的 info，所以底层异常的原话只要像一条 URL
 * （带 `?` 或 `://`）就不回显，只给通用提示。
 */
fun Throwable?.srunMessage(): String = when (this) {
    null -> "网络错误，请稍后重试"
    is SrunException -> message?.takeIf { it.isNotBlank() } ?: "校园网认证失败"
    is UnknownHostException -> "解析不了门户域名，请确认已连接校园网"
    is SocketTimeoutException -> "连接门户超时"
    is ConnectException -> "连不上门户，请确认已连接校园网"
    is SSLException -> "与门户建立安全连接失败"
    is IOException -> safeDetail() ?: "网络错误，请稍后重试"
    else -> safeDetail() ?: "校园网认证失败"
}

private fun Throwable.safeDetail(): String? =
    message?.takeIf { it.isNotBlank() && '?' !in it && "://" !in it }

/**
 * 门户 `error` 字段的中文说明。
 *
 * `ip_already_online_error`、`auth_info_error`、`not_online` 在 Go 版源码里出现过；
 * `login_error`、`usernum_limit_error` 是深澜门户的常见返回，没有在本校门户上逐个核对。
 * 表里没有的一律走 [describe] 的兜底，并把门户自己的 `error_msg` 原样带出来。
 */
object SrunErrors {

    private val messages = mapOf(
        "login_error" to "账号或密码不对",
        "ip_already_online_error" to "门户说这台设备已经在线；如果其实上不了网，先「注销」再登录",
        "usernum_limit_error" to "这个账号在线的设备数已达上限",
        "auth_info_error" to "门户没有认下这次的认证信息",
    )

    fun describe(code: String, portalMessage: String = ""): String {
        val name = code.trim()
        val extra = portalMessage.trim()
        val known = messages[name]
        return when {
            extra.isEmpty() && known != null -> known
            known != null -> "$known（门户返回：$extra）"
            extra.isNotEmpty() -> "门户拒绝了请求：$extra"
            name.isNotEmpty() -> "门户拒绝了请求（$name）"
            else -> "门户没有给出明确结果"
        }
    }
}

/** 一次 `srun_portal?action=login` 的解析结果。 */
data class SrunLoginResult(
    /** 门户接受了这次登录（`res` 为 ok/success，或带 access_token 且没有报错）。 */
    val accepted: Boolean,
    val error: String = "",
    val errorMsg: String = "",
    val sucMsg: String = "",
    /** Go 版打印的那一行：`res=… error=… error_msg=… suc_msg=…`。 */
    val summary: String = "",
) {

    val message: String
        get() = if (accepted) {
            sucMsg.ifBlank { "登录成功" }
        } else {
            SrunErrors.describe(error, errorMsg)
        }
}

/** 门户 `rad_user_info` 报告的在线状态。 */
data class SrunOnlineStatus(
    val online: Boolean,
    val userName: String = "",
    val onlineIp: String = "",
    val deviceTotal: String = "",
    /** 上线时间，Unix 秒。 */
    val addTime: String = "",
    /** 本次在线累计流量，字节。 */
    val allBytes: String = "",
    /** 门户给的原始错误码；查询失败时是失败原因。 */
    val detail: String = "",
    /** 请求或解析失败：这时的 [online] 只是「不知道」，不能当成「未在线」。 */
    val failed: Boolean = false,
)

/**
 * 一次完整的登录尝试（对应 Go 版 login 模式的 `RESULT:` 那一行）。
 * [accepted] 表示门户至少接受过一次，[online] 是最后问门户的结果，两者都是判定依据。
 */
data class SrunLoginOutcome(
    val accepted: Boolean,
    val online: Boolean,
    val attempts: Int,
    /** 最后一次门户应答的那一行（Go 的 `last`）；最后一次没拿到应答时是空串。 */
    val lastSummary: String,
    /** 最后一次门户拒绝的中文原因；最后一次没拿到应答时是空串。 */
    val lastMessage: String,
    /** 最后一次是异常时的原因；最后一次拿到了应答时为 null。 */
    val lastError: String?,
    val status: SrunOnlineStatus?,
)

/** 注销的结果：怎么跟用户说、算不算成功，以及随后门户还认不认这台设备在线。 */
data class SrunLogoutResult(
    val message: String,
    /** 请求发出去了，而且门户现在不认为这台设备在线。 */
    val ok: Boolean,
    val status: SrunOnlineStatus,
)

/** 界面用的当前状态：门户的记录，加上一次真实的联网探测。 */
data class SrunStatus(
    val portal: SrunOnlineStatus,
    /** HTTP 204 探测：true 能上网，false 不通，null 表示没有探测。 */
    val reachable: Boolean? = null,
)

/** 流量字节数 → 「1.2 GB」；认不出就原样返回。 */
fun formatSrunBytes(raw: String): String {
    val text = raw.trim()
    val bytes = text.toLongOrNull() ?: return text
    if (bytes < 0) return text
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
