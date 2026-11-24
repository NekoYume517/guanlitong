package com.sdmu.guanlitong.data

import android.content.Context
import android.content.SharedPreferences
import com.sdmu.guanlitong.widget.refreshDesktopWidgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 账号状态存储。
 *
 * 账号名、昵称、登录状态保存在本地偏好设置中；
 * 需要自动登录的服务（抢课助手）会额外用 Keystore 加密保存密码。
 */
class AccountStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("guanlitong_accounts", Context.MODE_PRIVATE)

    private val _accounts = MutableStateFlow(loadAll())
    val accounts: StateFlow<Map<String, Account>> = _accounts.asStateFlow()

    private fun loadAll(): Map<String, Account> = buildMap {
        ServiceCatalog.services.forEach { service ->
            val username = prefs.getString(usernameKey(service.id), "").orEmpty()
            val nickname = prefs.getString(nicknameKey(service.id), "").orEmpty()
            val loggedIn = prefs.getBoolean(loggedInKey(service.id), false)
            val lastLogin = prefs.getLong(lastLoginKey(service.id), 0L)
            val hasPassword = !prefs.getString(passwordKey(service.id), null).isNullOrBlank()
            if (username.isNotBlank() || loggedIn || hasPassword) {
                put(
                    service.id,
                    Account(
                        serviceId = service.id,
                        username = username,
                        nickname = nickname,
                        loggedIn = loggedIn,
                        lastLoginAt = lastLogin,
                        hasPassword = hasPassword,
                    ),
                )
            }
        }
    }

    private fun refresh() {
        _accounts.value = loadAll()
        refreshDesktopWidgets(appContext)
    }

    fun accountOf(serviceId: String): Account =
        _accounts.value[serviceId] ?: Account(serviceId = serviceId)

    fun rememberPassword(serviceId: String, password: String) {
        Secrets.encrypt(password)?.let { sealed ->
            prefs.edit().putString(passwordKey(serviceId), sealed).apply()
        }
        refresh()
    }

    fun passwordOf(serviceId: String): String? =
        prefs.getString(passwordKey(serviceId), null)?.let(Secrets::decrypt)

    fun markLoggedIn(
        serviceId: String,
        username: String,
        nickname: String = "",
        password: String? = null,
    ) {
        val editor = prefs.edit()
            .putString(usernameKey(serviceId), username)
            .putString(nicknameKey(serviceId), nickname)
            .putBoolean(loggedInKey(serviceId), true)
            .putLong(lastLoginKey(serviceId), System.currentTimeMillis())

        if (password != null) {
            Secrets.encrypt(password)?.let { editor.putString(passwordKey(serviceId), it) }
        }
        editor.apply()
        refresh()
    }

    /** 网页登录成功后标记登录态，保留已有账号名。 */
    fun markWebLoggedIn(serviceId: String) {
        prefs.edit()
            .putBoolean(loggedInKey(serviceId), true)
            .putLong(lastLoginKey(serviceId), System.currentTimeMillis())
            .apply()
        refresh()
    }

    fun forgetPassword(serviceId: String) {
        prefs.edit().remove(passwordKey(serviceId)).apply()
        refresh()
    }

    /** 仅记录账号名（网页登录时用于显示），不改动登录状态。 */
    fun rememberUsername(serviceId: String, username: String) {
        prefs.edit().putString(usernameKey(serviceId), username).apply()
        refresh()
    }

    /** 标记为已退出，保留账号名，可选清除已保存密码。 */
    fun signOut(serviceId: String, clearPassword: Boolean = false) {
        val editor = prefs.edit().putBoolean(loggedInKey(serviceId), false)
        if (clearPassword) editor.remove(passwordKey(serviceId))
        editor.apply()
        refresh()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        refresh()
    }

    private fun usernameKey(id: String) = "acct_${id}_username"
    private fun nicknameKey(id: String) = "acct_${id}_nickname"
    private fun loggedInKey(id: String) = "acct_${id}_logged_in"
    private fun lastLoginKey(id: String) = "acct_${id}_last_login"
    private fun passwordKey(id: String) = "pwd_$id"
}