package com.sdmu.guanlitong.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.service.BrushTaskRegistry
import com.sdmu.guanlitong.service.BrushTaskSnapshot

/**
 * 多任务刷课球（学习通 / 知到共用）：
 *
 * - 球体显示排在最前的任务（运行中优先），右上角红底徽标是正在跑的任务数；
 * - 只有一个任务时点球直接进对应进度页，保持旧体验；
 * - 多个任务时点球从底部展开任务列表，每项是「来源 + 任务名 + 进度/状态」，
 *   点某项进对应页面。结束后任务由业务方从 [BrushTaskRegistry] 移除，球和徽标自动消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrushTaskBall(
    tasks: List<BrushTaskSnapshot>,
    onOpenTask: (BrushTaskSnapshot) -> Unit,
    modifier: Modifier = Modifier,
    bottomAvoid: Dp = 0.dp,
) {
    if (tasks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val direct = BrushTaskRegistry.directTarget(tasks)
    val top = tasks.first()
    StudyBall(
        state = StudyBallState(
            courseName = top.title,
            primary = top.statusText,
            secondary = top.subtitle,
            fraction = top.progress,
        ),
        visible = true,
        onOpen = {
            if (direct != null) onOpenTask(direct) else expanded = true
        },
        modifier = modifier,
        bottomAvoid = bottomAvoid,
        badge = BrushTaskRegistry.badgeCount(tasks),
    )
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "刷课任务（${tasks.size}）",
                    style = MaterialTheme.typography.titleMedium,
                )
                tasks.forEach { task ->
                    TaskRow(task) {
                        expanded = false
                        onOpenTask(task)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: BrushTaskSnapshot, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        task.source.label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    task.title.ifBlank { "刷课任务" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                Text(
                    if (task.running) "运行中" else "已暂停",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (task.running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (task.subtitle.isNotBlank() || task.statusText.isNotBlank()) {
                Text(
                    listOf(task.subtitle, task.statusText)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            LinearProgressIndicator(
                progress = { task.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(4.dp),
            )
        }
    }
}