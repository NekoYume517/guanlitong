package com.sdmu.guanlitong.ui.pu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuPersonalCode
import com.sdmu.guanlitong.data.pu.mask
import com.sdmu.guanlitong.ui.sdgy.QrImage
import com.sdmu.guanlitong.ui.sdgy.SdgyLoading
import com.sdmu.guanlitong.ui.sdgy.SdgyScaffold
import kotlinx.coroutines.delay

/** 二维码边长（§4.2）。 */
private val PERSONAL_QR_SIZE = 352.dp

/** `refresh` 的钳制范围（§4.2）：服务端给了越界值也不能把客户端拖死。 */
private const val REFRESH_MIN_SECONDS = 5
private const val REFRESH_MAX_SECONDS = 600

/**
 * 个人签到二维码。
 *
 * 服务端 30s 换一次码（`refresh` 字段），页面按同一周期自动重取；
 * `LaunchedEffect` 随页面离开而取消，不会在后台空转。
 */
@Composable
fun PuPersonalCodeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = rememberPuCredStore()

    var code by remember { mutableStateOf<PuPersonalCode?>(null) }
    var remaining by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var authLost by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey, store.loggedIn) {
        if (!store.loggedIn) return@LaunchedEffect
        while (true) {
            if (code == null) loading = true
            when (val r = puCall(context) { PuActions.personalCode() }) {
                is PuOutcome.Ok -> {
                    val fresh = r.value
                    code = fresh
                    error = null
                    loading = false
                    val seconds = fresh.refresh.coerceIn(REFRESH_MIN_SECONDS, REFRESH_MAX_SECONDS)
                    total = seconds
                    remaining = seconds
                    while (remaining > 0) {
                        delay(1_000L)
                        remaining -= 1
                    }
                }

                is PuOutcome.AuthLost -> {
                    authLost = true
                    loading = false
                    return@LaunchedEffect
                }

                is PuOutcome.Fail -> {
                    loading = false
                    error = r.message
                    // 已有旧码时继续展示，但按固定间隔重试；没有码时由错误态提供手动重试。
                    if (code == null) return@LaunchedEffect
                    delay(5_000L)
                }
            }
        }
    }

    SdgyScaffold(
        title = "我的二维码",
        onBack = onBack,
        modifier = modifier,
        actions = {
            if (store.loggedIn && !authLost) {
                IconButton(onClick = { refreshKey++ }, enabled = !loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "立即换码")
                }
            }
        },
    ) { padding ->
        when {
            !store.loggedIn -> PuLoginPrompt(onBack, Modifier.fillMaxSize().padding(padding))

            authLost -> PuErrorState(PU_AUTH_LOST, null, Modifier.fillMaxSize().padding(padding))

            loading && code == null -> SdgyLoading("正在生成二维码…", Modifier.fillMaxSize().padding(padding))

            code == null -> PuErrorState(
                error ?: "二维码生成失败",
                onRetry = { refreshKey++ },
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> {
                val current = code!!
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    QrImage(
                        text = current.code,
                        modifier = Modifier.size(PERSONAL_QR_SIZE),
                        sizePx = 1024,
                    )

                    Spacer(Modifier.height(24.dp))

                    Text(
                        current.name.ifBlank { "口袋校园用户" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "账号 ${mask(current.username)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))

                    if (total > 0) {
                        val fraction = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (remaining > 0) "$remaining 秒后自动换码" else "正在换码…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "每 $total 秒刷新",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "换码失败，展示的是上一张码",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { refreshKey++ }) { Text("重试") }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(
                        "二维码内含加密后的账号标识，仅供活动签到时对方扫码使用；截图转发等同出示本人凭证。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "编码长度 ${current.code.length} 字符",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}