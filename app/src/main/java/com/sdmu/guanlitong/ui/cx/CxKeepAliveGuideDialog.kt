package com.sdmu.guanlitong.ui.cx

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 首次进入 App / 首次开启签到实时提醒时的保活引导（最多出现一次）。
 *
 * 只解释系统限制与三个手动入口，不用无障碍、设备管理员等对抗系统的方式。
 */
@Composable
fun CxKeepAliveGuideDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("让签到提醒更可靠") },
        text = {
            Text(
                "系统在空闲压缩或清理后台时可能停掉签到监控。建议把管院通加入电池优化白名单，" +
                    "并在系统设置里允许自启动；开启通知权限后，签到提醒才会以横幅出现。",
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}