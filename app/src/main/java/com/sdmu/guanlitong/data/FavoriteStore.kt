package com.sdmu.guanlitong.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * 功能页的收藏。只存稳定的目录 ID（`jwxt.timetable`、`sdgy.app.xxx`），
 * 功能的名字、图标和跳转仍由 [FeatureCatalog] 与山管功能表提供。
 *
 * 山管服务器半夜会关机，收藏的是哪个功能由 ID 决定，所以离线时只要本地还有
 * 上次的功能表缓存，收藏页照样能用。
 */
class FavoriteStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(load())

    /** 按收藏先后排列，先收藏的排前面。 */
    val ids: StateFlow<List<String>> = state.asStateFlow()

    fun isFavorite(id: String): Boolean = state.value.contains(id)

    fun add(id: String) {
        update { current -> if (current.contains(id)) current else current + id }
    }

    fun remove(id: String) {
        update { current -> current - id }
    }

    private fun load(): List<String> {
        val raw = prefs.getString(KEY_IDS, null) ?: return emptyList()
        return runCatching {
            JSONArray(raw).let { array ->
                List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun update(transform: (List<String>) -> List<String>) {
        while (true) {
            val current = state.value
            val next = transform(current)
            if (next == current || !state.compareAndSet(current, next)) continue
            prefs.edit().putString(KEY_IDS, JSONArray(next).toString()).apply()
            return
        }
    }

    companion object {
        private const val PREFS = "guanlitong_favorites"
        private const val KEY_IDS = "feature_ids"
    }
}
