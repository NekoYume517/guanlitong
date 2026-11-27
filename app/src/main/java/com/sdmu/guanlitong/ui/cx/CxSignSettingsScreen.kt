package com.sdmu.guanlitong.ui.cx

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sdmu.guanlitong.data.cx.CX_SIGN_DEFAULT_SEED_LAT
import com.sdmu.guanlitong.data.cx.CX_SIGN_DEFAULT_SEED_LNG
import com.sdmu.guanlitong.data.cx.CxSignStore
import com.sdmu.guanlitong.service.CxKeepAlive
import com.sdmu.guanlitong.service.CxSignStatus
import com.sdmu.guanlitong.service.CxTaskService
import com.sdmu.guanlitong.service.keepAliveHints
import com.sdmu.guanlitong.ui.sdgy.SdgyKeyValueRow
import com.sdmu.guanlitong.ui.sdgy.SdgySectionTitle
import com.sdmu.guanlitong.ui.sdgy.hasPermission
import com.sdmu.guanlitong.ui.sdgy.rememberPermissionRequester
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 签到监控与自动签到设置。
 *
 * 「签到实时提醒」是总开关，默认开启并决定前台服务是否轮询；自动签到/签退只决定
 * 发现活动后要不要直接提交。关闭总开关会同时停掉轮询和两条自动路径。
 *
 * 位置签到用「固定种子坐标 + 教师三点定位」，不需要定位权限：种子只是三个探针的
 * 起点，最终提交的是三边测量算出的教师坐标。种子越接近学校实际位置，解算越稳。
 *
 * 手势签到没有固定密码可填：老师每个活动给的图案都不一样，遇到时只发提醒，
 * 由用户在 [CxGestureSignScreen] 当场划。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CxSignSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { CxSignStore(context.applicationContext) }
    var settings by remember { mutableStateOf(store.settings()) }
    val status by CxSignStatus.state.collectAsState()

    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.hasPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
    }
    // 后台保活：电池白名单、通知开关都是系统状态，回到本页时重新读一次。
    var batteryWhitelisted by remember { mutableStateOf(isBatteryOptimizationIgnored(context)) }
    var notificationsEnabled by remember { mutableStateOf(appNotificationsEnabled(context)) }
    var showKeepAliveGuide by remember { mutableStateOf(false) }

    val requestPermission = rememberPermissionRequester { granted ->
        notificationGranted = granted
        notificationsEnabled = granted && appNotificationsEnabled(context)
        if (!granted) {
            message = "没给通知权限：签到照常跑，但结果通知可能看不到"
            messageIsError = true
        }
    }
    val hints = keepAliveHints(batteryWhitelisted, notificationsEnabled)
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryWhitelisted = isBatteryOptimizationIgnored(context)
                notificationsEnabled = appNotificationsEnabled(context)
                notificationGranted = notificationsEnabled
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    var seedLatText by remember(settings.seedLat) { mutableStateOf(settings.seedLat.toString()) }
    var seedLngText by remember(settings.seedLng) { mutableStateOf(settings.seedLng.toString()) }
    var addressText by remember(settings.address) { mutableStateOf(settings.address) }

    fun refresh() {
        settings = store.settings()
    }

    fun show(text: String, isError: Boolean = false) {
        message = text
        messageIsError = isError
    }

    /** 实时提醒开着就保证服务与保活链在跑；总开关关掉就全停。 */
    fun applyService() {
        if (store.settings().monitorEnabled) {
            CxTaskService.startSign(context)
            CxKeepAlive.enable(context)
        } else {
            CxTaskService.stopSign(context)
            CxKeepAlive.disable(context)
        }
    }

    fun enableNotificationThenStart() {
        if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
        CxTaskService.startSign(context)
        CxKeepAlive.enable(context)
    }

    /** 打开自动提交前先确保实时提醒开着：轮询由总开关控制。 */
    fun enableAuto() {
        if (!store.settings().monitorEnabled) store.setMonitorEnabled(true)
        refresh()
        enableNotificationThenStart()
    }

    // 进程被杀后恢复：实时提醒还开着但轮询没在跑，就重新拉起前台服务和保活链。
    LaunchedEffect(Unit) {
        if (store.settings().monitorEnabled) {
            CxKeepAlive.enable(context)
            if (!CxSignStatus.state.value.running) CxTaskService.startSign(context)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("自动签到") },
                subtitle = { Text("学习通") },
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SdgySectionTitle("开关") }
            item {
                SwitchRow(
                    title = "签到实时提醒",
                    subtitle = "默认开启：有进行中活动时每 90 秒检查，没有时 5 分钟一轮，发现签到/签退立即提醒；关掉即停止监控",
                    checked = settings.monitorEnabled,
                ) { checked ->
                    store.setMonitorEnabled(checked)
                    refresh()
                    if (checked) {
                        // 首次开启实时提醒时也给一次保活引导，之后只在设置页展示。
                        if (!store.hasShownKeepAliveGuide()) showKeepAliveGuide = true
                        enableNotificationThenStart()
                    } else {
                        applyService()
                    }
                }
            }
            item {
                SwitchRow(
                    title = "自动签到",
                    subtitle = "需要实时提醒开启；发现签到后自动提交",
                    checked = settings.checkinEnabled,
                ) { checked ->
                    store.setCheckinEnabled(checked)
                    refresh()
                    if (checked) enableAuto() else applyService()
                }
            }
            item {
                SwitchRow(
                    title = "自动签退",
                    subtitle = "需要实时提醒开启；老师发布的签退未结束时自动完成",
                    checked = settings.checkoutEnabled,
                ) { checked ->
                    store.setCheckoutEnabled(checked)
                    refresh()
                    if (checked) enableAuto() else applyService()
                }
            }
            item {
                Text(
                    "签到实时提醒开着时会常驻一条低优先级通知；发现签到会发「重要提醒」，" +
                        "结果通知每条活动只发一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    "手势签到不需要提前设密码：老师每个活动给的图案都不一样，" +
                        "遇到时会发「重要提醒」，点开通知当场划出图案即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item { SdgySectionTitle("后台保活") }
            item {
                Text(
                    "系统空闲压缩或清理后台时可能停掉签到监控。下面三项需要在系统里手动允许，" +
                        "恢复会更可靠；本应用不使用无障碍、设备管理员等对抗系统的方式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(hints.batteryStatus, style = MaterialTheme.typography.bodyMedium)
                    if (hints.showBatteryWhitelistAction) {
                        TextButton(onClick = { openBatteryOptimizationRequest(context) }) {
                            Text("加入电池优化白名单")
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "厂商自启动：系统里需要手动允许自启动，否则划掉任务后可能无法恢复。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { openVendorAutoStartSettings(context) }) {
                        Text("打开自启动管理")
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        hints.notificationStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hints.showNotificationSettingsAction) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (hints.showNotificationSettingsAction) {
                        TextButton(onClick = { openNotificationSettings(context) }) {
                            Text("去开启通知")
                        }
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item { SectionStatus(status.running, status.lastMessage, status.lastNotifiedAt) }

            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item { SdgySectionTitle("位置签到（不需要定位权限）") }
            item {
                Text(
                    "填学校大致位置即可：系统会用三个探针反解教师坐标。" +
                        "越接近学校实际位置，定位越准。默认 $CX_SIGN_DEFAULT_SEED_LAT / $CX_SIGN_DEFAULT_SEED_LNG。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = seedLatText,
                        onValueChange = { seedLatText = it },
                        label = { Text("纬度") },
                        singleLine = true,
                        // Number 键盘没有小数点和负号，经纬度根本输不进去。
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = seedLngText,
                        onValueChange = { seedLngText = it },
                        label = { Text("经度") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    label = { Text("签到地点") },
                    placeholder = { Text("例如：教学楼 A 座（缺省用活动自带地址）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                TextButton(onClick = {
                    val lat = seedLatText.toDoubleOrNull()
                    val lng = seedLngText.toDoubleOrNull()
                    when {
                        lat == null || lng == null -> show("经纬度要是数字", isError = true)
                        lat !in -90.0..90.0 -> show("纬度应在 -90 到 90 之间", isError = true)
                        lng !in -180.0..180.0 -> show("经度应在 -180 到 180 之间", isError = true)
                        else -> {
                            store.setSeed(lat, lng)
                            store.setAddress(addressText)
                            refresh()
                            show("位置已保存")
                        }
                    }
                }) { Text("保存位置") }
            }

            item {
                message?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }
    }

    if (showKeepAliveGuide) {
        CxKeepAliveGuideDialog(
            onDismiss = {
                store.markKeepAliveGuideShown()
                showKeepAliveGuide = false
            },
            onOpenSettings = {
                store.markKeepAliveGuideShown()
                showKeepAliveGuide = false
                openBatteryOptimizationRequest(context)
            },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionStatus(running: Boolean, lastMessage: String, lastNotifiedAt: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SdgySectionTitle("运行状态")
        SdgyKeyValueRow("轮询", if (running) "进行中 90 秒 / 空闲 5 分钟" else "未运行")
        if (lastMessage.isNotBlank()) SdgyKeyValueRow("上次停止", lastMessage)
        if (lastNotifiedAt > 0) {
            val text = remember(lastNotifiedAt) {
                SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastNotifiedAt))
            }
            SdgyKeyValueRow("最近结果", text)
        }
    }
}