package com.sdmu.guanlitong.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 概览快捷入口按小组件实例分别保存。
 *
 * 同一个功能可以添加多块快捷功能卡片，每块卡片各自维护入口列表；
 * 存储里只保存稳定的目录 ID，页面与路由仍由 FeatureCatalog 统一提供。
 */
class QuickActionStore(
    context: Context,
    currentWidgetIds: List<String> = emptyList(),
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("guanlitong_quick_actions", Context.MODE_PRIVATE)

    private val _idsByWidget = MutableStateFlow(load(currentWidgetIds))
    val idsByWidget: StateFlow<Map<String, List<String>>> = _idsByWidget.asStateFlow()

    fun featureIds(widgetId: String): List<String> =
        _idsByWidget.value[widgetId] ?: defaultIds()

    fun replace(widgetId: String, id: String, replacementId: String) {
        if (!isValidId(replacementId)) return
        val current = featureIds(widgetId)
        val index = current.indexOf(id)
        if (index < 0 || (replacementId != id && replacementId in current)) return
        commit(widgetId, current.toMutableList().apply { this[index] = replacementId })
    }

    /** 按编辑页拖拽后的完整顺序保存，避免可见列表与存储列表长度不一致时按索引错位。 */
    fun setOrder(widgetId: String, ids: List<String>) {
        val next = ids.filter(::isValidId).distinct().take(MAX_ACTIONS)
        if (next.isEmpty()) return
        commit(widgetId, next)
    }

    fun add(widgetId: String, id: String) {
        val current = featureIds(widgetId)
        if (current.size >= MAX_ACTIONS || id in current || !isValidId(id)) return
        commit(widgetId, current + id)
    }

    fun remove(widgetId: String, id: String) {
        val current = featureIds(widgetId)
        if (current.size <= 1 || id !in current) return
        commit(widgetId, current - id)
    }

    private fun load(widgetIds: List<String>): Map<String, List<String>> {
        val stored = prefs.getString(KEY_BY_WIDGET, null)
            ?.let { raw -> runCatching { parseMap(raw) }.getOrNull() }
            .orEmpty()
            .toMutableMap()

        // 1.2.x 及以前只有一份全局列表：升级时复制给当时已有的每块快捷卡片，
        // 之后各卡片独立编辑。
        prefs.getString(KEY_LEGACY_IDS, null)?.let { raw ->
            val migrated = runCatching { parseList(raw) }.getOrNull()
                ?.let(::sanitize)
                .orEmpty()
                .ifEmpty { defaultIds() }
            widgetIds.forEach { stored.putIfAbsent(it, migrated) }
            prefs.edit().remove(KEY_LEGACY_IDS).apply()
        }
        // 新添加的快捷卡片从默认入口开始。
        widgetIds.forEach { stored.putIfAbsent(it, defaultIds()) }

        val normalized = stored.mapValues { (_, ids) -> sanitize(ids) }
        if (normalized != stored || prefs.getString(KEY_BY_WIDGET, null) == null) {
            persist(normalized)
        }
        return normalized
    }

    private fun sanitize(saved: List<String>): List<String> {
        val valid = saved.mapNotNull(::migrateId).distinct().take(MAX_ACTIONS).toMutableList()
        if (valid.isEmpty()) return defaultIds()

        // 旧版「教务首页」已从目录移除；升级后原位换成考试安排，
        // 如果目标已被占用，则用其他非抢课专属功能补回原来的槽位数。
        val targetSize = saved.distinct().size.coerceAtMost(MAX_ACTIONS)
        if (valid.size < targetSize) {
            val fallbacks = defaultIds() +
                FeatureCatalog.features.filterNot { it.grabSourceOnly }.map { it.id }
            fallbacks.forEach { id ->
                if (valid.size < targetSize && id !in valid) valid.add(id)
            }
        }
        return valid
    }

    private fun migrateId(id: String): String? = when {
        id == "jwxt.home" -> FeatureCatalog.byId("jwxt.exams")?.id
        isValidId(id) -> id
        else -> null
    }

    private fun commit(widgetId: String, ids: List<String>) {
        val next = _idsByWidget.value.toMutableMap().apply {
            this[widgetId] = ids.distinct().take(MAX_ACTIONS)
        }
        persist(next)
        _idsByWidget.value = next
    }

    private fun persist(idsByWidget: Map<String, List<String>>) {
        val json = JSONObject()
        idsByWidget.forEach { (widgetId, ids) ->
            json.put(widgetId, JSONArray().apply { ids.forEach(::put) })
        }
        prefs.edit().putString(KEY_BY_WIDGET, json.toString()).apply()
    }

    private fun parseMap(raw: String): Map<String, List<String>> {
        val json = JSONObject(raw)
        val result = linkedMapOf<String, List<String>>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val widgetId = keys.next()
            val array = json.optJSONArray(widgetId) ?: continue
            result[widgetId] = buildList {
                for (index in 0 until array.length()) add(array.optString(index))
            }
        }
        return result
    }

    private fun parseList(raw: String): List<String> = buildList {
        val array = JSONArray(raw)
        for (index in 0 until array.length()) add(array.optString(index))
    }

    private fun defaultIds(): List<String> = FeatureCatalog.quickFeatures.map { it.id }

    private fun isValidId(id: String): Boolean =
        FeatureCatalog.byId(id) != null ||
            (id.startsWith("sdgy.app.") && id.removePrefix("sdgy.app.").isNotBlank())

    companion object {
        const val MAX_ACTIONS = 4
        private const val KEY_BY_WIDGET = "feature_ids_by_widget"
        private const val KEY_LEGACY_IDS = "feature_ids"
    }
}
