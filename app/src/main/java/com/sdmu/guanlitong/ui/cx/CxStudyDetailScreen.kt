package com.sdmu.guanlitong.ui.cx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.cx.cxFormatDuration
import com.sdmu.guanlitong.service.CxTaskService
import com.sdmu.guanlitong.service.CxTaskState
import com.sdmu.guanlitong.service.CxTaskStatus

/**
 * 刷课详情页：只读 [CxTaskStatus] 的实时状态，不发任何网络请求。
 *
 * 运行中展示课程名、阶段（第 1 轮 / 第 2 轮重刷）、当前/总任务点、当前任务点名称、
 * 任务点内进度与百分比；结束后如实展示服务写回的 message，并把 fatalError
 * （中止）与 incomplete（有章节没取到，结果不可信）单独标出来，不把「部分失败」
 * 包装成成功。底部「取消刷课」直接委托 [CxTaskService.cancel]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CxStudyDetailScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by CxTaskStatus.state.collectAsState()

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("刷课详情") },
                subtitle = { Text(state.courseName.ifBlank { "学习通" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.running) RunningCard(state) else ResultCard(state)
            }

            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Button(
                    onClick = { CxTaskService.cancel(context) },
                    enabled = state.running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Icon(
                        Icons.Outlined.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("取消刷课", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun RunningCard(state: CxTaskState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                state.courseName.ifBlank { "学习通课程" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    state.phase.ifBlank { "正在准备" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${state.currentIndex}/${state.total}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            LinearProgressIndicator(
                progress = { state.overallPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "当前任务点",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                state.currentTask.ifBlank { "正在准备下一个任务点" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.duration > 0) {
                Text(
                    "${cxFormatDuration(state.position)}/${cxFormatDuration(state.duration)}" +
                        " · ${state.taskPercent}%",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { state.taskPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    state.message.ifBlank { "正在准备…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 结束后的结果：label 判类，message 原样展示，错误信息单独标红。 */
private data class StudyOutcome(
    val label: String,
    val isProblem: Boolean,
)

private fun outcomeOf(state: CxTaskState): StudyOutcome = when {
    state.fatalError != null && state.incomplete != null ->
        StudyOutcome("部分失败（已中止）", true)

    state.fatalError != null -> StudyOutcome("已中止", true)
    state.incomplete != null -> StudyOutcome("部分失败", true)
    state.message.startsWith("已取消") -> StudyOutcome("已取消", false)
    state.message.startsWith("完成") -> StudyOutcome("成功", false)
    state.message.isNotBlank() -> StudyOutcome("已结束", false)
    else -> StudyOutcome("未开始", false)
}

@Composable
private fun ResultCard(state: CxTaskState) {
    val outcome = outcomeOf(state)
    val accent = if (outcome.isProblem) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                outcome.label,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
            if (state.courseName.isNotBlank()) {
                Text(
                    state.courseName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                state.message.ifBlank { "当前没有正在进行的刷课任务" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.fatalError?.let { error ->
                Text(
                    "中止原因：$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.incomplete?.let { incomplete ->
                Text(
                    "结果不可信：$incomplete",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}