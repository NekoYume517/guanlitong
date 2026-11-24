package com.sdmu.guanlitong.data.sdgy

import android.content.Context

/**
 * 官方应用总页的本地缓存。
 *
 * 山管服务器半夜会关机，断网时就用上次的列表，至少原生工具和常见网页入口还在。
 */
object SdgyAppsStore {

    private const val PREFS = "guanlitong_sdgy"
    private const val KEY_JSON = "sdgy_apps_json"
    private const val KEY_AT = "sdgy_apps_at"

    data class Cached(
        val raw: String,
        val savedAt: Long,
        val groups: List<SdgyAppGroup>,
    )

    fun load(context: Context): Cached? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_JSON, null)?.takeIf { it.isNotBlank() } ?: return null
        val groups = runCatching { SdgyAppCatalog.parse(raw) }.getOrNull()
        if (groups.isNullOrEmpty()) return null
        return Cached(raw, prefs.getLong(KEY_AT, 0L), SdgyAppCatalog.withNativeTools(groups))
    }

    fun save(context: Context, raw: String) {
        if (raw.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, raw)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }
}