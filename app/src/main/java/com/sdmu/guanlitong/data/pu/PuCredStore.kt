package com.sdmu.guanlitong.data.pu

import android.content.Context
import com.sdmu.guanlitong.data.Secrets

/**
 * PU 凭据存储（§6.4 第 5 条：不落明文密码）。
 *
 * - `token` 用 [Secrets]（Android Keystore AES/GCM）加密后存 SharedPreferences；
 * - `goId` / 姓名 / 学号是明文（不是密钥材料），与上游 `Cred.java` 一致；
 * - 密码根本不会被保存——登录成功后只在内存里用一次；
 * - 构造时把 [PuRuntime] 绑到当前进程，并把已保存的凭据装进内存里的 [PuApi]。
 *
 * 项目没有 Application 类，所以「进程级初始化」由每个 PU 页面调用
 * `rememberPuCredStore()` 时顺带完成（见 ui/pu/PuCommon.kt）。
 */
class PuCredStore(context: Context) {

    private val app: Context = context.applicationContext
    private val prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private var cachedToken: String? = loadToken()
    private var cachedGoId: String? = prefs.getString(KEY_GO_ID, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    init {
        PuRuntime.attach(app)
        refreshRuntime()
    }

    /** 32 位十六进制登录 token；未登录为 null。 */
    var token: String?
        get() = cachedToken
        set(value) {
            cachedToken = value?.trim()?.takeIf { it.isNotEmpty() }
            persistToken()
            refreshRuntime()
        }

    /** 学校 go_id，随登录返回的 `sid` 一起保存。 */
    var goId: String?
        get() = cachedGoId
        set(value) {
            cachedGoId = value?.trim()?.takeIf { it.isNotEmpty() }
            val editor = prefs.edit()
            val v = cachedGoId
            if (v == null) editor.remove(KEY_GO_ID) else editor.putString(KEY_GO_ID, v)
            editor.apply()
            refreshRuntime()
        }

    val loggedIn: Boolean
        get() = !cachedToken.isNullOrBlank() && !cachedGoId.isNullOrBlank()

    /** 登录成功后的真实姓名，登录页回显用。 */
    val name: String?
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    /** 学号（上游 `baseUserInfo.username`），登录页回显用。 */
    val account: String?
        get() = prefs.getString(KEY_ACCOUNT, null)?.takeIf { it.isNotBlank() }

    fun save(session: PuSession) {
        val token = session.token.trim()
        val goId = session.goId.trim().ifBlank { PU_SCHOOL_GO_ID.toString() }
        cachedToken = token
        cachedGoId = goId

        val editor = prefs.edit()
        val sealed = runCatching { Secrets.encrypt(token) }.getOrNull()
        if (sealed == null) {
            // Keystore 不可用：token 只留在内存里，进程重启后需要重新登录，
            // 绝不退化成明文落盘。
            editor.remove(KEY_TOKEN)
        } else {
            editor.putString(KEY_TOKEN, sealed)
        }
        editor.putString(KEY_GO_ID, goId)
            .putString(KEY_NAME, session.name)
            .putString(KEY_ACCOUNT, session.account)
            .apply()

        refreshRuntime()
    }

    /** 清空本地凭据（登录失效 / 清除所有数据时调用）。 */
    fun clear() {
        cachedToken = null
        cachedGoId = null
        prefs.edit().clear().apply()
        PuRuntime.unbind()
    }

    private fun loadToken(): String? {
        val sealed = prefs.getString(KEY_TOKEN, null) ?: return null
        val plain = runCatching { Secrets.decrypt(sealed) }.getOrNull()
        if (plain.isNullOrBlank()) {
            prefs.edit().remove(KEY_TOKEN).apply()
            return null
        }
        return plain
    }

    private fun persistToken() {
        val t = cachedToken
        val editor = prefs.edit()
        if (t == null) {
            editor.remove(KEY_TOKEN)
        } else {
            val sealed = runCatching { Secrets.encrypt(t) }.getOrNull()
            if (sealed == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, sealed)
        }
        editor.apply()
    }

    private fun refreshRuntime() {
        val t = cachedToken
        val g = cachedGoId
        if (t.isNullOrBlank() || g.isNullOrBlank()) {
            PuRuntime.unbind()
        } else {
            PuRuntime.bind(t, g)
        }
    }

    private companion object {
        const val PREF_NAME = "guanlitong_pu"
        const val KEY_TOKEN = "pu_token"
        const val KEY_GO_ID = "pu_go_id"
        const val KEY_NAME = "pu_name"
        const val KEY_ACCOUNT = "pu_account"
    }
}