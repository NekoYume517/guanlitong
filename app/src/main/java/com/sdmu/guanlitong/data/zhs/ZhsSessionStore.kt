package com.sdmu.guanlitong.data.zhs

import android.content.Context
import android.content.SharedPreferences
import com.sdmu.guanlitong.data.Secrets
import org.json.JSONObject

/** 会话存储抽象：真实实现是 [ZhsSessionStore]，单测用内存假实现。 */
interface ZhsSessionStorage {
    val session: ZhsSession?

    fun save(session: ZhsSession)

    fun clear()

    val loggedIn: Boolean
        get() = session?.let { it.account.isNotBlank() && it.hasTicket } == true

    val account: String?
        get() = session?.account?.takeIf { it.isNotBlank() }
}

/**
 * 知到（智慧树）会话存储。
 *
 * - 会话（账号 + 全部 Cookie + CAS 票据 + 课程 AES 密钥）整体用 [Secrets]
 *   （Android Keystore AES/GCM）加密后存 SharedPreferences；
 * - 密码不进入这里，只有用户勾了「记住密码」才由 AccountStore 单独保管；
 * - Keystore 不可用时会话只留在内存里，重启后需要重新登录；
 * - 请求失败从不自动清会话，只有用户点「退出登录」才会删。
 */
class ZhsSessionStore(context: Context) : ZhsSessionStorage {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: ZhsSession? = load()

    override val session: ZhsSession? get() = cached

    override fun save(session: ZhsSession) {
        cached = session
        val plain = runCatching { encode(session) }.getOrNull() ?: return
        val sealed = Secrets.encrypt(plain) ?: run {
            prefs.edit().remove(KEY_SESSION).apply()
            return
        }
        prefs.edit().putString(KEY_SESSION, sealed).apply()
    }

    override fun clear() {
        cached = null
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun load(): ZhsSession? {
        val sealed = prefs.getString(KEY_SESSION, null) ?: return null
        val plain = runCatching { Secrets.decrypt(sealed) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(plain) }.getOrNull() ?: return null
        val cookiesJson = json.optJSONObject("cookies") ?: return null

        val cookies = LinkedHashMap<String, String>()
        cookiesJson.keys().forEach { key ->
            val value = cookiesJson.optString(key)
            if (value.isNotBlank() && !value.equals("null", ignoreCase = true)) {
                cookies[key] = value
            }
        }
        if (cookies.isEmpty()) return null

        return ZhsSession(
            account = clean(json.optString("account")),
            uuid = clean(json.optString("uuid")),
            cookies = cookies,
            aesKey = clean(json.optString("aesKey")),
            qaKey = clean(json.optString("qaKey")),
            studyKey = clean(json.optString("studyKey")),
            loggedAt = json.optLong("loggedAt"),
        )
    }

    private fun encode(session: ZhsSession): String {
        val cookies = JSONObject()
        session.cookies.forEach { (key, value) -> cookies.put(key, value) }
        return JSONObject()
            .put("account", session.account)
            .put("uuid", session.uuid)
            .put("aesKey", session.aesKey)
            .put("qaKey", session.qaKey)
            .put("studyKey", session.studyKey)
            .put("loggedAt", session.loggedAt)
            .put("cookies", cookies)
            .toString()
    }

    private fun clean(raw: String): String =
        raw.trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()

    companion object {
        const val PREF_NAME = "guanlitong_zhs"
        const val KEY_SESSION = "zhs_session"

        @Volatile
        private var processStore: ZhsSessionStore? = null

        /**
         * 进程内共享的会话存储。
         *
         * 会话缓存在实例内存里，界面各自 `remember { ZhsRepository(...) }` 时如果各自新建
         * 一份存储，别的实例重新登录后这些页面的 `loggedIn` 与网络请求都还是旧会话，
         * 会一直 401 且反复弹重登框。共享一份后，登录/退出立刻对所有页面可见。
         */
        fun instance(context: Context): ZhsSessionStore =
            processStore ?: synchronized(this) {
                processStore ?: ZhsSessionStore(context.applicationContext).also {
                    processStore = it
                }
            }
    }
}