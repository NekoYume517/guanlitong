package com.sdmu.guanlitong.widget

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.WidgetType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 编辑页实时预览：读取同一份桌面数据，不提交也能看到切换后的结果。 */
@Composable
fun DesktopWidgetPreview(
    context: Context,
    appWidgetId: Int,
    size: DesktopSize,
    type: WidgetType,
    modifier: Modifier = Modifier,
) {
    val state = remember(appWidgetId, size, type) {
        DesktopData.load(context, size, appWidgetId, type)
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
                    " · ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it))} 更新"
                }.orEmpty()
            } else ""
            Text(
                text = when {
                    type == WidgetType.NextClass && !state.nextClass.hasTimetable -> "长按编辑"
                    type == WidgetType.NextClass && state.nextClass.sessionLost -> "未登录，显示上次课表$nextClassUpdate"
                    type == WidgetType.NextClass && nextClassUpdate.isNotEmpty() -> nextClassUpdate.removePrefix(" · ")
                    type == WidgetType.NextClass -> "显示上次课表"
                    else -> "桌面卡片 · 长按编辑"
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
                Text("${state.cached.timetable.xnm} 学年 · 第 ${state.cached.timetable.xqm} 学期")
                Text("本周共 ${state.weekCourses} 门课", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        WidgetType.QuickActions -> {
            listOf("课表与成绩", "考试与选课", "抢课任务").forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center)
            }
        }
        WidgetType.Accounts -> state.accounts.forEach { (label, loggedIn) ->
            Text("$label · ${if (loggedIn) "已登录" else "未登录"}",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        WidgetType.NextClass -> Unit
    }
}

@Composable
private fun PreviewMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center)
}