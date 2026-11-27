package com.sdmu.guanlitong.widget

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.WidgetType
import com.sdmu.guanlitong.data.cx.cxSignRemainingLabel
import com.sdmu.guanlitong.data.jwxt.GradeWidgetConfig
import com.sdmu.guanlitong.data.jwxt.GradeWidgetStyle
import com.sdmu.guanlitong.data.jwxt.ScoreBucket
import com.sdmu.guanlitong.data.jwxt.gradeBoard
import com.sdmu.guanlitong.data.pu.summarizePu
import com.sdmu.guanlitong.ui.format.formatMonthDayTime
import com.sdmu.guanlitong.ui.format.semesterText

/** 编辑页实时预览：读取同一份桌面数据，不提交也能看到切换后的结果。 */
@Composable
fun DesktopWidgetPreview(
    context: Context,
    appWidgetId: Int,
    size: DesktopSize,
    type: WidgetType,
    modifier: Modifier = Modifier,
    gradeConfig: GradeWidgetConfig? = null,
) {
    val state = remember(appWidgetId, size, type, gradeConfig) {
        DesktopData.load(context, size, appWidgetId, type)
            .let { if (gradeConfig != null) it.copy(gradeConfig = gradeConfig) else it }
    }
    val ratio = when (size) {
        DesktopSize.Small -> 1f
        DesktopSize.Medium -> 1.65f
        DesktopSize.Large -> 0.78f
    }
    Surface(
        modifier = modifier.widthIn(max = if (size == DesktopSize.Small) 190.dp else 360.dp)
            .fillMaxWidth().aspectRatio(ratio),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (size == DesktopSize.Small) 12.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = type.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(if (size == DesktopSize.Small) 5.dp else 9.dp))
            if (type == WidgetType.NextClass) NextPreview(state, size) else GenericPreview(state)
            Spacer(Modifier.height(if (size == DesktopSize.Small) 5.dp else 9.dp))
            val nextClassUpdate = if (type == WidgetType.NextClass) {
                state.nextClass.savedAt.takeIf { it > 0 }?.let {
                    " · ${formatMonthDayTime(it)} 更新"
                }.orEmpty()
            } else ""
            Text(
                text = when {
                    type == WidgetType.NextClass && !state.nextClass.hasTimetable -> "长按编辑"
                    type == WidgetType.NextClass && state.nextClass.sessionLost -> "未登录，显示上次课表$nextClassUpdate"
                    type == WidgetType.NextClass && nextClassUpdate.isNotEmpty() -> nextClassUpdate.removePrefix(" · ")
                    type == WidgetType.NextClass -> "显示上次课表"
                    else -> "小组件 · 长按编辑"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NextPreview(state: DesktopState, size: DesktopSize) {
    val snapshot = state.nextClass
    when {
        !snapshot.hasTimetable -> PreviewMessage("打开一次课表后显示")
        snapshot.upcoming.isEmpty() -> PreviewMessage("接下来一周没有课")
        size == DesktopSize.Small -> {
            val first = snapshot.upcoming.first()
            Text(
                text = if (first.isOngoing) "上课中" else first.start?.let(::formatClock) ?: "下一节",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = first.course.name,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(dayPrefix(first, snapshot.today), first.course.room.takeIf { it.isNotBlank() }?.let { "@$it" }, countdownLabel(first))
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        else -> {
            snapshot.upcoming.take(if (size == DesktopSize.Large) 3 else 2).forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = listOf(dayPrefix(item, snapshot.today), item.start?.let(::formatClock).orEmpty())
                                .filter { it.isNotBlank() }.joinToString(" "),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.padding(start = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = item.course.name,
                            style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOf(countdownLabel(item), item.course.room.takeIf { it.isNotBlank() }?.let { "@$it" })
                                .filterNotNull().joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenericPreview(state: DesktopState) {
    when (state.type) {
        WidgetType.TodayCourses -> {
            if (state.todayCourses.isEmpty()) PreviewMessage("今天没有课")
            else state.todayCourses.take(4).forEach {
                Text(
                    text = "${it.startPeriod}–${it.endPeriod}节  ${it.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(it.room.takeIf { room -> room.isNotBlank() }?.let { room -> "@$room" }, it.teacher).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        WidgetType.Semester -> {
            if (state.cached == null) PreviewMessage("打开一次课表后显示")
            else {
                Text("第 ${state.currentWeek} 周", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(semesterText(state.cached.timetable.xnm, state.cached.timetable.xqm))
                Text("本周共 ${state.weekCourses} 门课", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        WidgetType.QuickActions -> {
            val actions = state.quickActions
            if (actions.isEmpty()) {
                PreviewMessage("打开 App 后使用快捷功能")
            } else {
                val columns = if (state.size == DesktopSize.Small) 1 else 2
                actions.chunked(columns).forEachIndexed { index, rowActions ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowActions.forEach { action ->
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = action.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                )
                            }
                        }
                        repeat(columns - rowActions.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        WidgetType.Accounts -> {
            val accountLimit = when (state.size) {
                DesktopSize.Small -> 2
                DesktopSize.Medium -> 4
                DesktopSize.Large -> 5
            }
            state.accounts.take(accountLimit).forEach { (label, loggedIn) ->
                Text("$label · ${if (loggedIn) "已登录" else "未登录"}",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
        WidgetType.CxSign -> {
            val snapshot = state.sign
            val now = System.currentTimeMillis()
            val restriction = snapshot?.takeIf { it.restrictionUntilMs > now }
            val ongoing = snapshot?.ongoingSorted().orEmpty()
            when {
                restriction != null -> {
                    PreviewTitle("受限中")
                    PreviewMessage("解除于 ${formatMonthDayTime(restriction.restrictionUntilMs)}")
                }
                !state.signMonitorEnabled -> PreviewMessage("签到监控未开启")
                ongoing.isNotEmpty() -> {
                    PreviewTitle("${ongoing.size} 个进行中")
                    ongoing.take(if (state.size == DesktopSize.Small) 1 else 2).forEach {
                        Text(
                            text = "${it.courseName} · ${cxSignRemainingLabel(it.endsAtMs, now)}",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                snapshot?.lastResultLabel?.isNotBlank() == true -> {
                    PreviewTitle("最近${snapshot.lastResultLabel}")
                    PreviewMessage(snapshot.lastResultTitle.ifBlank { "学习通签到" })
                }
                else -> PreviewMessage("暂无进行中")
            }
        }
        WidgetType.CxInbox -> {
            val summary = state.inbox
            if (summary == null || !summary.hasData) {
                PreviewMessage("打开收件箱同步后显示")
            } else {
                PreviewTitle(if (summary.unread > 0) "${summary.unread} 条未读" else "全部已读")
                summary.latest.take(if (state.size == DesktopSize.Small) 1 else 2).forEach {
                    Text(
                        text = it.title,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        WidgetType.Grades -> {
            val config = state.gradeConfig ?: GradeWidgetConfig()
            val board = state.grades?.let {
                gradeBoard(it.grades, config, rankingLimit = 3)
            }
            if (board == null) {
                PreviewMessage("打开一次成绩页后显示")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        board.averageScore?.let(::previewNumber) ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("均分 · ${board.courseCount} 门", style = MaterialTheme.typography.labelSmall)
                        Text(
                            board.ranking.firstOrNull()?.let { "${it.grade.name} ${it.grade.score}" }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (state.size != DesktopSize.Small) {
                    if (config.style == GradeWidgetStyle.Courses) {
                        board.ranking.take(3).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    row.grade.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${row.grade.score} · ${previewNumber(row.grade.credit)}学分",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    } else {
                        PreviewBucketBars(board.buckets)
                    }
                }
            }
        }
        WidgetType.Pu -> {
            val cached = state.pu
            val summary = cached?.let { summarizePu(it.activities, it.totalCredit, System.currentTimeMillis()) }
            if (cached == null || summary == null) {
                PreviewMessage("打开第二课堂后显示")
            } else {
                PreviewTitle("总学时 ${previewNumber(cached.totalCredit)}")
                PreviewMessage(summary.headline?.name ?: "暂无近期活动")
            }
        }
        WidgetType.CxStudy -> {
            val snapshot = state.study
            if (snapshot == null) {
                PreviewMessage("打开课程页开始刷课")
            } else {
                PreviewTitle(if (snapshot.running) "${snapshot.overallPercent}%" else "已停止")
                PreviewMessage(snapshot.courseName.ifBlank { snapshot.message.ifBlank { "暂无进度" } })
            }
        }
        WidgetType.NextClass -> Unit
    }
}

@Composable
private fun PreviewTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PreviewBucketBars(buckets: Map<ScoreBucket, Int>) {
    val max = buckets.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(42.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ScoreBucket.entries.forEach { bucket ->
            val count = buckets[bucket] ?: 0
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((2 + 18 * (count.toFloat() / max)).dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(
                            if (count == 0) MaterialTheme.colorScheme.outlineVariant
                            else when (bucket) {
                                ScoreBucket.S90 -> MaterialTheme.colorScheme.primary
                                ScoreBucket.S80 -> MaterialTheme.colorScheme.secondary
                                ScoreBucket.S70 -> MaterialTheme.colorScheme.tertiary
                                ScoreBucket.S60, ScoreBucket.S60Minus -> MaterialTheme.colorScheme.error
                            },
                        ),
                )
                Text(bucket.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PreviewMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center)
}

private fun previewNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.CHINA, "%.1f", value)