package com.sdmu.guanlitong.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** 概览快捷入口只保存稳定的目录 ID，页面与路由仍由 FeatureCatalog 统一提供。 */
class QuickActionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("guanlitong_quick_actions", Context.MODE_PRIVATE)

    private val _featureIds = MutableStateFlow(load())
    val featureIds: StateFlow<List<String>> = _featureIds.asStateFlow()

    fun replace(id: String, replacementId: String) {
        if (!isValidId(replacementId)) return
        val current = _featureIds.value
        val index = current.indexOf(id)
        if (index < 0 || (replacementId != id && replacementId in current)) return
        commit(current.toMutableList().apply { this[index] = replacementId })
    }

    fun reorder(from: Int, to: Int) {
        val current = _featureIds.value
        if (from !in current.indices || to !in current.indices || from == to) return
        commit(current.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun add(id: String) {
        val current = _featureIds.value
        if (current.size >= MAX_ACTIONS || id in current || !isValidId(id)) return
        commit(current + id)
    }

    fun remove(id: String) {
        val current = _featureIds.value
        if (current.size <= 1 || id !in current) return
        commit(current - id)
    }

    private fun load(): List<String> {
        val saved = prefs.getString(KEY_IDS, null)
            ?.let { raw -> runCatching { parse(raw) }.getOrNull() }
            .orEmpty()
        val valid = saved.filter(::isValidId).distinct().take(MAX_ACTIONS)
        return valid.ifEmpty { FeatureCatalog.quickFeatures.map { it.id } }
    }

    private fun commit(ids: List<String>) {
        val next = ids.distinct().take(MAX_ACTIONS)
        val array = JSONArray()
        next.forEach(array::put)
        prefs.edit().putString(KEY_IDS, array.toString()).apply()
        _featureIds.value = next
    }

    private fun parse(raw: String): List<String> = buildList {
        val array = JSONArray(raw)
        for (index in 0 until array.length()) add(array.optString(index))
    }

    private fun isValidId(id: String): Boolean =
        FeatureCatalog.byId(id) != null ||
            (id.startsWith("sdgy.app.") && id.removePrefix("sdgy.app.").isNotBlank())

    companion object {
        const val MAX_ACTIONS = 4
        private const val KEY_IDS = "feature_ids"
    }
}
