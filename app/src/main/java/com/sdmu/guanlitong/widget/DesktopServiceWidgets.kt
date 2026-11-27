package com.sdmu.guanlitong.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityIntent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sdmu.guanlitong.MainActivity
import com.sdmu.guanlitong.data.cx.CxInboxGroup
import com.sdmu.guanlitong.data.cx.CxInboxSummary
import com.sdmu.guanlitong.data.cx.cxSignRemainingLabel
import com.sdmu.guanlitong.data.formatDuration
import com.sdmu.guanlitong.data.jwxt.GradeBoard
import com.sdmu.guanlitong.data.jwxt.GradeRanking
import com.sdmu.guanlitong.data.jwxt.GradeWidgetConfig
import com.sdmu.guanlitong.data.jwxt.GradeWidgetStyle
import com.sdmu.guanlitong.data.jwxt.ScoreBucket
import com.sdmu.guanlitong.data.jwxt.gradeBoard
import com.sdmu.guanlitong.data.pu.summarizePu
import com.sdmu.guanlitong.data.remainingRatio
import com.sdmu.guanlitong.data.staleUpdateLabel
import com.sdmu.guanlitong.ui.FeatureDeepLink
import com.sdmu.guanlitong.ui.format.formatMonthDayTime
import java.time.LocalTime
import java.util.Locale

/**
 * 桌面侧 5 种小组件。
 *
 * Glance 没有 Compose Canvas：图表退化为「主题色块 + 权重条 + 大数字」，
 * 但每种卡片的视觉语言与概览保持一致：签到=多根水位条、成绩=名次徽章+分布条、
 * 二课=液面杯、收件箱=信封块+类型堆叠条、刷课=磁带条。
 * 所有数据来自 [DesktopState] 已加载的本地缓存，不发请求。
 */

internal fun featureIntent(context: Context, featureId: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(FeatureDeepLink.EXTRA_FEATURE_ID, featureId)
    }

@Composable
internal fun CxSignDesktop(state: DesktopState) {
    val context = LocalContext.current
    val snapshot = state.sign
    val now = System.currentTimeMillis()
    val restriction = snapshot?.takeIf { it.restrictionUntilMs > now }
    val ongoing = snapshot?.ongoingSorted().orEmpty()
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .clickable(onClick = actionStartActivityIntent(featureIntent(context, "cx.sign"))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(GlanceModifier.defaultWeight())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            restriction != null -> {
                BigLine("受限中", GlanceTheme.colors.error)
                BodyLine("解除于 ${formatMonthDayTime(restriction.restrictionUntilMs)}")
            }

            !state.signMonitorEnabled -> EmptyBlock("签到监控未开启", "打开签到页开启")

            ongoing.isNotEmpty() -> {
                val first = ongoing.first()
                val ratio = remainingRatio(first.startsAtMs, first.endsAtMs, now)
                val minutes = ((first.endsAtMs - now).coerceAtLeast(0) / 60_000L).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(first.courseName, style = bodyStyle(), maxLines = 1)
                        Text(
                            listOf(first.modeLabel, "${ongoing.size} 个进行中")
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = captionStyle(),
                            maxLines = 1,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (first.endsAtMs > 0) "$minutes 分钟" else "—",
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                        Text(
                            cxSignRemainingLabel(first.endsAtMs, now),
                            style = captionStyle(),
                            maxLines = 1,
                        )
                    }
                }
                ThickBar(ratio, GlanceTheme.colors.primary, if (state.size == DesktopSize.Small) 84.dp else 200.dp)
                if (state.size != DesktopSize.Small && ongoing.size > 1) {
                    ongoing.drop(1).take(if (state.size == DesktopSize.Large) 3 else 1).forEach { brief ->
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(brief.courseName, style = captionStyle(), modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                            Text(cxSignRemainingLabel(brief.endsAtMs, now), style = captionStyle(), maxLines = 1)
                        }
                    }
                }
            }

            snapshot?.lastResultLabel?.isNotBlank() == true -> {
                val success = snapshot.lastResultLabel == "成功"
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        snapshot.lastResultLabel,
                        style = TextStyle(
                            color = if (success) GlanceTheme.colors.tertiary else GlanceTheme.colors.error,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(snapshot.lastResultTitle.ifBlank { "学习通签到" }, style = bodyStyle(), maxLines = 1)
                }
            }

            else -> EmptyBlock("暂无进行中", "最近没有签到活动")
        }
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

/**
 * 今日课程时间轴（Glance 版）：整条横带按课程数量等分，颜色表达
 * 已结束/进行中/未开始。Glance 没有 Canvas，用色块拼条表达同一语义。
 */
@Composable
internal fun TodayDayTape(state: DesktopState) {
    val timetable = state.cached?.timetable ?: return
    if (state.todayCourses.isEmpty()) return
    val now = LocalTime.now()
    val nowMinutes = now.hour * 60 + now.minute
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 5.dp).height(6.dp),
    ) {
        state.todayCourses.take(if (state.size == DesktopSize.Medium) 2 else 6).forEach { course ->
            val start = clockMinutes(timetable.jcTimes[course.startPeriod]?.start)
            val end = clockMinutes(timetable.jcTimes[course.endPeriod]?.end)
            val color = when {
                start == null || end == null -> GlanceTheme.colors.surfaceVariant
                end < nowMinutes -> GlanceTheme.colors.surfaceVariant
                start <= nowMinutes && nowMinutes <= end -> GlanceTheme.colors.primary
                else -> GlanceTheme.colors.secondary
            }
            Box(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                    .padding(horizontal = 1.dp).cornerRadius(3.dp).background(color),
            ) {}
        }
    }
}

private fun clockMinutes(text: String?): Int? {
    val parts = text?.trim()?.split(":") ?: return null
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return hour * 60 + minute
}

@Composable
internal fun CxInboxDesktop(state: DesktopState) {
    val context = LocalContext.current
    val summary = state.inbox
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .clickable(onClick = actionStartActivityIntent(featureIntent(context, "cx.notices"))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(GlanceModifier.defaultWeight())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (summary == null || !summary.hasData) {
            EmptyBlock("收件箱未同步", "打开收件箱同步后显示")
            return@Column
        }
        Text(
            "${summary.unread}",
            style = TextStyle(
                color = if (summary.unread > 0) GlanceTheme.colors.primary else GlanceTheme.colors.outline,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            if (summary.unread > 0) "条未读" else "全部已读",
            style = captionStyle(),
        )
        Text(
            summary.latest.firstOrNull()?.title.orEmpty(),
            style = bodyStyle(),
            maxLines = 1,
        )
        if (summary.unread > 0) {
            InboxStackedBar(summary.unreadByGroup)
            if (state.size != DesktopSize.Small) {
                Row(modifier = GlanceModifier.padding(top = 4.dp)) {
                    CxInboxGroup.entries.forEach { group ->
                        val count = summary.unreadByGroup[group] ?: 0
                        if (count > 0) {
                            Text(
                                "${group.label} $count  ",
                                style = captionStyle(),
                            )
                        }
                    }
                }
            }
            if (state.size == DesktopSize.Large) {
                summary.latest.take(3).forEach { notice ->
                    Text(
                        notice.title,
                        style = captionStyle(),
                        modifier = GlanceModifier.padding(top = 2.dp),
                        maxLines = 1,
                    )
                }
            }
        }
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

@Composable
internal fun GradesDesktop(state: DesktopState) {
    val context = LocalContext.current
    val cached = state.grades
    val config = state.gradeConfig ?: GradeWidgetConfig()
    val board = cached?.let {
        gradeBoard(
            grades = it.grades,
            config = config,
            rankingLimit = if (state.size == DesktopSize.Small) 1 else if (state.size == DesktopSize.Medium) 2 else 6,
            updatedAtMs = it.savedAt,
        )
    }
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .clickable(onClick = actionStartActivityIntent(featureIntent(context, "jwxt.grades"))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(GlanceModifier.defaultWeight())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (board == null || cached == null) {
            EmptyBlock("暂无成绩", "打开成绩页同步后显示")
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                board.averageScore?.let(::formatOne) ?: "—",
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Column(modifier = GlanceModifier.padding(start = 8.dp)) {
                Text(board.semester, style = captionStyle(), maxLines = 1)
                Text(
                    "${board.courseCount} 门 · 均分",
                    style = captionStyle(),
                    maxLines = 1,
                )
            }
        }
        if (config.style == GradeWidgetStyle.Courses) {
            val limit = when (state.size) {
                DesktopSize.Small -> 1
                DesktopSize.Medium -> 3
                DesktopSize.Large -> 6
            }
            board.ranking.take(limit).forEach { row ->
                CourseRow(row, showBar = state.size == DesktopSize.Large)
            }
        } else {
            val top = board.ranking.firstOrNull()
            if (top != null) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RankChip(top.rank)
                    Text(
                        top.grade.name,
                        style = bodyStyle(),
                        modifier = GlanceModifier.defaultWeight().padding(start = 6.dp),
                        maxLines = 1,
                    )
                    Text(top.grade.score, style = bodyStyle(), maxLines = 1)
                }
            }
            if (state.size != DesktopSize.Small) {
                BucketBars(board.buckets)
                if (state.size == DesktopSize.Large) {
                    board.ranking.drop(1).take(4).forEach { row ->
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${row.rank}", style = captionStyle(), modifier = GlanceModifier.width(16.dp))
                            Text(
                                row.grade.name,
                                style = captionStyle(),
                                modifier = GlanceModifier.defaultWeight(),
                                maxLines = 1,
                            )
                            Text(row.grade.score, style = captionStyle(), maxLines = 1)
                        }
                    }
                }
            }
        }
        staleUpdateLabel(cached.savedAt)?.let { CaptionLine(it) }
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

@Composable
internal fun PuDesktop(state: DesktopState) {
    val context = LocalContext.current
    val cached = state.pu
    val summary = cached?.let { summarizePu(it.activities, it.totalCredit, System.currentTimeMillis()) }
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .clickable(onClick = actionStartActivityIntent(featureIntent(context, "pu.activities"))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(GlanceModifier.defaultWeight())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (cached == null || summary == null) {
            EmptyBlock("暂无学时数据", "打开第二课堂后显示")
            return@Column
        }
        Text(
            formatCredit(cached.totalCredit),
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            "/ ${formatCredit(summary.targetCredit)} 学时 · 按 ${formatCredit(summary.targetCredit)} 估算",
            style = captionStyle(),
            maxLines = 1,
        )
        ThickBar(summary.creditRatio(), GlanceTheme.colors.primary, if (state.size == DesktopSize.Small) 84.dp else 200.dp)
        summary.headline?.let {
            Text(it.name, style = bodyStyle(), maxLines = 1, modifier = GlanceModifier.padding(top = 4.dp))
        }
        if (state.size != DesktopSize.Small) {
            PuCategoryBar(summary)
        }
        if (state.size == DesktopSize.Large) {
            (summary.ongoing + summary.upcoming).take(3).forEach { activity ->
                Text(
                    activity.name,
                    style = captionStyle(),
                    modifier = GlanceModifier.padding(top = 2.dp),
                    maxLines = 1,
                )
            }
        }
        staleUpdateLabel(cached.savedAt)?.let { CaptionLine(it) }
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

@Composable
internal fun CxStudyDesktop(state: DesktopState) {
    val context = LocalContext.current
    val snapshot = state.study
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .clickable(onClick = actionStartActivityIntent(featureIntent(context, "cx.courses"))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(GlanceModifier.defaultWeight())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (snapshot == null) {
            EmptyBlock("未在刷课", "打开课程页开始刷课")
            return@Column
        }
        val ratio = if (snapshot.duration > 0) {
            (snapshot.position.toFloat() / snapshot.duration).coerceIn(0f, 1f)
        } else {
            snapshot.overallPercent / 100f
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (snapshot.running) "${snapshot.overallPercent}%" else "已停止",
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Column(modifier = GlanceModifier.padding(start = 10.dp)) {
                Text(
                    snapshot.courseName.ifBlank { "刷课任务" },
                    style = bodyStyle(),
                    maxLines = 1,
                )
                Text(
                    if (snapshot.total > 0) {
                        "第 ${snapshot.currentIndex.coerceAtLeast(0)}/${snapshot.total} 个任务点"
                    } else {
                        snapshot.message.ifBlank { "暂无进度" }
                    },
                    style = captionStyle(),
                    maxLines = 1,
                )
            }
        }
        ThickBar(ratio, GlanceTheme.colors.primary, if (state.size == DesktopSize.Small) 84.dp else 200.dp)
        if (state.size != DesktopSize.Small && snapshot.duration > 0) {
            Text(
                "${formatDuration(snapshot.position)} / ${formatDuration(snapshot.duration)}",
                style = captionStyle(),
                modifier = GlanceModifier.padding(top = 3.dp),
            )
        }
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

// ── Glance 图形零件 ─────────────────────────────────────

@Composable
private fun BigLine(text: String, color: ColorProvider) {
    Text(
        text = text,
        style = TextStyle(color = color, fontSize = 25.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
}

@Composable
private fun BodyLine(text: String) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
}

@Composable
private fun CaptionLine(text: String) {
    if (text.isBlank()) return
    Text(text = text, style = captionStyle(), maxLines = 2)
}

/** 空态：主指标占位 + 如实说明，不画装饰图形。 */
@Composable
private fun EmptyBlock(title: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "—",
            style = TextStyle(color = GlanceTheme.colors.outline, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        )
        Text(title, style = bodyStyle())
        Text(caption, style = captionStyle())
    }
}

/** 环形在 Glance 退化为粗进度条，但仍与概览同色同比例。 */
@Composable
private fun ThickBar(ratio: Float, color: ColorProvider, width: androidx.compose.ui.unit.Dp = 200.dp) {
    // Glance 的 Box 不叠放子元素，进度条用 Row 首尾两段拼，未完成部分保留轨道色。
    val clamped = ratio.coerceIn(0f, 1f)
    Box(modifier = GlanceModifier.width(width).padding(top = 5.dp)) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(9.dp)
                .cornerRadius(5.dp)
                .background(GlanceTheme.colors.surfaceVariant),
        ) {
            if (clamped > 0f) {
                Box(
                    modifier = GlanceModifier.width((width.value * clamped).dp).fillMaxHeight()
                        .cornerRadius(5.dp)
                        .background(color),
                ) {}
            }
        }
    }
}

@Composable
private fun RingText(value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = TextStyle(
                color = GlanceTheme.colors.primary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(unit, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp))
    }
}

@Composable
private fun InboxStackedBar(groups: Map<CxInboxGroup, Int>) {
    val colorOf = mapOf(
        CxInboxGroup.Checkin to GlanceTheme.colors.primary,
        CxInboxGroup.Work to GlanceTheme.colors.tertiary,
        CxInboxGroup.Notice to GlanceTheme.colors.secondary,
    )
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(8.dp).cornerRadius(4.dp)
            .background(GlanceTheme.colors.surfaceVariant),
    ) {
        CxInboxGroup.entries.forEach { group ->
            val count = groups[group] ?: 0
            if (count > 0) {
                Box(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                        .background(colorOf.getValue(group)),
                ) {}
            }
        }
    }
}

/** 课程清单样式的一行：课程名 + 分数/学分/绩点，大号再加一条分数条。 */
@Composable
private fun CourseRow(row: GradeRanking, showBar: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.grade.name,
            style = bodyStyle(),
            modifier = GlanceModifier.defaultWeight(),
            maxLines = 1,
        )
        Text(
            courseMeta(row),
            style = captionStyle(),
            maxLines = 1,
            modifier = GlanceModifier.padding(start = 6.dp),
        )
    }
    if (showBar) {
        val ratio = (row.grade.score.toFloatOrNull() ?: 0f) / 100f
        ThickBar(ratio, GlanceTheme.colors.primary, 180.dp)
    }
}

private fun courseMeta(row: GradeRanking): String {
    val grade = row.grade
    val point = grade.point?.let(::formatOne)
    return listOfNotNull(grade.score, "${formatOne(grade.credit)}学分", point?.let { "绩点$it" })
        .joinToString(" · ")
}

private fun formatOne(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.CHINA, "%.1f", value)

@Composable
private fun RankChip(rank: Int) {
    val color = when (rank) {
        1 -> GlanceTheme.colors.primary
        2 -> GlanceTheme.colors.secondary
        3 -> GlanceTheme.colors.tertiary
        else -> GlanceTheme.colors.surfaceVariant
    }
    Box(
        modifier = GlanceModifier.width(18.dp).height(18.dp).cornerRadius(9.dp).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$rank",
            style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** 五段分布条：每段固定占比位置，柱高与该段门数联动；无数据段留灰色空格。 */
@Composable
private fun BucketBars(buckets: Map<ScoreBucket, Int>) {
    val max = buckets.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(30.dp).padding(top = 5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ScoreBucket.entries.forEach { bucket ->
            val count = buckets[bucket] ?: 0
            val height = (4f + 22f * (count.toFloat() / max)).dp
            Column(
                modifier = GlanceModifier.defaultWeight().padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(GlanceModifier.height(26.dp - height))
                Box(
                    modifier = GlanceModifier.fillMaxWidth().height(height).cornerRadius(2.dp)
                        .background(
                            if (count == 0) GlanceTheme.colors.surfaceVariant else barColor(bucket),
                        ),
                ) {}
            }
        }
    }
}

@Composable
private fun barColor(bucket: ScoreBucket) = when (bucket) {
    ScoreBucket.S90 -> GlanceTheme.colors.primary
    ScoreBucket.S80 -> GlanceTheme.colors.secondary
    ScoreBucket.S70 -> GlanceTheme.colors.tertiary
    ScoreBucket.S60 -> GlanceTheme.colors.error
    ScoreBucket.S60Minus -> GlanceTheme.colors.error
}

@Composable
private fun PuCategoryBar(summary: com.sdmu.guanlitong.data.pu.PuSummary) {
    val slices = summary.categories.take(3)
    Box(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(7.dp)
            .cornerRadius(4.dp)
            .background(GlanceTheme.colors.surfaceVariant),
    ) {
        slices.forEachIndexed { index, slice ->
            Box(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight().background(
                    when (index) {
                        0 -> GlanceTheme.colors.primary
                        1 -> GlanceTheme.colors.tertiary
                        else -> GlanceTheme.colors.secondary
                    },
                ),
            ) {}
        }
    }
    }
}

@Composable
private fun bodyStyle() = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp)

@Composable
private fun captionStyle() = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp)

private fun formatCredit(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.CHINA, "%.1f", value)