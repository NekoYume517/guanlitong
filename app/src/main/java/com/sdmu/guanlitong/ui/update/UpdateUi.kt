package com.sdmu.guanlitong.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.update.UpdateClient
import com.sdmu.guanlitong.data.update.UpdateInfo
import java.io.File

/** 更新流程的状态；AppRoot 持有，对话框和「我的」页共用。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val downloaded: Long, val total: Long) : UpdateState
    data class Ready(val info: UpdateInfo, val apk: File, val needsPermission: Boolean) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * 检查 / 下载 / 安装的对话框。
 *
 * 只有「发现新版本」「正在下载」「下载完成」「失败」会弹出来；
 * 「检查中」和「已是最新」在「我的」页的行内文字里显示，不打断当前操作。
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
            title = { Text("发现新版本 ${state.info.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.info.notes.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = cleanNotes(state.info.notes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = "安装包 ${formatSize(state.info.apkSize)} · 来自 GitHub Releases",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDownload) { Text("下载并安装") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("稍后") }
            },
        )

        is UpdateState.Downloading -> {
            val total = state.total
            val fraction = if (total > 0) {
                (state.downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            AlertDialog(
                onDismissRequest = {},
                icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
                title = { Text("正在下载 ${state.info.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = if (total > 0) {
                                "${formatSize(state.downloaded)} / ${formatSize(total)}"
                            } else {
                                formatSize(state.downloaded)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
            )
        }

        is UpdateState.Ready -> {
            // 权限可能在用户去过系统设置后已经打开，这里每次显示都重新判断。
            val canInstall = UpdateClient.canInstall(LocalContext.current)
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
                title = { Text(if (canInstall) "下载完成" else "需要允许安装应用") },
                text = {
                    Text(
                        text = if (canInstall) {
                            "安装包已下载到本地缓存。点「安装」交给系统安装器，安装时会覆盖当前版本，账号与设置都会保留。"
                        } else {
                            "Android 要求你允许本应用安装更新包。点「安装」会打开系统的「安装未知应用」开关，" +
                                "允许「管院通」之后回到这里再点一次即可。"
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = onInstall) { Text("安装") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("稍后") }
                },
            )
        }

        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("已是最新版本") },
            text = { Text("当前版本已经是 GitHub 上最新的正式 Release。") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("好") }
            },
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("更新失败") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = onRetry) { Text("重试") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            },
        )

        else -> Unit
    }
}

/** 更新入口那一行的副标题：顺手把检查/下载状态显示出来。 */
fun updateStatusText(state: UpdateState, currentVersion: String): String = when (state) {
    is UpdateState.Idle -> "当前版本 $currentVersion"
    is UpdateState.Checking -> "正在从 GitHub 检查…"
    is UpdateState.UpToDate -> "已是最新版本 $currentVersion"
    is UpdateState.Available -> "发现新版本 ${state.info.version}，点按查看"
    is UpdateState.Downloading -> "正在下载 ${state.info.version}…"
    is UpdateState.Ready -> "新版本 ${state.info.version} 已下载，点按安装"
    is UpdateState.Failed -> "检查失败：${state.message}"
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format("%.1f MB", mb) else "${bytes / 1024} KB"
}

/** Release 说明里常有 Markdown 标题，对话框不渲染 Markdown，去掉行首的 # 更好读。 */
private fun cleanNotes(raw: String): String =
    raw.lineSequence()
        .joinToString("\n") { line -> line.replaceFirst(Regex("^#{1,6}\\s+"), "") }
        .trim()