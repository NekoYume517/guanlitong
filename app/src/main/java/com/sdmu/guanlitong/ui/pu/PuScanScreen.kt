package com.sdmu.guanlitong.ui.pu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuScan
import com.sdmu.guanlitong.data.pu.PuScanResult
import com.sdmu.guanlitong.data.pu.mask
import com.sdmu.guanlitong.ui.sdgy.SdgyKeyValueRow
import com.sdmu.guanlitong.ui.sdgy.SdgyScaffold
import com.sdmu.guanlitong.ui.components.QrScannerDialog
import com.sdmu.guanlitong.ui.sdgy.SdgySectionTitle
import kotlinx.coroutines.launch

/**
 * 扫一扫。
 *
 * 解码结果先过 `PuScan.classify` 再分支渲染：个人码只展示可读字段，
 * 活动码给出「查看详情 / 确认签到」两个动作，其余内容折叠在「查看原始内容」后面，
 * 不把扫码原文直接怼给用户。签到调 `scanSignIn(id, raw)`，原文照传给数据层验签。
 */
@Composable
fun PuScanScreen(
    onBack: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = rememberPuCredStore()
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(true) }
    var raw by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<PuScanResult?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var authLost by remember { mutableStateOf(false) }

    if (scanning) {
        QrScannerDialog(
            onDismiss = { scanning = false },
            onDecoded = { text ->
                raw = text
                result = PuScan.classify(text)
                showRaw = false
                actionMessage = null
                scanning = false
            },
        )
    }

    SdgyScaffold(title = "扫一扫", onBack = onBack, modifier = modifier) { padding ->
        when {
            !store.loggedIn -> PuLoginPrompt(onBack, Modifier.fillMaxSize().padding(padding))

            authLost -> PuErrorState(PU_AUTH_LOST, null, Modifier.fillMaxSize().padding(padding))

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (result == null) {
                    ScanIdleCard(onScan = { scanning = true })
                } else {
                    when (val r = result!!) {
                        is PuScanResult.User -> UserResultCard(r)
                        is PuScanResult.Activity -> ActivityResultCard(
                            result = r,
                            busy = busy,
                            message = actionMessage,
                            onOpenActivity = { onOpenActivity(r.id) },
                            onSignIn = {
                                scope.launch {
                                    busy = true
                                    actionMessage = null
                                    when (val outcome = puCall(context) {
                                        PuActions.scanSignIn(r.id, raw.orEmpty())
                                    }) {
                                        is PuOutcome.Ok -> actionMessage = "签到已提交"
                                        is PuOutcome.AuthLost -> authLost = true
                                        is PuOutcome.Fail -> actionMessage = outcome.detail ?: outcome.message
                                    }
                                    busy = false
                                }
                            },
                        )

                        is PuScanResult.Text -> TextResultCard(
                            raw = r.raw,
                            showRaw = showRaw,
                            onToggleRaw = { showRaw = !showRaw },
                        )
                    }

                    Spacer(Modifier.height(2.dp))
                    OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("重新扫描")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanIdleCard(onScan: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text("识别二维码", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "支持口袋校园活动签到码与个人码；扫码结果只在本机解析。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            FilledTonalButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Text("打开相机扫码")
            }
        }
    }
}

@Composable
private fun ResultShell(
    kind: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Surface(color = accent, shape = RoundedCornerShape(8.dp)) {
                Text(
                    kind,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun UserResultCard(result: PuScanResult.User) {
    ResultShell(
        kind = "个人码",
        accent = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            result.username.ifBlank { "口袋校园用户" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        SdgyKeyValueRow("账号", mask(result.username))
        SdgyKeyValueRow("UID", result.uid)
        SdgyKeyValueRow("解析结果", result.plain)
    }
}

@Composable
private fun ActivityResultCard(
    result: PuScanResult.Activity,
    busy: Boolean,
    message: String?,
    onOpenActivity: () -> Unit,
    onSignIn: () -> Unit,
) {
    val signedOutOfRange = result.type != 1
    ResultShell(
        kind = "活动签到码",
        accent = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            result.title.ifBlank { "活动 ${result.id}" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        SdgyKeyValueRow("活动 ID", result.id.toString())
        SdgyKeyValueRow("码类型", result.type.toString())
        if (signedOutOfRange) {
            Spacer(Modifier.height(6.dp))
            Text(
                "这个码不是签到类型（type=${result.type}），只能查看活动，不能签到。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onOpenActivity, modifier = Modifier.weight(1f)) {
                Text("查看活动")
            }
            FilledTonalButton(
                onClick = onSignIn,
                enabled = !busy && !signedOutOfRange,
                modifier = Modifier.weight(1f),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("确认签到")
                }
            }
        }
        if (message != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextResultCard(
    raw: String,
    showRaw: Boolean,
    onToggleRaw: () -> Unit,
) {
    ResultShell(
        kind = "未识别",
        accent = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            "这不是口袋校园的二维码",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "内容不匹配个人码（xyhui://user/…）或活动签到码格式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onToggleRaw, contentPadding = PaddingValues(0.dp)) {
            Text(if (showRaw) "隐藏原始内容" else "查看原始内容")
        }
        if (showRaw) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    raw,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}