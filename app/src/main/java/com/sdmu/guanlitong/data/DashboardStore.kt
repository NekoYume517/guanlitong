package com.sdmu.guanlitong.data

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.ui.graphics.vector.ImageVector
import com.sdmu.guanlitong.data.jwxt.GradeWidgetConfig
import com.sdmu.guanlitong.data.jwxt.parseGradeWidgetConfig
import com.sdmu.guanlitong.data.jwxt.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 同一个功能可以按三种尺寸摆放，尺寸只影响卡片的信息密度。 */
enum class WidgetSize(val key: String, val label: String) {
    Small("small", "小号"),
    Medium("medium", "中号"),
    Large("large", "大号"),
}

/**
 * 概览页小组件的功能类型。
 *
 * [sizes] 是这类组件支持的尺寸：「下一节课」只做小号和中号，
 * 添加面板里不会给它生成大号预览。
 */
enum class WidgetType(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val sizes: Set<WidgetSize> = WidgetSize.entries.toSet(),
) {
    TodayCourses("today_courses", "今日课程", "今天要上的课与时间", Icons.Outlined.CalendarMonth),
    NextClass(
        key = "next_class",
        title = "下一节课",
        subtitle = "按当前时间显示接下来要上的课",
        icon = Icons.Outlined.Schedule,
        sizes = setOf(WidgetSize.Small, WidgetSize.Medium),
    ),
    QuickActions("quick_actions", "快捷功能", "课表、成绩、选课等常用入口", Icons.Outlined.Apps),
    Semester("semester", "教务概览", "当前学期、周次与本周课程", Icons.Outlined.School),
    Accounts("accounts", "账号状态", "教务系统的登录情况", Icons.Outlined.Person),

    /** 学习通签到：只读签到服务落盘的快照，不发请求。 */
    CxSign("cx_sign", "学习通签到", "进行中的签到与最近一次结果", Icons.Outlined.HowToReg),

    /** 学习通收件箱：只读本地收件箱库。 */
    CxInbox("cx_inbox", "学习通收件箱", "未读通知与最新消息", Icons.Outlined.Email),

    /** 成绩：只读教务成绩缓存。 */
    Grades("grades", "成绩", "最近学期的成绩与平均绩点", Icons.Outlined.Assessment),

    /** 第二课堂：只读第二课堂缓存。 */
    Pu("pu", "第二课堂", "总学时与近期活动", Icons.Outlined.WorkspacePremium),

    /** 刷课进度：概览读进程内状态，桌面读刷课快照。 */
    CxStudy("cx_study", "刷课进度", "任务点完成情况与最近结果", Icons.Outlined.Bolt),
}

/** 概览页上的一块小组件。同一个功能可以用不同尺寸出现多次，所以用 id 区分。 */
data class DashboardItem(
    val id: String,
    val type: WidgetType,
    val size: WidgetSize,
    /** 成绩卡片按实例保存的学期/课程/样式；其它类型为 null。 */
    val gradeConfig: GradeWidgetConfig? = null,
)

/**
 * 概览页小组件的本地存储。
 *
 * 顺序就是列表顺序，新增的组件追加到末尾；旧版本存的是逗号分隔的 key 列表，
 * 读取时自动迁移成带尺寸的条目。
 *
 * 进程内共享同一个实例：概览页和各小组件各自 `remember` 新实例时，内存里的列表会分叉，
 * 一处保存成绩配置后另一处再增删组件就会把配置覆盖掉。
 */
class DashboardStore private constructor(context: Context) {

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

    /** 保存某张成绩卡片的配置；同类型的其它卡片互不影响。 */
    fun updateGradeConfig(id: String, config: GradeWidgetConfig) {
        val current = _items.value
        if (current.none { it.id == id }) return
        commit(current.map { if (it.id == id) it.copy(gradeConfig = config) else it })
    }

    fun gradeConfig(id: String): GradeWidgetConfig? =
        _items.value.firstOrNull { it.id == id }?.gradeConfig

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
            // 旧数据没有 gradeConfig 字段：optJSONObject 返回 null，照常读取。
            val gradeConfig = parseGradeWidgetConfig(objectValue.optJSONObject("gradeConfig"))
            result += DashboardItem(id, type, size, gradeConfig)
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
                    .apply { item.gradeConfig?.let { put("gradeConfig", it.toJson()) } }
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

    companion object {
        @Volatile
        private var shared: DashboardStore? = null

        /** 进程内唯一实例；[context] 只用 applicationContext。 */
        fun get(context: Context): DashboardStore =
            shared ?: synchronized(this) {
                shared ?: DashboardStore(context.applicationContext).also { shared = it }
            }

        private const val KEY_ITEMS = "items"
        private const val KEY_LEGACY = "widgets"
    }
}