package com.sdmu.guanlitong.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.RowScope
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle


import com.sdmu.guanlitong.MainActivity
import com.sdmu.guanlitong.data.WidgetType
import com.sdmu.guanlitong.data.jwxt.CachedTimetable
import com.sdmu.guanlitong.data.ServiceCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

abstract class DesktopWidgetReceiver : GlanceAppWidgetReceiver() {
    abstract val size: DesktopSize
    override val glanceAppWidget: GlanceAppWidget
        get() = DesktopAppWidget(size)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        DesktopWidgetStore(context).delete(appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class SmallDesktopWidgetReceiver : DesktopWidgetReceiver() { override val size = DesktopSize.Small }
class MediumDesktopWidgetReceiver : DesktopWidgetReceiver() { override val size = DesktopSize.Medium }
class LargeDesktopWidgetReceiver : DesktopWidgetReceiver() { override val size = DesktopSize.Large }

/** 只有一个内容实现，三个系统入口只传入尺寸，不额外暴露内部卡片。 */
class DesktopAppWidget(private val size: DesktopSize) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = DesktopData.load(context, size, (id as androidx.glance.appwidget.AppWidgetId).appWidgetId)
        provideContent {
            GlanceTheme(colors = WidgetColors.colors) {
                DesktopContent(state)
            }
        }
    }
}

private object WidgetColors {
    val colors = androidx.glance.material3.ColorProviders(
        light = androidx.compose.material3.expressiveLightColorScheme(),
        dark = androidx.compose.material3.darkColorScheme(),
    )
}

@Composable
private fun DesktopContent(state: DesktopState) {
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .clickable(onClick = actionStartActivity<MainActivity>())
            .padding(if (state.size == DesktopSize.Small) 12.dp else 16.dp)
            .cornerRadius(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.title,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = if (state.size == DesktopSize.Small) 14.sp else 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        if (state.type == WidgetType.NextClass) {
            NextClassDesktop(state)
        } else {
            GenericDesktop(state)
        }
    }
}

@Composable
private fun NextClassDesktop(state: DesktopState) {
    val snapshot = state.nextClass
    when {
        !snapshot.hasTimetable -> {
            DesktopMessage("打开一次课表后显示")
        }
        snapshot.upcoming.isEmpty() -> {
            DesktopMessage("接下来一周没有课")
        }
        state.size == DesktopSize.Small -> {
            val first = snapshot.upcoming.first()
            Text(
                text = if (first.isOngoing) "上课中" else first.start?.let(::formatClock) ?: "下一节",
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 25.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                text = first.course.name,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(
                text = listOf(dayPrefix(first, snapshot.today), first.course.room.takeIf { it.isNotBlank() }?.let { "@$it" }, countdownLabel(first))
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                style = captionStyle(), maxLines = 2,
            )
        }
        else -> {
            val first = snapshot.upcoming.first()
            Text(
                text = if (first.isOngoing) "正在上课" else countdownLabel(first),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(
                text = first.course.name,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 2,
            )
            Text(
                text = listOf(dayPrefix(first, snapshot.today).takeIf { it.isNotBlank() }, first.timeLabel,
                    first.course.room.takeIf { it.isNotBlank() }?.let { "@$it" })
                    .filterNotNull().joinToString(" · "),
                style = captionStyle(), maxLines = 1,
            )
            snapshot.upcoming.drop(1).take(if (state.size == DesktopSize.Large) 2 else 1).forEach { item ->
                Spacer(GlanceModifier.height(8.dp))
                Column(
                    modifier = GlanceModifier.fillMaxWidth()
                        .background(GlanceTheme.colors.secondaryContainer)
                        .cornerRadius(14.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = listOf(dayPrefix(item, snapshot.today).takeIf { it.isNotBlank() },
                            item.start?.let(::formatClock).orEmpty(), item.course.name)
                            .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSecondaryContainer,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        text = listOf(countdownLabel(item), item.course.room.takeIf { it.isNotBlank() }?.let { "@$it" })
                            .filterNotNull().joinToString(" · "),
                        style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 10.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
    val updateLabel = snapshot.savedAt.takeIf { it > 0 }?.let {
        " · ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it))} 更新"
    }.orEmpty()
    val statusLabel = when {
        !snapshot.hasTimetable -> ""
        snapshot.sessionLost -> "未登录，显示上次课表$updateLabel"
        updateLabel.isNotEmpty() -> updateLabel.removePrefix(" · ")
        else -> "显示上次课表"
    }
    Spacer(GlanceModifier.height(6.dp))
    Text(
        text = if (statusLabel.isEmpty()) "长按编辑" else "$statusLabel · 长按编辑",
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 10.sp,
        ),
        maxLines = 1,
    )
}

@Composable
private fun GenericDesktop(state: DesktopState) {
    when (state.type) {
        WidgetType.TodayCourses -> {
            if (state.todayCourses.isEmpty()) {
                DesktopMessage("今天没有课")
            } else {
                state.todayCourses.take(if (state.size == DesktopSize.Medium) 2 else 4).forEach {
                    Column(
                        modifier = GlanceModifier.fillMaxWidth()
                            .background(GlanceTheme.colors.secondaryContainer)
                            .cornerRadius(14.dp)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${it.startPeriod}–${it.endPeriod}节 · ${it.name}",
                            style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                        Text(
                            text = listOf(it.room.takeIf { room -> room.isNotBlank() }?.let { room -> "@$room" }, it.teacher.orEmpty())
                                .filterNotNull().filter { text -> text.isNotBlank() }.joinToString(" · "),
                            style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 10.sp), maxLines = 1,
                        )
                    }
                    Spacer(GlanceModifier.height(6.dp))
                }
            }
        }
        WidgetType.Semester -> {
            val timetable = state.cached?.timetable
            if (timetable == null) DesktopMessage("打开一次课表后显示")
            else {
                Text("第 ${state.currentWeek} 周", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                Text("${timetable.xnm} 学年 · 第 ${timetable.xqm} 学期", style = bodyStyle())
                Text("本周共 ${state.weekCourses} 门课", style = captionStyle())
            }
        }
        WidgetType.QuickActions -> {
            Text("点击卡片，打开管院通", style = bodyStyle())
            listOf("课表与成绩", "考试与选课").forEach {
                Text(it, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.height(5.dp))
            }
        }
        WidgetType.Accounts -> {
            state.accounts.forEach { (label, loggedIn) ->
                Row {
                    Text(label, style = bodyStyle(), modifier = GlanceModifier.defaultWeight())
                    Text(if (loggedIn) "已登录" else "未登录", style = captionStyle())
                }
                Spacer(GlanceModifier.height(7.dp))
            }
        }
        WidgetType.NextClass -> Unit
    }
    Spacer(GlanceModifier.fillMaxWidth())
}

@Composable
private fun DesktopMessage(text: String) {
    Column(modifier = GlanceModifier.height(80.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = bodyStyle())
    }
}

@Composable
private fun bodyStyle() = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp)

@Composable
private fun captionStyle() = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp)

fun openDesktopWidgetConfiguration(context: Context, appWidgetId: Int, size: DesktopSize): Intent =
    Intent(context, DesktopWidgetConfigActivity::class.java).apply {
        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra(EXTRA_DESKTOP_SIZE, size.key)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
