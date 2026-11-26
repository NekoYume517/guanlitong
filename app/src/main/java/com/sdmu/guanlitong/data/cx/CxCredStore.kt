package com.sdmu.guanlitong.data.cx

import android.content.Context
import android.content.SharedPreferences
import com.sdmu.guanlitong.data.Secrets
import org.json.JSONObject

/**
 * 学习通会话存储。
 *
 * - 会话（手机号 + 全部 Cookie + uid/fid）整体用 [Secrets]（Android Keystore AES/GCM）
 *   加密后存 SharedPreferences；
 * - 密码不进入这里——登录成功后只保留会话；
 * - Keystore 不可用时本次会话只留在内存里，重启后需要重新登录。
 *
 * 注意：任何请求失败都不会调用 [clear]；只有用户在账号页点「退出登录」
 * 或「退出全部账号」才会真正清会话。
 */
class CxCredStore(context: Context) : CxSessionStore {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: CxSession? = load()

    override val session: CxSession? get() = cached

    override val phone: String? get() = cached?.phone?.takeIf { it.isNotBlank() }

    val loggedIn: Boolean
        get() = cached?.let { it.phone.isNotBlank() && it.cookies.isNotEmpty() } == true

    override fun save(session: CxSession) {
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

    private fun load(): CxSession? {
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

        return CxSession(
            phone = clean(json.optString("phone")),
            cookies = cookies,
            uid = clean(json.optString("uid")),
            fid = clean(json.optString("fid")),
        )
    }

    private fun encode(session: CxSession): String {
        val cookies = JSONObject()
        session.cookies.forEach { (key, value) -> cookies.put(key, value) }
        return JSONObject()
            .put("phone", session.phone)
            .put("uid", session.uid)
            .put("fid", session.fid)
            .put("cookies", cookies)
            .toString()
    }

    private fun clean(raw: String): String =
        raw.trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()

    private companion object {
        const val PREF_NAME = "guanlitong_cx"
        const val KEY_SESSION = "cx_session"
    }
}