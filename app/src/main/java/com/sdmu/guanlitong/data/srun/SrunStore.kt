package com.sdmu.guanlitong.data.srun

import android.content.Context
import com.sdmu.guanlitong.data.AccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 门户配置的存取；单测里换成内存实现。 */
interface SrunStorage {
    fun config(): SrunConfig
    fun saveConfig(config: SrunConfig)
}

/** 门户配置落在 SharedPreferences 的 `guanlitong_srun` 里（对应 Go 版的 config.json）。 */
class SrunStore(context: Context) : SrunStorage {

    private val prefs = context.applicationContext
        .getSharedPreferences("guanlitong_srun", Context.MODE_PRIVATE)

    override fun config(): SrunConfig = SrunConfig(
        initUrl = prefs.getString(KEY_URL, null).orEmpty(),
        n = prefs.getString(KEY_N, null).orEmpty(),
        type = prefs.getString(KEY_TYPE, null).orEmpty(),
        acId = prefs.getString(KEY_AC_ID, null).orEmpty(),
        enc = prefs.getString(KEY_ENC, null).orEmpty(),
        ip = prefs.getString(KEY_IP, null).orEmpty(),
    ).normalized()

    private val _autoEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_ENABLED, false))

    /** 默认关闭；保存账号密码也不会擅自开启网络监听。 */
    val autoEnabled: StateFlow<Boolean> = _autoEnabled.asStateFlow()

    fun setAutoEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ENABLED, enabled).commit()
        _autoEnabled.value = enabled
    }

    /** 自动认证事件的状态与手动校园网页面分开，不表示门户在线。 */
    fun autoState(): SrunAutoState = SrunAutoState(
        networkKey = prefs.getString(KEY_AUTO_NETWORK, null).orEmpty(),
        successfulNetworkKey = prefs.getString(KEY_AUTO_SUCCESS, null).orEmpty(),
        outcomeKey = prefs.getString(KEY_AUTO_OUTCOME, null).orEmpty(),
        message = prefs.getString(KEY_AUTO_MESSAGE, null).orEmpty(),
        attemptedAt = prefs.getLong(KEY_AUTO_ATTEMPTED_AT, 0L),
    )

    /** 保存结果事件；通知去重由协调器按网络与结果身份判定。 */
    fun saveAutoState(state: SrunAutoState) {
        prefs.edit()
            .putString(KEY_AUTO_NETWORK, state.networkKey)
            .putString(KEY_AUTO_SUCCESS, state.successfulNetworkKey)
            .putString(KEY_AUTO_OUTCOME, state.outcomeKey)
            .putString(KEY_AUTO_MESSAGE, state.message)
            .putLong(KEY_AUTO_ATTEMPTED_AT, state.attemptedAt)
            .apply()
    }

    override fun saveConfig(config: SrunConfig) {
        prefs.edit()
            .putString(KEY_URL, config.initUrl)
            .putString(KEY_N, config.n)
            .putString(KEY_TYPE, config.type)
            .putString(KEY_AC_ID, config.acId)
            .putString(KEY_ENC, config.enc)
            .putString(KEY_IP, config.ip)
            .apply()
    }

    private companion object {
        const val KEY_URL = "init_url"
        const val KEY_N = "n"
        const val KEY_TYPE = "type"
        const val KEY_AC_ID = "ac_id"
        const val KEY_ENC = "enc"
        const val KEY_IP = "ip"
        const val KEY_AUTO_ENABLED = "auto_enabled"
        const val KEY_AUTO_NETWORK = "auto_network"
        const val KEY_AUTO_SUCCESS = "auto_success"
        const val KEY_AUTO_OUTCOME = "auto_outcome"
        const val KEY_AUTO_MESSAGE = "auto_message"
        const val KEY_AUTO_ATTEMPTED_AT = "auto_attempted_at"
    }
}

/**
 * 校园网账号与密码的保管处。
 *
 * 这里只存账号名和（可选的）密码，不写 AccountStore 的「已登录」标记：
 * 在不在线以门户的实时查询为准，本地标记既不准，也会让首次启动的引导误以为
 * 已经有账号登录了。密码由 [AccountStore] 用 Keystore 加密。
 */
interface SrunAccountStorage {

    val username: String

    fun password(): String?

    /** 账号输入即可保存；记住密码勾选时也立即保存，取消只清密码。 */
    fun save(username: String, password: String, rememberPassword: Boolean)

    fun forgetPassword()
}

class SrunAccountStore(private val accounts: AccountStore) : SrunAccountStorage {

    override val username: String
        get() = accounts.accountOf(SrunRepository.SERVICE_ID).username

    override fun password(): String? =
        accounts.passwordOf(SrunRepository.SERVICE_ID)?.takeIf { it.isNotBlank() }

    override fun save(username: String, password: String, rememberPassword: Boolean) {
        accounts.rememberUsername(SrunRepository.SERVICE_ID, username)
        if (rememberPassword) {
            accounts.rememberPassword(SrunRepository.SERVICE_ID, password)
        } else {
            accounts.forgetPassword(SrunRepository.SERVICE_ID)
        }
    }

    override fun forgetPassword() {
        accounts.forgetPassword(SrunRepository.SERVICE_ID)
    }
}
