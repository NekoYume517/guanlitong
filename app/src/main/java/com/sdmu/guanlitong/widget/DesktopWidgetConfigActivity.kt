package com.sdmu.guanlitong.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.WidgetType
import com.sdmu.guanlitong.data.jwxt.GradeWidgetConfig
import com.sdmu.guanlitong.data.jwxt.GradeWidgetStyle
import com.sdmu.guanlitong.ui.theme.GuanLiTongTheme
import kotlinx.coroutines.launch

/** 系统长按桌面卡片「编辑 / 设置」时进入这里。 */
@OptIn(ExperimentalLayoutApi::class)
class DesktopWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appWidgetId = intent.getIntExtra(EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(RESULT_CANCELED, resultIntent())
        val store = DesktopWidgetStore(this)
        val original = store.load(appWidgetId)
        val initialSize = DesktopSize.fromKey(intent.getStringExtra(EXTRA_DESKTOP_SIZE) ?: original.size.key)
        setContent {
            GuanLiTongTheme {
                var selectedType by remember { mutableStateOf(original.type) }
                var gradeConfig by remember {
                    mutableStateOf(original.gradeConfig ?: GradeWidgetConfig())
                }
                val gradeCache = remember {
                    DesktopWidgetRefresher.currentCachedGrades(this@DesktopWidgetConfigActivity)
                }
                val scope = rememberCoroutineScope()
                var refreshing by remember { mutableStateOf(false) }
                var lastSavedAt by remember {
                    mutableStateOf(
                        DesktopWidgetRefresher
                            .currentCachedTimetable(this@DesktopWidgetConfigActivity)?.savedAt,
                    )
                }
                var refreshMessage by remember { mutableStateOf<String?>(null) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("编辑小组件") },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                                }
                            },
                        )
                    },
                    bottomBar = {
                        Surface(tonalElevation = 3.dp) {
                            Button(
                                onClick = {
                                    store.save(appWidgetId, initialSize, selectedType)
                                    store.saveGradeConfig(
                                        appWidgetId,
                                        gradeConfig.takeIf { selectedType == WidgetType.Grades },
                                    )
                                    DesktopWidgetRefreshSchedule.ensurePeriodic(this@DesktopWidgetConfigActivity)
                                    refreshDesktopWidgets(this@DesktopWidgetConfigActivity)
                                    setResult(RESULT_OK, resultIntent())
                                    finish()
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                                    .height(50.dp),
                            ) { Text("保存并更新小组件") }
                        }
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = "${initialSize.label} · ${initialSize.description}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "选择卡片内容，预览会实时更新。桌面只提供小、中、大三种尺寸。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            WidgetType.entries.forEach { type ->
                                val selected = type == selectedType
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth(if (initialSize == DesktopSize.Small) 0.47f else 1f)
                                        .clickable { selectedType = type },
                                ) {
                                    Column(
                                        modifier = Modifier.padding(3.dp).border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(26.dp),
                                        ).padding(3.dp),
                                    ) {
                                        DesktopWidgetPreview(
                                            context = this@DesktopWidgetConfigActivity,
                                            appWidgetId = appWidgetId,
                                            size = initialSize,
                                            type = type,
                                            gradeConfig = gradeConfig.takeIf {
                                                type == WidgetType.Grades && selected
                                            },
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = type.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (selected) {
                                            Icon(Icons.Outlined.Check, contentDescription = "已选择",
                                                modifier = Modifier.padding(start = 4.dp).size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                        if (selectedType == WidgetType.Grades) {
                            Text(
                                "成绩卡片",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                            val grades = gradeCache?.grades.orEmpty()
                            val semesters = remember(grades) {
                                grades.groupBy { it.semesterKey }
                                    .map { (key, items) -> key to items.first().semester.ifBlank { key } }
                                    .sortedByDescending { it.first }
                            }
                            val courses = remember(grades, gradeConfig.semesterKey) {
                                grades.filter {
                                    gradeConfig.semesterKey.isBlank() || it.semesterKey == gradeConfig.semesterKey
                                }.map { it.name }.distinct()
                            }
                            Text(
                                "选择学期；不选课程表示该学期全部课程。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(
                                modifier = Modifier.padding(top = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FilterChip(
                                    selected = gradeConfig.semesterKey.isBlank(),
                                    onClick = { gradeConfig = gradeConfig.copy(semesterKey = "") },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("全部", maxLines = 1) },
                                )
                                semesters.take(3).forEach { (key, label) ->
                                    FilterChip(
                                        selected = gradeConfig.semesterKey == key,
                                        onClick = {
                                            gradeConfig = gradeConfig.copy(
                                                semesterKey = key,
                                                courseNames = gradeConfig.courseNames.filter { name ->
                                                    grades.any { it.semesterKey == key && it.name == name }
                                                }.toSet(),
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text(label, maxLines = 1) },
                                    )
                                }
                            }
                            courses.take(20).forEach { name ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = name in gradeConfig.courseNames,
                                        onCheckedChange = { checked ->
                                            gradeConfig = if (checked) {
                                                if (gradeConfig.courseNames.size >= GradeWidgetConfig.MAX_COURSES) {
                                                    gradeConfig
                                                } else {
                                                    gradeConfig.copy(courseNames = gradeConfig.courseNames + name)
                                                }
                                            } else {
                                                gradeConfig.copy(courseNames = gradeConfig.courseNames - name)
                                            }
                                        },
                                    )
                                    Text(name, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "只看已出分的",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = gradeConfig.onlyGraded,
                                    onCheckedChange = {
                                        gradeConfig = gradeConfig.copy(onlyGraded = it)
                                    },
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GradeWidgetStyle.entries.forEach { style ->
                                    FilterChip(
                                        selected = gradeConfig.style == style,
                                        onClick = { gradeConfig = gradeConfig.copy(style = style) },
                                        label = { Text(style.label) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        // 数据刷新：默认 1 小时缓存，自动刷新失败时保留旧数据；这里可以立即拉一次。
                        Text("数据刷新", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "课表默认缓存 1 小时，过期后小组件会自动刷新；失败时继续显示上次的数据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = when {
                                    refreshing -> "正在刷新…"
                                    lastSavedAt != null -> formatDesktopUpdatedLabel(lastSavedAt!!)
                                    else -> "还没有可用的课表缓存"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = !refreshing,
                                onClick = {
                                    scope.launch {
                                        refreshing = true
                                        refreshMessage = null
                                        var report = DesktopWidgetRefresher.refreshNow(
                                            this@DesktopWidgetConfigActivity, force = true,
                                        )
                                        if (report.timetable is DesktopRefreshOutcome.Skipped) {
                                            // 刚好撞上后台刷新时共享了它的结果；手动刷新再补一次强制拉取。
                                            report = DesktopWidgetRefresher.refreshNow(
                                                this@DesktopWidgetConfigActivity, force = true,
                                            )
                                        }
                                        lastSavedAt = DesktopWidgetRefresher
                                            .currentCachedTimetable(this@DesktopWidgetConfigActivity)?.savedAt
                                        val warmed = listOfNotNull(
                                            (report.grades as? PrewarmOutcome.Updated)?.let { "成绩" },
                                            (report.pu as? PrewarmOutcome.Updated)?.let { "第二课堂" },
                                        )
                                        val warmSuffix =
                                            if (warmed.isEmpty()) "" else "（已同步${warmed.joinToString("、")}）"
                                        refreshMessage = when (val timetable = report.timetable) {
                                            is DesktopRefreshOutcome.Updated -> "已刷新$warmSuffix"
                                            is DesktopRefreshOutcome.Failed ->
                                                "刷新失败，继续显示${lastSavedAt?.let(::formatDesktopUpdatedLabel) ?: "旧数据"}"
                                            DesktopRefreshOutcome.NotLoggedIn -> "请先在「我的」里登录教务系统"
                                            DesktopRefreshOutcome.Skipped -> "数据仍是最新的"
                                        }
                                        refreshing = false
                                    }
                                },
                            ) {
                                Text(if (refreshing) "刷新中" else "立即刷新")
                            }
                        }
                        refreshMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun resultIntent() = android.content.Intent().putExtra(EXTRA_APPWIDGET_ID, appWidgetId)

    companion object {
        private const val EXTRA_APPWIDGET_ID = AppWidgetManager.EXTRA_APPWIDGET_ID
    }
}
