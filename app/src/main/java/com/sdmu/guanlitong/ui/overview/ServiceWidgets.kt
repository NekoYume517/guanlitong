package com.sdmu.guanlitong.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.DashboardStore
import com.sdmu.guanlitong.data.WidgetSize
import com.sdmu.guanlitong.data.cx.CxCredStore
import com.sdmu.guanlitong.data.cx.CxInboxGroup
import com.sdmu.guanlitong.data.cx.CxInboxSummary
import com.sdmu.guanlitong.data.cx.CxNoticeStore
import com.sdmu.guanlitong.data.cx.CxSignBrief
import com.sdmu.guanlitong.data.cx.CxSignSnapshot
import com.sdmu.guanlitong.data.cx.CxSignSnapshotStore
import com.sdmu.guanlitong.data.cx.CxSignStore
import com.sdmu.guanlitong.data.cx.CxStudySnapshotStore
import com.sdmu.guanlitong.data.cx.belongsTo
import com.sdmu.guanlitong.data.cx.cxSignRemainingLabel
import com.sdmu.guanlitong.data.cx.signAccountKey
import com.sdmu.guanlitong.data.cx.summarizeInbox
import com.sdmu.guanlitong.data.formatDuration
import com.sdmu.guanlitong.data.jwxt.CachedGrades
import com.sdmu.guanlitong.data.jwxt.Course
import com.sdmu.guanlitong.data.jwxt.GradeBoard
import com.sdmu.guanlitong.data.jwxt.JcTime
import com.sdmu.guanlitong.data.jwxt.GradeWidgetConfig
import com.sdmu.guanlitong.data.jwxt.GradeWidgetStyle
import com.sdmu.guanlitong.data.jwxt.JwxtGradesCache
import com.sdmu.guanlitong.data.jwxt.ScoreBucket
import com.sdmu.guanlitong.data.jwxt.gradeBoard
import com.sdmu.guanlitong.data.pu.PuCache
import com.sdmu.guanlitong.data.pu.PuSummary
import com.sdmu.guanlitong.data.pu.summarizePu
import com.sdmu.guanlitong.data.remainingRatio
import com.sdmu.guanlitong.data.staleUpdateLabel
import com.sdmu.guanlitong.service.CxTaskStatus
import com.sdmu.guanlitong.ui.format.formatMonthDayTime
import com.sdmu.guanlitong.widget.DesktopWidgetRefresher
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 概览侧新增的 5 种小组件，统一按「数据卡片」呈现：
 * 一张卡只有一个大字号主指标，其余是辅助文字；图形只用条形、堆叠条、细环、
 * 迷你折线、排名徽章、未读角标和进度条，每个图形都只表达数值，不做插画。
 * 全部只读本地数据，不发起任何请求。
 */

// ── 学习通签到：剩余分钟 + 细环 ───────────────────────────

@Composable
internal fun CxSignWidget(size: WidgetSize, onOpen: () -> Unit) {
    val context = LocalContext.current
    val live by CxSignSnapshotStore.state(context).collectAsState()
    val accountKey = CxCredStore(context).session?.let(::signAccountKey).orEmpty()
    val snapshot = live?.takeIf { it.belongsTo(accountKey) }
    val monitorOn = CxSignStore(context).settings().monitorEnabled
    val now = rememberNowMillis()
    val ongoing = snapshot?.ongoingSorted().orEmpty()
    val restriction = snapshot?.takeIf { it.restrictionUntilMs > now }
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    WidgetCard(
        title = "学习通签到",
        size = size,
        actionText = "打开",
        onAction = onOpen,
        onClick = onOpen,
    ) {
        when {
            restriction != null -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("受限中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = error)
                val until = formatMonthDayTime(restriction.restrictionUntilMs)
                Text(
                    if (size == WidgetSize.Small) {
                        "至 " + until.replace("月", "/").replace("日", " ")
                    } else {
                        "解除于 $until"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ongoing.isNotEmpty() -> SignOngoing(size, ongoing, now, primary)

            snapshot?.lastResultLabel?.isNotBlank() == true -> SignLastResult(size, snapshot)

            !monitorOn -> EmptyHint("—", "签到监控未开启", "打开签到页开启")

            else -> EmptyHint("—", "暂无进行中", "最近没有签到活动")
        }
    }
}

@Composable
private fun SignOngoing(size: WidgetSize, ongoing: List<CxSignBrief>, now: Long, accent: Color) {
    val first = ongoing.first()
    val ratio = remainingRatio(first.startsAtMs, first.endsAtMs, now)
    val minutesLeft = ((first.endsAtMs - now).coerceAtLeast(0) / 60_000L).toInt()
    if (size == WidgetSize.Small) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Ring(progress = ratio, color = accent, modifier = Modifier.size(52.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (first.endsAtMs > 0) "$minutesLeft" else "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("分钟", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = first.courseName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (ongoing.size > 1) "${ongoing.size} 个进行中" else first.modeLabel.ifBlank { "签到中" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Ring(progress = ratio, color = accent, modifier = Modifier.size(76.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (first.endsAtMs > 0) "$minutesLeft" else "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("分钟", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "${ongoing.size} 个进行中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ongoing.take(if (size == WidgetSize.Medium) 2 else 4).forEach { brief ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = brief.courseName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = cxSignRemainingLabel(brief.endsAtMs, now),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignLastResult(size: WidgetSize, snapshot: CxSignSnapshot) {
    val success = snapshot.lastResultLabel == "成功"
    val color = if (success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = snapshot.lastResultLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = snapshot.lastResultTitle.ifBlank { "学习通签到" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOf(
            snapshot.lastResultDetail,
            snapshot.lastResultAtMs.takeIf { it > 0 }?.let(::formatClock),
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank() && size != WidgetSize.Small) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 学习通收件箱：未读数 + 类型堆叠条 ────────────────────

@Composable
internal fun CxInboxWidget(size: WidgetSize, onOpen: () -> Unit) {
    val context = LocalContext.current
    val summary = rememberInboxSummary(context)
    val groups = summary?.unreadByGroup.orEmpty()
    WidgetCard(
        title = "学习通收件箱",
        size = size,
        actionText = "打开",
        onAction = onOpen,
        onClick = onOpen,
    ) {
        when {
            summary == null || !summary.hasData -> EmptyHint("—", "收件箱未同步", "打开收件箱同步后显示")

            else -> {
                val unread = summary.unread
                val unreadColor = if (unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                if (size == WidgetSize.Small) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "$unread",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = unreadColor,
                            maxLines = 1,
                        )
                        Text(
                            text = if (unread > 0) "条未读" else "全部已读",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (unread > 0) {
                            InboxStackedBar(groups, Modifier.padding(top = 4.dp))
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$unread",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = unreadColor,
                        )
                        Text(
                            text = if (unread > 0) " 条未读" else " 全部已读",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    if (unread > 0) {
                        InboxStackedBar(groups, Modifier.padding(top = 6.dp))
                        InboxLegend(groups)
                    }
                    if (size == WidgetSize.Large) {
                        Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            summary.latest.take(3).forEach { notice ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = notice.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = formatClock(notice.atMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxStackedBar(groups: Map<CxInboxGroup, Int>, modifier: Modifier = Modifier) {
    val colors = mapOf(
        CxInboxGroup.Checkin to MaterialTheme.colorScheme.primary,
        CxInboxGroup.Work to MaterialTheme.colorScheme.tertiary,
        CxInboxGroup.Notice to MaterialTheme.colorScheme.secondary,
    )
    val entries = CxInboxGroup.entries.mapNotNull { group -> groups[group]?.takeIf { it > 0 }?.let { group to it } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant))
        }
        entries.forEach { (group, count) ->
            Box(
                modifier = Modifier
                    .weight(count.toFloat())
                    .fillMaxHeight()
                    .background(colors.getValue(group)),
            )
        }
    }
}

@Composable
private fun InboxLegend(groups: Map<CxInboxGroup, Int>) {
    val colors = mapOf(
        CxInboxGroup.Checkin to MaterialTheme.colorScheme.primary,
        CxInboxGroup.Work to MaterialTheme.colorScheme.tertiary,
        CxInboxGroup.Notice to MaterialTheme.colorScheme.secondary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        groups.entries.filter { it.value > 0 }.forEach { (group, count) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(colors.getValue(group)))
                Text(
                    text = "${group.label} $count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 成绩：均分 + 分数段分布 + 排名 ──────────────────────

@Composable
internal fun GradesWidget(
    size: WidgetSize,
    itemId: String,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { DashboardStore.get(context) }
    val items by store.items.collectAsState()
    val config = items.firstOrNull { it.id == itemId }?.gradeConfig ?: GradeWidgetConfig()
    // 成绩页/桌面预热写入缓存后版本号 +1，卡片立刻重读，不用等重新进页面。
    val cacheVersion by JwxtGradesCache.version.collectAsState()
    val cached = remember(context, cacheVersion) { DesktopWidgetRefresher.currentCachedGrades(context) }
    val board = remember(cached, config) { cached?.let { gradeBoard(it.grades, config, updatedAtMs = it.savedAt) } }
    var editing by remember { mutableStateOf(false) }
    val editable = items.any { it.id == itemId }

    WidgetCard(
        title = "成绩",
        size = size,
        actionText = if (editable) "编辑" else null,
        onAction = if (editable) ({ editing = true }) else null,
        // 小号卡片放不下文字按钮，用标题行右侧的编辑图标；中/大号仍是「编辑」文字按钮。
        actionIcon = if (editable && size == WidgetSize.Small) Icons.Outlined.Edit else null,
        onClick = onOpen,
    ) {
        if (editing) {
            GradeWidgetDialog(
                initial = config,
                cached = cached,
                onDismiss = { editing = false },
                onSave = {
                    store.updateGradeConfig(itemId, it)
                    editing = false
                },
            )
        }
        if (board == null || cached == null) {
            EmptyHint("—", "暂无成绩", "打开成绩页同步后显示")
            return@WidgetCard
        }
        val fresh = staleUpdateLabel(cached.savedAt)
        when (size) {
            WidgetSize.Small -> GradeSmall(board)
            WidgetSize.Medium -> GradeMedium(board, config, fresh)
            WidgetSize.Large -> GradeLarge(board, config, fresh)
        }
    }
}

@Composable
private fun GradeSmall(board: GradeBoard) {
    val top = board.ranking.firstOrNull()
    if (top == null) {
        Text("暂无成绩", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = top.grade.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = top.grade.score,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = buildString {
                board.averageScore?.let { append("均分 ${formatOne(it)}") }
                append(" · 第 ${top.rank} 名")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        top.grade.score.toFloatOrNull()?.let { LinearTape(it / 100f) }
    }
}

@Composable
private fun GradeMedium(board: GradeBoard, config: GradeWidgetConfig, fresh: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GradeAverage(board, fresh)
        if (config.style == GradeWidgetStyle.Distribution) {
            BucketBars(board.buckets, height = 64.dp)
        }
        GradeRankingRows(board, limit = 2)
    }
}

@Composable
private fun GradeLarge(board: GradeBoard, config: GradeWidgetConfig, fresh: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GradeAverage(board, fresh)
        if (config.style == GradeWidgetStyle.Courses) {
            GradeRankingRows(board, limit = 6)
        } else {
            GradeRankingRows(board, limit = 3)
            BucketBars(board.buckets, height = 58.dp)
            if (board.semesterAverages.size >= 2) {
                SemesterSparkline(board, modifier = Modifier.fillMaxWidth().height(34.dp))
            }
        }
    }
}

/** 成绩卡的主指标：均分；其余信息一律小号。 */
@Composable
private fun GradeAverage(board: GradeBoard, fresh: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = board.averageScore?.let(::formatOne) ?: "—",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = buildString {
                append(" 均分")
                board.averagePoint?.let { append(" · 绩点 ${formatOne(it)}") }
                append(" · ${board.semester}")
                fresh?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatOne(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.CHINA, "%.1f", value)

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** 时间统一 HH:mm（Asia/Shanghai）。 */
private fun formatClock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Shanghai")).format(clockFormat)

@Composable
private fun GradeRankingRows(board: GradeBoard, limit: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        board.ranking.take(limit).forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RankBadge(row.rank, 24.dp)
                Text(
                    text = row.grade.name,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.grade.score,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = buildString {
                        append("${formatOne(row.grade.credit)} 学分")
                        row.grade.point?.let { append(" · 绩点 ${formatOne(it)}") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 名次徽章：1/2/3 主题色不同，4 名以后用中性色。 */
@Composable
private fun RankBadge(rank: Int, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val background = when (rank) {
        1 -> colors.primary
        2 -> colors.secondary
        3 -> colors.tertiary
        else -> colors.surfaceVariant
    }
    val content = when (rank) {
        1 -> colors.onPrimary
        2 -> colors.onSecondary
        3 -> colors.onTertiary
        else -> colors.onSurfaceVariant
    }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$rank",
            style = if (rank <= 3) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
        )
    }
}

/** 五个分数段：柱高与门数联动，颜色从 primary 到 error 表达分数区间。 */
@Composable
private fun BucketBars(buckets: Map<ScoreBucket, Int>, height: Dp) {
    val max = buckets.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
    )
    val barHeight = height - 26.dp
    Row(
        modifier = Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ScoreBucket.entries.forEachIndexed { index, bucket ->
            val count = buckets[bucket] ?: 0
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((barHeight * (count.toFloat() / max)).coerceAtLeast(2.dp))
                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                        .background(if (count == 0) MaterialTheme.colorScheme.outlineVariant else colors[index]),
                )
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 跨学期平均分迷你折线；数据点之间的起伏就是真实学期差异。 */
@Composable
private fun SemesterSparkline(board: GradeBoard, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.tertiary
    val averages = board.semesterAverages.map { it.average }
    val min = averages.min()
    val max = averages.max()
    Canvas(modifier) {
        if (averages.size < 2) return@Canvas
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (averages.size - 1)
        val points = averages.mapIndexed { index, value ->
            Offset(
                x = index * stepX,
                y = size.height - ((value - min) / span * size.height * 0.8 + size.height * 0.1).toFloat(),
            )
        }
        points.forEach { point ->
            drawLine(
                color = accent.copy(alpha = 0.35f),
                start = Offset(point.x, point.y),
                end = Offset(point.x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        for (index in 1 until points.size) {
            drawLine(color = accent, start = points[index - 1], end = points[index], strokeWidth = 2.dp.toPx())
        }
        points.forEach { drawCircle(color = accent, radius = 2.5.dp.toPx(), center = it) }
    }
}

@Composable
private fun GradeWidgetDialog(
    initial: GradeWidgetConfig,
    cached: CachedGrades?,
    onDismiss: () -> Unit,
    onSave: (GradeWidgetConfig) -> Unit,
) {
    val grades = cached?.grades.orEmpty()
    var semesterKey by remember { mutableStateOf(initial.semesterKey) }
    var selected by remember { mutableStateOf(initial.courseNames) }
    var onlyGraded by remember { mutableStateOf(initial.onlyGraded) }
    var style by remember { mutableStateOf(initial.style) }
    val semesters = remember(grades) {
        grades.groupBy { it.semesterKey }
            .map { (key, items) -> key to items.first().semester.ifBlank { key } }
            .sortedByDescending { it.first }
    }
    val visibleCourses = remember(grades, semesterKey) {
        grades.filter { semesterKey.isBlank() || it.semesterKey == semesterKey }
            .map { it.name to it.score }
            .distinctBy { it.first }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("成绩卡片") },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    GradeWidgetConfig(
                        semesterKey = semesterKey,
                        courseNames = selected.take(GradeWidgetConfig.MAX_COURSES).toSet(),
                        onlyGraded = onlyGraded,
                        style = style,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("学期", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = semesterKey.isBlank(),
                        onClick = { semesterKey = "" },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("全部", maxLines = 1) },
                    )
                    semesters.take(3).forEach { (key, label) ->
                        FilterChip(
                            selected = semesterKey == key,
                            onClick = { semesterKey = key },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
                Text("课程（最多 ${GradeWidgetConfig.MAX_COURSES} 门，不选=全部）", style = MaterialTheme.typography.labelMedium)
                visibleCourses.take(20).forEach { (name, score) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = name in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) {
                                    if (selected.size >= GradeWidgetConfig.MAX_COURSES) selected else selected + name
                                } else {
                                    selected - name
                                }
                            },
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(score, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("只看已出分的", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = onlyGraded, onCheckedChange = { onlyGraded = it })
                }
                Text("展示样式", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GradeWidgetStyle.entries.forEach { option ->
                        FilterChip(
                            selected = style == option,
                            onClick = { style = option },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
        },
    )
}

// ── 第二课堂：水杯 ─────────────────────────────────────

@Composable
internal fun PuWidget(size: WidgetSize, onOpen: () -> Unit) {
    val context = LocalContext.current
    val cacheVersion by PuCache.version.collectAsState()
    val cached = remember(context, cacheVersion) { PuCache.peek(context) }
    val summary = remember(cached) {
        cached?.let { summarizePu(it.activities, it.totalCredit, System.currentTimeMillis()) }
    }
    WidgetCard(
        title = "第二课堂",
        size = size,
        actionText = "打开",
        onAction = onOpen,
        onClick = onOpen,
    ) {
        if (cached == null || summary == null) {
            EmptyHint("—", "暂无学时数据", "打开第二课堂后显示")
            return@WidgetCard
        }
        val ratio = summary.creditRatio()
        val fresh = staleUpdateLabel(cached.savedAt)
        if (size == WidgetSize.Small) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatCredit(cached.totalCredit),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = " / ${formatCredit(summary.targetCredit)} 学时",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                LinearTape(ratio)
                Text(
                    text = "还差 ${formatCredit((summary.targetCredit - cached.totalCredit).coerceAtLeast(0.0))} 学时",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatCredit(cached.totalCredit),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = " / ${formatCredit(summary.targetCredit)} 学时",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    if (fresh != null) {
                        Text(
                            text = fresh,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
                        )
                    }
                }
                LinearTape(ratio)
                Text(
                    text = "毕业要求按 ${formatCredit(summary.targetCredit)} 学时估算",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PuCategoryBar(summary, Modifier.padding(top = 2.dp))
                if (size == WidgetSize.Large) {
                    Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (summary.ongoing + summary.upcoming).take(3).forEach { activity ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = activity.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatClock(activity.startMs).ifBlank { "待定" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PuCategoryBar(summary: PuSummary, modifier: Modifier) {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
    )
    val slices = summary.categories.take(3)
    val total = slices.sumOf { it.count }.coerceAtLeast(1)
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            slices.forEachIndexed { index, slice ->
                Box(
                    modifier = Modifier
                        .weight(slice.count.toFloat() / total)
                        .fillMaxHeight()
                        .background(colors[index % colors.size]),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 4.dp)) {
            slices.forEachIndexed { index, slice ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(colors[index % colors.size]))
                    Text(
                        text = "${slice.label} ${slice.count}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── 刷课进度：磁带 / 进度盘 ─────────────────────────────

@Composable
internal fun CxStudyWidget(size: WidgetSize, onOpen: () -> Unit) {
    val context = LocalContext.current
    val live by CxTaskStatus.state.collectAsState()
    val persisted = remember(context) { CxStudySnapshotStore.peek(context) }
    // 进程内状态优先；进程被回收后回退到持久化快照，并标注「上次」。
    val usingPersisted = !live.running && live.courseName.isBlank() && live.message.isBlank()
    val running = if (usingPersisted) persisted?.running == true else live.running
    val percent = if (usingPersisted) persisted?.overallPercent ?: 0 else live.overallPercent
    val courseName = if (usingPersisted) persisted?.courseName.orEmpty() else live.courseName
    val currentTask = if (usingPersisted) persisted?.currentTask.orEmpty() else live.currentTask
    val detail = if (usingPersisted) persisted?.message.orEmpty() else live.message
    val position = if (usingPersisted) persisted?.position ?: 0 else live.position
    val duration = if (usingPersisted) persisted?.duration ?: 0 else live.duration
    val currentIndex = if (usingPersisted) persisted?.currentIndex ?: 0 else live.currentIndex
    val total = if (usingPersisted) persisted?.total ?: 0 else live.total
    val taskRatio = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else percent / 100f
    // 圆环表达整体任务点进度；磁带条表达当前任务点已播比例。
    val ratio = percent / 100f
    val statusLabel = when {
        usingPersisted -> "$percent%"
        running -> "$percent%"
        else -> "已停"
    }

    WidgetCard(
        title = "刷课进度",
        size = size,
        actionText = "打开",
        onAction = onOpen,
        onClick = onOpen,
    ) {
        if (!running && courseName.isBlank() && detail.isBlank()) {
            EmptyHint("—", "未在刷课", "打开课程页开始刷课")
            return@WidgetCard
        }
        if (size == WidgetSize.Small) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Ring(progress = ratio, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = courseName.ifBlank { "刷课任务" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            if (usingPersisted) append("上次 · ")
                            if (total > 0) append("${currentIndex.coerceAtLeast(0)}/$total") else append(detail.ifBlank { "暂无进度" })
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Ring(progress = ratio, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(76.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (total > 0) {
                            Text(
                                text = "$currentIndex/$total",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = courseName.ifBlank { "刷课任务" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearTape(taskRatio)
                    Text(
                        text = if (duration > 0) {
                            "${formatDuration(position)} / ${formatDuration(duration)}"
                        } else {
                            currentTask.ifBlank { detail.ifBlank { "暂无进度" } }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (size == WidgetSize.Large && detail.isNotBlank() && duration > 0) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinearTape(ratio: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

// ── 今日课程：一天时间轴 ────────────────────────────────

/**
 * 今日课程时间轴：每节课按真实起止时间画一条水平条。
 *
 * 已结束用低透明度，进行中用主题色，未开始用中等透明度；中线是当前时刻。
 * [visibleCount] 由卡片尺寸决定（中号 2 条、大号全天），与文字列表一致。
 */
@Composable
internal fun TodayTimeline(
    courses: List<Course>,
    jcTimes: Map<Int, JcTime>,
    visibleCount: Int,
    now: LocalTime,
    modifier: Modifier = Modifier,
) {
    val intervals = remember(courses, jcTimes, visibleCount) {
        courses.take(visibleCount).mapNotNull { course ->
            val start = jcTimes[course.startPeriod]?.start?.let(::parseClock)
            val end = jcTimes[course.endPeriod]?.end?.let(::parseClock)
            if (start == null || end == null || end <= start) null else Triple(start, end, course.startPeriod to course.endPeriod)
        }
    }
    if (intervals.isEmpty()) return
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val nowColor = MaterialTheme.colorScheme.error
    val nowMinutes = now.hour * 60 + now.minute
    val minOfDay = intervals.minOf { it.first }
    val maxOfDay = intervals.maxOf { it.second }
    val span = (maxOfDay - minOfDay).coerceAtLeast(60)
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val y = size.height / 2
        drawLine(
            color = track,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        intervals.forEach { (start, end, _) ->
            val left = (start - minOfDay).toFloat() / span * size.width
            val right = (end - minOfDay).toFloat() / span * size.width
            val alpha = when {
                end < nowMinutes -> 0.28f
                start <= nowMinutes && nowMinutes <= end -> 1f
                else -> 0.6f
            }
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(left, y),
                end = Offset(right.coerceAtLeast(left + 3.dp.toPx()), y),
                strokeWidth = 8.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        if (nowMinutes in minOfDay..maxOfDay) {
            val x = (nowMinutes - minOfDay).toFloat() / span * size.width
            drawLine(
                color = nowColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

private fun parseClock(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return hour * 60 + minute
}

// ── 通用图形 ──────────────────────────────────────────

/** 细环形进度：弧长只表达真实占比。 */
@Composable
private fun Ring(
    progress: Float,
    color: Color,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.08f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** 空态：主指标占位 + 如实说明，不画装饰图形。 */
@Composable
private fun EmptyHint(headline: String, title: String, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(headline, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun rememberInboxSummary(context: android.content.Context): CxInboxSummary? =
    remember(context) {
        val session = CxCredStore(context).session ?: return@remember null
        val key = signAccountKey(session)
        if (key.isBlank()) return@remember null
        val store = CxNoticeStore(context)
        summarizeInbox(store.notices(key), store.lastSyncedAt(key))
    }

@Composable
private fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun formatCredit(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.CHINA, "%.1f", value)