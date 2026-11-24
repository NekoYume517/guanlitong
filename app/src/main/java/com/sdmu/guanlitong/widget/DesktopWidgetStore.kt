package com.sdmu.guanlitong.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.sdmu.guanlitong.data.WidgetType

/** 桌面只暴露三种尺寸；内容类型由每个实例独立配置。 */
enum class DesktopSize(val key: String, val label: String, val description: String) {
    Small("small", "小号", "2 × 2"),
    Medium("medium", "中号", "3 × 2"),
    Large("large", "大号", "3 × 4"),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: Medium
    }
}

data class DesktopWidgetConfig(
    val size: DesktopSize = DesktopSize.Medium,
    val type: WidgetType = WidgetType.NextClass,
)

class DesktopWidgetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("desktop_widgets", Context.MODE_PRIVATE)

    fun load(id: Int): DesktopWidgetConfig = DesktopWidgetConfig(
        size = DesktopSize.fromKey(prefs.getString("size_$id", null)),
        type = WidgetType.entries.firstOrNull {
            it.key == prefs.getString("type_$id", null)
        } ?: WidgetType.NextClass,
    )

    fun save(id: Int, size: DesktopSize, type: WidgetType) {
        prefs.edit().putString("size_$id", size.key).putString("type_$id", type.key).apply()
    }

    fun delete(ids: IntArray) {
        prefs.edit().apply {
            ids.forEach { remove("size_$it"); remove("type_$it") }
        }.apply()
    }
}

const val ACTION_DESKTOP_WIDGET_REFRESH = "com.sdmu.guanlitong.widget.REFRESH"
const val ACTION_DESKTOP_WIDGET_CONFIGURE = "com.sdmu.guanlitong.widget.CONFIGURE"
const val EXTRA_DESKTOP_SIZE = "desktop_size"
val INVALID_WIDGET_ID = AppWidgetManager.INVALID_APPWIDGET_ID
