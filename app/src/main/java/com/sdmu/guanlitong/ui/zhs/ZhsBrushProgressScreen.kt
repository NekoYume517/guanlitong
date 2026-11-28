package com.sdmu.guanlitong.ui.zhs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.zhs.ZhsLessonStatus
import com.sdmu.guanlitong.data.zhs.ZhsLessonTask
import com.sdmu.guanlitong.data.zhs.ZhsTaskController
import com.sdmu.guanlitong.data.zhs.ZhsTaskMode
import com.sdmu.guanlitong.data.zhs.ZhsTaskState
import com.sdmu.guanlitong.data.zhs.formatClock
import com.sdmu.guanlitong.data.zhs.formatSpan

/**
 * 定时刷课的独立实时页面，对照旧项目 `study-timer.js` 的运行中弹窗：
 *
 * 累计新增 / 目标进度条、当前节与节内进度、已刷节数、倍速、每节实时进度列表、
 * 带时间戳的日志、运行中改倍速与「停止」。旧项目靠 3 秒轮询 `/api/brush-time-status`，
 * 这里直接订阅 [ZhsTaskController.state]，字段一一对应旧项目的 `tbs_states`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZhsBrushProgressScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by ZhsTaskController.state.collectAsState()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (state.mode == ZhsTaskMode.ITEM) "单节刷课" else "定时刷课",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (state.courseName.isNotBlank()) {
                            Text(
                                state.courseName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (state.mode == ZhsTaskMode.NONE) {
            EmptyCard(Modifier.padding(padding))
            return@Scaffold
        }
        val lessons = sortedLessons(state.lessons)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.captcha) {
                item(key = "captcha") { CaptchaCard() }
            }
            state.error?.let {
                item(key = "error") {
                    ErrorCard(it, expired = state.expired, onOpenAccount = onOpenAccount)
                }
            }
            item(key = "summary") { SummaryCard(state) }
            if (lessons.isNotEmpty()) {
                item(key = "lessons-title") { SectionTitle("每节实时进度") }
                items(lessons.size) { LessonRow(lessons[it]) }
            }
            if (state.log.isNotEmpty()) {
                item(key = "log") { LogCard(state.log) }
            }
            item(key = "controls") { Controls(state, onBack = onBack) }
        }
    }
}

/** 旧前端排序：数字 index 在前按升序，`R{n}` 轮次在后。 */
private fun sortedLessons(lessons: Map<Long, ZhsLessonTask>): List<ZhsLessonTask> =
    lessons.values.sortedWith(
        compareBy(
            { it.index.startsWith("R") },
            { it.index.removePrefix("R").toIntOrNull() ?: 0 },
        ),
    )

@Composable
private fun EmptyCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("还没有进行中的刷课任务", style = MaterialTheme.typography.titleMedium)
            Text(
                "到课程章节页选择「开始定时刷课」，或点某一节的「刷课」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CaptchaCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("需要滑块验证", style = MaterialTheme.typography.titleSmall)
            Text(
                "批量刷课已停止。请打开智慧树 / 知到 App 完成滑块验证，再回来重新开始。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, expired: Boolean, onOpenAccount: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall)
            if (expired) {
                TextButton(onClick = onOpenAccount) { Text("去重新登录") }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: ZhsTaskState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.mode == ZhsTaskMode.ITEM) "单节刷课" else "定时批量刷课",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "倍速 ${state.speedText}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatSpan(state.gained),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (state.target > 0) " / ${formatSpan(state.target)}" else " 本次新增",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                )
            }
            if (state.target > 0) {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
            }
            Row {
                Text(
                    "已刷 ${state.lessonsDone}/${state.totalLessons} 节",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.current.isNotEmpty()) {
                    Text(
                        " · ${state.current}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.currentName.isNotEmpty()) {
                Text(
                    state.currentName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.running && state.currentProgress > 0) {
                    LinearProgressIndicator(
                        progress = { state.currentProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
            }
            state.summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun LessonRow(lesson: ZhsLessonTask) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lesson.index,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    lesson.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    lessonStatusText(lesson),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (lesson.status == ZhsLessonStatus.CAPTCHA) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            LinearProgressIndicator(
                progress = { lesson.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(4.dp),
            )
            Row(Modifier.padding(top = 4.dp)) {
                Text(
                    if (lesson.total > 0) {
                        "${formatClock(lesson.total * lesson.progress / 100)}/${formatClock(lesson.total)}"
                    } else {
                        "${lesson.progress}%"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (lesson.speed.isNotEmpty()) {
                    Text(
                        " · ${lesson.speed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (lesson.answered > 0) {
                    Text(
                        " · 答题 ${lesson.answered} 道",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun lessonStatusText(lesson: ZhsLessonTask): String = when (lesson.status) {
    ZhsLessonStatus.RUNNING -> "进行中"
    ZhsLessonStatus.DONE -> "完成 +${lesson.gained ?: 0}s"
    ZhsLessonStatus.CAPTCHA -> "需滑块验证"
    null -> "等待"
}

@Composable
private fun LogCard(log: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("日志", style = MaterialTheme.typography.labelLarge)
            log.takeLast(12).forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Controls(state: ZhsTaskState, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.running) {
            Text("运行中改倍速", style = MaterialTheme.typography.labelMedium)
            SpeedPicker(
                selected = state.speed,
                enabled = true,
                onSelect = { ZhsTaskController.chooseSpeed(it) },
            )
            Button(
                onClick = { ZhsTaskController.stop() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("停止（当前这节刷完即停）")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
    }
}