package com.sdmu.guanlitong.data.sdgy

import android.content.Context
import com.sdmu.guanlitong.data.Secrets
import org.json.JSONObject
import java.util.UUID

/**
 * 山管登录态的本地保存。
 *
 * 会话里有 sk、Cookie 和用户信息，整体用系统密钥库里的 AES 密钥加密后再落盘；
 * 重试状态单独保存，用来在账号页显示「登录状态已保留，等待自动恢复」。
 */
class SdgySessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("guanlitong_sdgy", Context.MODE_PRIVATE)

    fun load(): SdgySession? {
        val sealed = prefs.getString(KEY_SESSION, null) ?: return null
        val plain = Secrets.decrypt(sealed) ?: return null
        return runCatching { SdgySession.fromJson(JSONObject(plain)) }.getOrNull()
    }

    fun save(session: SdgySession): Boolean {
        val sealed = Secrets.encrypt(session.toJson().toString()) ?: return false
        prefs.edit().putString(KEY_SESSION, sealed).apply()
        return true
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_SESSION)
            .remove(KEY_ERROR)
            .remove(KEY_ERROR_AT)
            .apply()
    }

    /** 每台设备一个稳定标识，服务端把它当作登录设备。 */
    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = "guanlitong-" + UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun lastError(): String? = prefs.getString(KEY_ERROR, null)?.takeIf { it.isNotBlank() }

    fun lastErrorAt(): Long = prefs.getLong(KEY_ERROR_AT, 0L)

    fun setError(message: String) {
        prefs.edit()
            .putString(KEY_ERROR, message)
            .putLong(KEY_ERROR_AT, System.currentTimeMillis())
            .apply()
    }

    fun markOk() {
        prefs.edit()
            .remove(KEY_ERROR)
            .remove(KEY_ERROR_AT)
            .putLong(KEY_OK_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastOkAt(): Long = prefs.getLong(KEY_OK_AT, 0L)

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ERROR = "last_error"
        const val KEY_ERROR_AT = "last_error_at"
        const val KEY_OK_AT = "last_ok_at"
    }
}