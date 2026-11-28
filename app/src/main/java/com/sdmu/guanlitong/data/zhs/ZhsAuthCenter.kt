package com.sdmu.guanlitong.data.zhs

import android.content.Context
import com.sdmu.guanlitong.data.AccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 知到重新登录协调器（进程内单例）。
 *
 * 任何知到接口抛 [ZhsErrorKind.EXPIRED] 时调用 [requestRelogin]，
 * AppRoot 顶层的 [com.sdmu.guanlitong.ui.zhs.ZhsReloginDialog] 会自动弹出来：
 * 账号已回填，用户完成易盾滑块后自动用记住的密码登录；没有记住密码时补一次密码。
 * 登录成功 [generation] +1，各页面据此自动重拉数据，不需要用户返回重进。
 *
 * 状态与网络无关，账号信息由 [ZhsAuthAccountProvider] 注入，便于纯 JVM 单测。
 */
data class ZhsAuthState(
    /** 是否有待处理的重新登录请求；弹窗据此显示。 */
    val requested: Boolean = false,
    /** 正在提交登录（滑块已完成，请求进行中）。 */
    val busy: Boolean = false,
    /** 登录失败或验证失败的错误。 */
    val error: String? = null,
    /** 账号（未脱敏；界面展示前用 [maskZhsAccount]）。 */
    val username: String = "",
    /** 是否已存密码；false 时弹窗要求用户补一次密码。 */
    val hasStoredPassword: Boolean = false,
)

/** 登录所需的账号信息；[password] 为 null 表示用户没勾「记住密码」。 */
data class ZhsAuthAccount(val username: String, val password: String?)

fun interface ZhsAuthAccountProvider {
    fun load(): ZhsAuthAccount
}

object ZhsAuthCenter {

    private var provider: ZhsAuthAccountProvider = ZhsAuthAccountProvider {
        ZhsAuthAccount("", null)
    }

    private val _state = MutableStateFlow(ZhsAuthState())
    val state: StateFlow<ZhsAuthState> = _state.asStateFlow()

    /** 每次自动重登成功 +1；页面把它的变化当作「重新拉数据」。 */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun bindProvider(value: ZhsAuthAccountProvider) {
        provider = value
    }

    /** 页面捕获到 EXPIRED 时调用；已有请求在等处理时不重复弹窗。 */
    fun requestRelogin() {
        val current = _state.value
        if (current.requested || current.busy) return
        val account = runCatching { provider.load() }.getOrDefault(ZhsAuthAccount("", null))
        _state.value = ZhsAuthState(
            requested = true,
            username = account.username,
            hasStoredPassword = !account.password.isNullOrBlank(),
        )
    }

    /** 滑块通过，开始提交登录请求。 */
    fun begin() {
        if (!_state.value.requested) return
        _state.update { it.copy(busy = true, error = null) }
    }

    /** 登录成功：关闭弹窗并让所有知到页面刷新。 */
    fun complete() {
        _state.value = ZhsAuthState()
        _generation.update { it + 1 }
    }

    /** 登录/验证失败：保留弹窗与账号，显示错误供重试。 */
    fun fail(message: String) {
        if (!_state.value.requested) return
        _state.update { it.copy(busy = false, error = message) }
    }

    /** 用户取消：收起弹窗，页面回到原来「去重新登录」的兜底入口。 */
    fun cancel() {
        _state.value = ZhsAuthState()
    }

    /** 读本机记着的账号与密码（密码只在用户勾过「记住密码」时有值）。 */
    fun rememberedAccount(): ZhsAuthAccount =
        runCatching { provider.load() }.getOrDefault(ZhsAuthAccount("", null))

    /** 读记住的密码；没有就返回 null。 */
    fun storedPassword(): String? = rememberedAccount().password

    /** 取当前账号（弹窗里账号可编辑时的初始值）。 */
    fun currentUsername(): String = _state.value.username

    /** 本机记着的账号名；为空表示连账号都没存过。 */
    fun rememberedUsername(): String = rememberedAccount().username
}

/**
 * 生产环境的账号来源：优先 App 本地账号（AccountStore），账号名缺失时回退到
 * 已保存的知到会话账号；密码只在用户勾过「记住密码」时才有值。
 */
class ZhsStoredAccountProvider(context: Context) : ZhsAuthAccountProvider {

    private val appContext = context.applicationContext

    override fun load(): ZhsAuthAccount {
        val store = AccountStore(appContext)
        val account = store.accountOf(ZhsRepository.SERVICE_ID)
        val username = account.username
            .ifBlank { ZhsRepository(appContext).account.orEmpty() }
            .trim()
        return ZhsAuthAccount(username = username, password = store.passwordOf(ZhsRepository.SERVICE_ID))
    }
}