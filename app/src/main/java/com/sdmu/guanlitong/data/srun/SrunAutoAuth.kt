package com.sdmu.guanlitong.data.srun

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.sdmu.guanlitong.data.AccountStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次 Wi-Fi 切换的自动认证结果；只有结果变化才会提醒。 */
sealed interface SrunAutoResult {
    data object AlreadyOnline : SrunAutoResult
    data class Success(val message: String = "校园网已自动认证") : SrunAutoResult
    data class Failure(val message: String) : SrunAutoResult
}

/** 持久化的事件身份，避免系统重复回调或进程重启后反复发同一条通知。 */
data class SrunAutoState(
    val networkKey: String = "",
    val successfulNetworkKey: String = "",
    val outcomeKey: String = "",
    val message: String = "",
    val attemptedAt: Long = 0L,
)

/**
 * 自动认证与界面展示无关的门槛：没存密码、不是 Wi-Fi、已经在线都不登录；
 * 只有门户明确回答未在线才发登录请求，查询失败不等于需要认证。
 */
internal class SrunAutoAuth(
    private val repository: SrunRepository,
    private val savedCredentials: () -> Pair<String, String>? = {
        val username = repository.savedUsername.trim()
        val password = repository.savedPassword().orEmpty()
        if (username.isBlank() || password.isBlank()) null else username to password
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val settleDelay: suspend () -> Unit = { delay(1_500) },
) {
    fun hasCredentials(): Boolean = savedCredentials() != null

    suspend fun authenticate(networkKey: String, previous: SrunAutoState): Pair<SrunAutoResult?, SrunAutoState> {
        settleDelay()
        val base = previous.copy(networkKey = networkKey, attemptedAt = clock())
        if (previous.successfulNetworkKey == networkKey) return null to base
        val credentials = savedCredentials() ?: return null to base
        val status = repository.onlineStatus()
        if (status.online) return SrunAutoResult.AlreadyOnline to base
        if (status.failed) return null to base
        val outcome = repository.login(credentials.first, credentials.second, rememberPassword = true)
        return when {
            outcome.online -> SrunAutoResult.Success()
            outcome.accepted -> SrunAutoResult.Failure("门户接受了认证，但还没显示在线。请打开校园网认证检查")
            outcome.lastMessage.isNotBlank() -> SrunAutoResult.Failure(outcome.lastMessage)
            else -> SrunAutoResult.Failure(outcome.lastError ?: "自动认证没有完成，请打开校园网认证检查")
        } to base
    }
}

/**
 * 只在系统 Wi-Fi 网络变化时触发一次，没有定时轮询、常驻服务或循环唤醒。
 * Android 的 Network.toString() 是系统分配的网络 ID，不含 Wi-Fi 名称、账号或 IP。
 */
/** 监听门槛是纯逻辑：任何一项不满足都必须停止，不能靠默认开启绕过。 */
internal class SrunAutoListenerGate(
    private val enabled: () -> Boolean,
    private val hasCredentials: () -> Boolean,
) {
    val active: Boolean
        get() = enabled() && hasCredentials()
}

internal class SrunAutoAuthMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val auth: SrunAutoAuth,
    private val store: SrunStore,
    private val onResult: (SrunAutoResult, SrunAutoState) -> Unit,
) : ConnectivityManager.NetworkCallback() {

    constructor(context: Context, scope: CoroutineScope, onResult: (SrunAutoResult, SrunAutoState) -> Unit) : this(
        context = context,
        scope = scope,
        auth = SrunAutoAuth(SrunRepository(context, AccountStore(context))),
        store = SrunStore(context),
        onResult = onResult,
    )

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(ConnectivityManager::class.java)
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val callbackScope = CoroutineScope(scope.coroutineContext + job)
    private var serial = Mutex()
    private var work: Job? = null
    private var lastWifiKey = ""
    private var disposed = false
    private val gate = SrunAutoListenerGate(store.autoEnabled::value, auth::hasCredentials)
    private var registered = false
    private var startedAt = 0L

    /** 开关与凭据都在每次回调时重新确认；关闭后不再等待或访问门户。 */
    private fun canAuthenticate(): Boolean = gate.active

    fun observeSetting() {
        callbackScope.launch {
            store.autoEnabled.collect {
                if (!disposed && canAuthenticate()) start() else stop()
            }
        }
    }

    /** 输入完账号密码后按需启动，不让关闭的开关在回调中自行重启。 */
    fun syncSetting() {
        if (!disposed && canAuthenticate()) start() else stop()
    }

    override fun onAvailable(network: Network) {
        trigger(network)
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
        trigger(network)
    }

    override fun onLost(network: Network) {
        if (lastWifiKey == network.toString()) lastWifiKey = ""
    }

    private fun trigger(network: Network) {
        // 注册后首次回调只是盘点当前网络，不能把它当成一次新的联网失败事件。
        if (System.currentTimeMillis() - startedAt < STARTUP_GRACE_MS) return
        val key = network.toString()
        val capabilities = manager?.getNetworkCapabilities(network) ?: return
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        if (key == lastWifiKey) return
        lastWifiKey = key
        if (!canAuthenticate()) return
        work = callbackScope.launch(Dispatchers.IO) {
            serial.withLock {
                if (!canAuthenticate()) return@withLock
                val (result, state) = auth.authenticate(key, store.autoState())
                ensureActive()
                if (result == null) return@withLock
                val next = state.copy(
                    successfulNetworkKey = if (result !is SrunAutoResult.Failure) key else "",
                    outcomeKey = result::class.java.name,
                    message = (result as? SrunAutoResult.Failure)?.message.orEmpty(),
                )
                val notify = store.autoState().outcomeKey != next.outcomeKey ||
                    store.autoState().networkKey != key || store.autoState().message != next.message
                store.saveAutoState(next)
                if (notify && result !is SrunAutoResult.AlreadyOnline) onResult(result, next)
            }
        }
    }

    fun start() {
        if (registered) return
        startedAt = System.currentTimeMillis()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { manager?.registerNetworkCallback(request, this) }
            .onSuccess { registered = true }
    }

    fun stop() {
        work?.cancel()
        work = null
        // 被取消协程持有的 Mutex 不能复用；重建锁让重新开启后仍能执行。
        serial = Mutex()
        lastWifiKey = ""
        if (registered) runCatching { manager?.unregisterNetworkCallback(this) }
        registered = false
    }

    /** Activity 销毁时结束 Flow 收集；单纯关闭开关只 stop，不影响以后再次开启。 */
    fun dispose() {
        disposed = true
        stop()
        registered = false
        job.cancel()
    }

    private companion object {
        const val STARTUP_GRACE_MS = 3_000L
    }
}
