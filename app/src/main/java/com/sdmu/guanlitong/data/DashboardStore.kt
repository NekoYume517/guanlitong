package com.sdmu.guanlitong.data

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.School
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 概览页小组件的功能类型。 */
enum class WidgetType(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    TodayCourses("today_courses", "今日课程", "今天要上的课与时间", Icons.Outlined.CalendarMonth),
    QuickActions("quick_actions", "快捷功能", "课表、成绩、选课等常用入口", Icons.Outlined.Apps),
    Semester("semester", "教务概览", "当前学期、周次与本周课程", Icons.Outlined.School),
    Accounts("accounts", "账号状态", "教务系统的登录情况", Icons.Outlined.Person),
}

/** 同一个功能可以按三种尺寸摆放，尺寸只影响卡片的信息密度。 */
enum class WidgetSize(val key: String, val label: String) {
    Small("small", "小号"),
    Medium("medium", "中号"),
    Large("large", "大号"),
}

/** 概览页上的一块小组件。同一个功能可以用不同尺寸出现多次，所以用 id 区分。 */
data class DashboardItem(
    val id: String,
    val type: WidgetType,
    val size: WidgetSize,
)

/**
 * 概览页小组件的本地存储。
 *
 * 顺序就是列表顺序，新增的组件追加到末尾；旧版本存的是逗号分隔的 key 列表，
 * 读取时自动迁移成带尺寸的条目。
 */
class DashboardStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("guanlitong_dashboard", Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DashboardItem>> = _items.asStateFlow()

    /** 追加一块组件到末尾，返回新条目（概览页用它做滚动后的高亮）。 */
    fun add(type: WidgetType, size: WidgetSize): DashboardItem {
        val item = DashboardItem(
            id = "${type.key}-${UUID.randomUUID().toString().take(8)}",
            type = type,
            size = size,
        )
        commit(_items.value + item)
        return item
    }

    fun remove(id: String) {
        commit(_items.value.filterNot { it.id == id })
    }

    fun move(from: Int, to: Int) {
        val current = _items.value
        if (from !in current.indices || to !in current.indices || from == to) return
        val reordered = current.toMutableList().apply {
            val item = removeAt(from)
            add(to, item)
        }
        commit(reordered)
    }

    private fun commit(next: List<DashboardItem>) {
        prefs.edit().putString(KEY_ITEMS, encode(next)).apply()
        _items.value = next
    }

    private fun load(): List<DashboardItem> {
        prefs.getString(KEY_ITEMS, null)?.let { raw ->
            runCatching { parse(raw) }.getOrNull()?.let { return it }
        }

        // 旧版本：只存了逗号分隔的 key，按默认尺寸迁移。
        prefs.getString(KEY_LEGACY, null)?.let { raw ->
            val migrated = raw.split(',')
                .mapNotNull { key -> WidgetType.entries.firstOrNull { it.key == key.trim() } }
                .distinct()
                .map { type -> DashboardItem(type.key, type, sizeOf(type)) }
            prefs.edit()
                .putString(KEY_ITEMS, encode(migrated))
                .remove(KEY_LEGACY)
                .apply()
            return migrated
        }

        return defaultItems().also {
            prefs.edit().putString(KEY_ITEMS, encode(it)).apply()
        }
    }

    private fun parse(raw: String): List<DashboardItem> {
        val array = JSONArray(raw)
        val result = mutableListOf<DashboardItem>()
        for (index in 0 until array.length()) {
            val objectValue = array.optJSONObject(index) ?: continue
            val type = WidgetType.entries.firstOrNull { it.key == objectValue.optString("type") } ?: continue
            val size = WidgetSize.entries.firstOrNull { it.key == objectValue.optString("size") } ?: sizeOf(type)
            val id = objectValue.optString("id").ifBlank { "${type.key}-$index" }
            if (result.any { it.id == id }) continue
            result += DashboardItem(id, type, size)
        }
        return result
    }

    private fun encode(items: List<DashboardItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("type", item.type.key)
                    .put("size", item.size.key)
            )
        }
        return array.toString()
    }

    private fun sizeOf(type: WidgetType): WidgetSize =
        if (type == WidgetType.TodayCourses) WidgetSize.Large else WidgetSize.Medium

    private fun defaultItems(): List<DashboardItem> = listOf(
        DashboardItem("today_courses", WidgetType.TodayCourses, WidgetSize.Large),
        DashboardItem("quick_actions", WidgetType.QuickActions, WidgetSize.Medium),
        DashboardItem("semester", WidgetType.Semester, WidgetSize.Small),
        DashboardItem("accounts", WidgetType.Accounts, WidgetSize.Small),
    )

    private companion object {
        const val KEY_ITEMS = "items"
        const val KEY_LEGACY = "widgets"
    }
}