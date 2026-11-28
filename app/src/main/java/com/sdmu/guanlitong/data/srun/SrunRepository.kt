package com.sdmu.guanlitong.data.srun

import android.content.Context
import com.sdmu.guanlitong.data.AccountStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 校园网认证的数据入口，对应 Go 版 login 模式的 `default` 分支。
 *
 * 与 [com.sdmu.guanlitong.data.zhs.ZhsRepository] 同样的注入方式：
 * 门户、联网探测、时钟和等待都能替换，单测完全不碰网络。
 *
 * 一轮登录的节奏照搬 Go 版：最多 4 次 `login`，失败后分别等 4 / 6 / 8 秒再试；
 * 结束后等 1 秒问门户在不在线，门户接受过但还没在线的再等 3 秒问最后一次。
 * 账号密码只在门户接受过登录之后才交给 [SrunAccountStorage]。
 */
class SrunRepository(
    private val store: SrunStorage,
    private val accounts: SrunAccountStorage? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    private val portalFactory: (SrunConfig) -> SrunPortalApi = { config ->
        SrunPortal(config, clock = clock)
    },
    private val probeCall: suspend () -> Boolean = { SrunProbe.isReachable() },
) {

    constructor(context: Context, accounts: AccountStore? = null) : this(
        store = SrunStore(context.applicationContext),
        accounts = accounts?.let(::SrunAccountStore),
    )

    /** 这次会话里门户识别到的本机 IP。不落盘：换一个 Wi-Fi 它就变了。 */
    private var learnedIp: String = ""

    val savedUsername: String
        get() = accounts?.username.orEmpty()

    fun savedPassword(): String? = accounts?.password()?.takeIf { it.isNotBlank() }

    /**
     * 登录一轮。[onAttempt] 在每次发出登录请求前回调，用来给界面报进度。
     * 两次尝试之间可以取消；已经发出去的请求会跑完并把结果记下来。
     */
    suspend fun login(
        username: String,
        password: String,
        rememberPassword: Boolean,
        onAttempt: (attempt: Int, total: Int) -> Unit = { _, _ -> },
    ): SrunLoginOutcome {
        val (config, portal) = openPortal()
        // Go 的 initGetip：拿不到首页不中断，后面由 get_challenge 的 client_ip 补上。
        attempt { portal.initIp() }
        keepLearnedIp(config, portal)

        var accepted = false
        var lastSummary = ""
        var lastMessage = ""
        var lastError: String? = null
        var attempts = 0
        for (attempt in 1..LOGIN_ATTEMPTS) {
            if (attempt > 1) delayMs((2 + (attempt - 1) * 2) * 1000L)
            onAttempt(attempt, LOGIN_ATTEMPTS)
            attempts = attempt
            // 请求一旦发出就让它跑完：用户中途离开时门户可能已经接受了登录，结果不能丢。
            val result = withContext(NonCancellable) {
                attempt { portal.login(username, password) }.also {
                    keepLearnedIp(config, portal)
                    if (it.getOrNull()?.accepted == true) {
                        accounts?.save(username, password, rememberPassword)
                    }
                }
            }
            val parsed = result.getOrNull()
            if (parsed == null) {
                lastSummary = ""
                lastMessage = ""
                lastError = result.exceptionOrNull().srunMessage()
            } else {
                lastSummary = parsed.summary
                lastMessage = parsed.message
                lastError = null
                if (parsed.accepted) {
                    accepted = true
                    break
                }
            }
        }

        return withContext(NonCancellable) {
            delayMs(1_000)
            var status = portal.checkOnline()
            if (!status.online && accepted) {
                delayMs(3_000)
                status = portal.checkOnline()
            }
            keepLearnedIp(config, portal)
            SrunLoginOutcome(
                accepted = accepted,
                online = status.online,
                attempts = attempts,
                lastSummary = lastSummary,
                lastMessage = lastMessage,
                lastError = lastError,
                status = status,
            )
        }
    }

    /** 自动认证只问门户，不额外请求公网探测；失败保持「不知道」，不据此注销或登录。 */
    suspend fun onlineStatus(): SrunOnlineStatus {
        val (config, portal) = openPortal()
        attempt { portal.initIp() }
        return portal.checkOnline().also { keepLearnedIp(config, portal) }
    }

    /** 门户的在线记录，外加一次真实的联网探测（探测本身失败只意味着没有结论）。 */
    suspend fun status(): SrunStatus {
        val (config, portal) = openPortal()
        attempt { portal.initIp() }
        val status = portal.checkOnline()
        keepLearnedIp(config, portal)
        val reachable = attempt { probeCall() }.getOrNull()
        return SrunStatus(portal = status, reachable = reachable)
    }

    /** 发注销请求，再问一次门户：以门户现在的记录为准，而不是只看它嘴上答应。 */
    suspend fun logout(username: String): SrunLogoutResult {
        val (config, portal) = openPortal()
        attempt { portal.initIp() }
        val response = attempt { portal.logout(username) }
        val acknowledged = response.getOrNull()
            ?.let { body -> attempt { SrunParser.parseLogout(body) }.getOrNull() }
        val status = portal.checkOnline()
        keepLearnedIp(config, portal)
        val message = when {
            response.isFailure -> "注销请求没发出去（${response.exceptionOrNull().srunMessage()}）"
            status.failed -> "注销请求已发出，但查不到现在的在线状态（${status.detail}）"
            !status.online -> "已注销"
            acknowledged == true -> "门户受理了注销，但还显示这台设备在线"
            else -> "门户没有受理注销，这台设备仍显示在线"
        }
        return SrunLogoutResult(
            message = message,
            ok = response.isSuccess && !status.failed && !status.online,
            status = status,
        )
    }

    fun forgetPassword() {
        accounts?.forgetPassword()
    }

    /** 门户域名，给界面展示用。 */
    val portalHost: String
        get() = store.config().host

    /** 配置里手填的 IP 优先，否则沿用这次会话里门户给过的。 */
    private fun openPortal(): Pair<SrunConfig, SrunPortalApi> {
        val config = store.config()
        return config to portalFactory(config.copy(ip = config.ip.ifBlank { learnedIp }))
    }

    private fun keepLearnedIp(config: SrunConfig, portal: SrunPortalApi) {
        if (config.ip.isBlank()) learnedIp = portal.ip
    }

    /** 协程被取消要原样抛出，其余异常收进 [Result]。 */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    companion object {
        const val SERVICE_ID = "srun"

        /** Go 版 login 模式的固定次数。 */
        private const val LOGIN_ATTEMPTS = 4
    }
}
