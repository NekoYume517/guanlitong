package com.sdmu.guanlitong.ui.sdgy

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdmu.guanlitong.data.sdgy.SdgyPayApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private data class PayUi(
    val firstLoad: Boolean = true,
    val refreshing: Boolean = false,
    val balanceRefreshing: Boolean = false,
    val error: String? = null,
    val cardNo: String = "",
    val cardName: String = "",
    val walletMain: String = "--",
    val wallets: List<Pair<String, String>> = emptyList(),
    val qr: String = "",
    val paid: SdgyPayApi.PayState? = null,
)

/** 内置版沿用 native_pay 的页面结构、倒计时、骨架屏和支付状态。 */
@Composable
fun SdgyPayScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sdgy_pay", Context.MODE_PRIVATE) }
    val repository = rememberSdgyRepository()
    val studentId = remember { repository.studentId() }
    var ui by remember {
        mutableStateOf(
            PayUi(
                cardNo = prefs.getString("acc_num", "").orEmpty(),
                cardName = prefs.getString("acc_name", "").orEmpty(),
            ),
        )
    }
    var reload by remember { mutableIntStateOf(0) }
    var balanceReload by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableLongStateOf(60) }
    var showWallets by remember { mutableStateOf(false) }

    suspend fun refreshCode() {
        if (studentId.isBlank()) {
            ui = ui.copy(firstLoad = false, error = "尚未登录山管 App，无法获取一卡通信息。")
            return
        }
        // 后续刷新保留旧二维码和余额，不清空已显示页面。
        ui = ui.copy(refreshing = true, error = null, paid = null)
        val result = runCatching {
            withContext(Dispatchers.IO) {
                var card = ui.cardNo
                var name = ui.cardName
                if (card.isBlank() || name.isBlank()) {
                    val info = SdgyPayApi.cardInfo(studentId)
                    if (!info.first.isNullOrBlank()) card = info.first.orEmpty()
                    if (!info.second.isNullOrBlank()) name = info.second.orEmpty()
                }
                if (card.isBlank()) {
                    return@withContext ui.copy(
                        firstLoad = false, refreshing = false,
                        error = "暂时读不到一卡通卡号",
                    )
                }
                val wallet = SdgyPayApi.wallet(card)
                val qr = SdgyPayApi.qrCode(card)
                if (qr.isNullOrBlank()) {
                    return@withContext ui.copy(
                        firstLoad = false, refreshing = false,
                        error = "暂时读不到付款码，请重试",
                    )
                }
                PayUi(
                    firstLoad = false,
                    cardNo = card,
                    cardName = name,
                    walletMain = wallet?.main ?: ui.walletMain,
                    wallets = wallet?.others ?: ui.wallets,
                    qr = qr,
                )
            }
        }.getOrElse {
            ui.copy(firstLoad = false, refreshing = false, error = it.sdgyMessage())
        }
        prefs.edit().putString("acc_num", result.cardNo)
            .putString("acc_name", result.cardName).apply()
        ui = result
        countdown = 60
    }

    LaunchedEffect(studentId, reload) { refreshCode() }
    LaunchedEffect(ui.qr) {
        if (ui.qr.isBlank()) return@LaunchedEffect
        countdown = 60
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
        reload++
    }
    LaunchedEffect(balanceReload) {
        if (balanceReload == 0) return@LaunchedEffect
        if (ui.cardNo.isBlank()) {
            reload++
            return@LaunchedEffect
        }
        ui = ui.copy(balanceRefreshing = true)
        val wallet = withContext(Dispatchers.IO) { SdgyPayApi.wallet(ui.cardNo) }
        if (wallet != null) {
            ui = ui.copy(walletMain = wallet.main, wallets = wallet.others)
        }
        ui = ui.copy(balanceRefreshing = false)
    }
    LaunchedEffect(ui.qr) {
        if (ui.qr.isBlank()) return@LaunchedEffect
        while (true) {
            val state = withContext(Dispatchers.IO) { runCatching { SdgyPayApi.poll(ui.qr) }.getOrNull() }
            if (state != null) {
                if (ui.cardName.isBlank() && state.accName.isNotBlank()) {
                    ui = ui.copy(cardName = state.accName)
                    prefs.edit().putString("acc_name", state.accName).apply()
                }
                if (state.paid) {
                    val wallet = withContext(Dispatchers.IO) { SdgyPayApi.wallet(ui.cardNo) }
                    ui = ui.copy(
                        paid = state,
                        walletMain = wallet?.main ?: ui.walletMain,
                        wallets = wallet?.others ?: ui.wallets,
                    )
                    break
                }
            }
            delay(1500)
        }
    }

    SdgyScaffold(
        title = "一卡通付款码",
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = { showWallets = true }) {
                Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = "钱包余额")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (ui.cardName.isNotBlank()) "${ui.cardName} · 一卡通余额" else "一卡通余额",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (ui.firstLoad) {
                            PaySkeletonBar(96.dp, 26.dp)
                        } else {
                            Text(
                                "¥ ${ui.walletMain}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    TextButton(onClick = { balanceReload++ }, enabled = !ui.balanceRefreshing) {
                        Text("刷新")
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            when {
                ui.paid != null -> PaySuccess(ui.paid!!) { reload++ }
                ui.firstLoad && ui.error == null -> PaySkeleton()
                ui.error != null && ui.qr.isBlank() -> Box(
                    Modifier.fillMaxWidth().height(360.dp), Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            ui.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = {
                            if (studentId.isBlank()) onOpenAccount() else reload++
                        }) { Text(if (studentId.isBlank()) "去登录山管 App" else "重试") }
                    }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                        Box(Modifier.padding(14.dp)) {
                            QrImage(ui.qr, Modifier.size(272.dp), sizePx = 720)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (ui.cardName.isNotBlank()) {
                        Text(
                            ui.cardName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        "请对准扫码设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (ui.refreshing) "正在更新…（当前码仍可用）" else "${countdown}s 后自动刷新",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    ui.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "刷新失败，当前码仍可用",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { reload++ },
                        enabled = !ui.refreshing,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            "立即刷新付款码",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
        }
    }

    if (showWallets) {
        AlertDialog(
            onDismissRequest = { showWallets = false },
            title = { Text("钱包余额") },
            text = {
                Column {
                    ui.wallets.forEach { (name, money) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("¥ $money", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (ui.wallets.isEmpty()) Text("暂无其他钱包余额")
                }
            },
            confirmButton = { TextButton(onClick = { showWallets = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun PaySkeleton() {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
        Box(Modifier.padding(14.dp)) {
            Box(
                Modifier.size(272.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFDCDCDC).copy(alpha = paySkeletonAlpha() * 1.6f)),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    PaySkeletonBar(120.dp, 14.dp)
    Spacer(Modifier.height(6.dp))
    PaySkeletonBar(88.dp, 12.dp)
    Spacer(Modifier.height(14.dp))
    PaySkeletonBar(0.dp, 48.dp, rounded = 12.dp, fillWidth = true)
}

@Composable
private fun PaySkeletonBar(width: Dp, height: Dp, rounded: Dp = 6.dp, fillWidth: Boolean = false) {
    Box(
        Modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height).clip(RoundedCornerShape(rounded))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = paySkeletonAlpha())),
    )
}

@Composable
private fun paySkeletonAlpha(): Float {
    val alpha by rememberInfiniteTransition(label = "付款码骨架屏").animateFloat(
        initialValue = 0.12f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "骨架屏呼吸",
    )
    return alpha
}

@Composable
private fun PaySuccess(state: SdgyPayApi.PayState, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(78.dp).clip(RoundedCornerShape(39.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("支付成功", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (state.amount > 0) {
            Text(
                String.format(Locale.US, "¥%.2f", state.amount),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                if (state.dealer.isNotBlank()) PayInfoLine("商户", state.dealer)
                if (state.dealTime.isNotBlank()) PayInfoLine("时间", state.dealTime)
                if (state.accName.isNotBlank()) PayInfoLine("账户", state.accName)
            }
        }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onContinue) { Text("再付一笔") }
    }
}

@Composable
private fun PayInfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
