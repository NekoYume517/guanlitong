package com.sdmu.guanlitong.ui.cx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 超过这么多秒还没拿到第一份数据，就认为网络慢，换成「正在重试」并给出重试出口。 */
const val CX_SLOW_CONNECT_SECONDS = 10

/**
 * 冷启动占位：只在「还没有任何内容」时覆盖整页，拿到数据后由调用方切走。
 *
 * 学习通首屏要串行发登录态校验 / 课程列表 / 课程中间页 / 章节树，网络重试叠起来
 * 可能卡十几秒。只转菊花用户不知道卡在哪，所以逐秒显示已等时间；超过
 * [CX_SLOW_CONNECT_SECONDS] 明确提示网络慢，并给一个「重新连接」按钮。
 *
 * @param text 在等什么，例如「正在连接学习通」「正在读取课程」。
 * @param resetKey 变化时秒数归零（比如点了重试）。
 * @param onRetry 为空则不显示按钮（例如页面已有其他重试入口）。
 */
@Composable
fun CxColdStartLoading(
    text: String = "正在连接学习通",
    resetKey: Any? = Unit,
    onRetry: (() -> Unit)? = null,
) {
    var seconds by remember(resetKey) { mutableIntStateOf(0) }
    LaunchedEffect(resetKey) {
        while (true) {
            delay(1_000)
            seconds++
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                "$text…已 $seconds 秒",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (seconds >= CX_SLOW_CONNECT_SECONDS) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "网络较慢，可以先返回再进来，或点下面重新连接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { Text("重新连接") }
                }
            }
        }
    }
}