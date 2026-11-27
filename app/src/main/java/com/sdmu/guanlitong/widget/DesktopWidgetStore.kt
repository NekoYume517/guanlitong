package com.sdmu.guanlitong.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.sdmu.guanlitong.data.WidgetType
import com.sdmu.guanlitong.data.jwxt.GradeWidgetConfig
import com.sdmu.guanlitong.data.jwxt.parseGradeWidgetConfig
import com.sdmu.guanlitong.data.jwxt.toJson
import org.json.JSONObject

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
    val gradeConfig: GradeWidgetConfig? = null,
)

class DesktopWidgetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("desktop_widgets", Context.MODE_PRIVATE)

    fun load(id: Int): DesktopWidgetConfig = DesktopWidgetConfig(
        size = DesktopSize.fromKey(prefs.getString("size_$id", null)),
        type = WidgetType.entries.firstOrNull {
            it.key == prefs.getString("type_$id", null)
        } ?: WidgetType.NextClass,
        gradeConfig = loadGradeConfig(id),
    )

    fun save(id: Int, size: DesktopSize, type: WidgetType) {
        prefs.edit().putString("size_$id", size.key).putString("type_$id", type.key).apply()
    }

    /**
     * 桌面成绩卡片配置，按 appWidgetId 分别存。
     *
     * 旧版本没有 `grade_<id>`，或内容不是合法 JSON 时返回 null（按默认配置渲染）。
     */
    fun loadGradeConfig(id: Int): GradeWidgetConfig? {
        val raw = prefs.getString("grade_$id", null) ?: return null
        return parseGradeWidgetConfig(runCatching { JSONObject(raw) }.getOrNull())
    }

    fun saveGradeConfig(id: Int, config: GradeWidgetConfig?) {
        prefs.edit().apply {
            if (config == null) remove("grade_$id") else putString("grade_$id", config.toJson().toString())
        }.apply()
    }

    fun delete(ids: IntArray) {
        prefs.edit().apply {
            ids.forEach { remove("size_$it"); remove("type_$it"); remove("grade_$it") }
        }.apply()
    }
}

const val ACTION_DESKTOP_WIDGET_REFRESH = "com.sdmu.guanlitong.widget.REFRESH"
const val ACTION_DESKTOP_WIDGET_CONFIGURE = "com.sdmu.guanlitong.widget.CONFIGURE"
const val EXTRA_DESKTOP_SIZE = "desktop_size"
val INVALID_WIDGET_ID = AppWidgetManager.INVALID_APPWIDGET_ID
