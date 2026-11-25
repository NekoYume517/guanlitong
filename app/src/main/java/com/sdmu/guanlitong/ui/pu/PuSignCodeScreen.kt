package com.sdmu.guanlitong.ui.pu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuActivity
import com.sdmu.guanlitong.data.pu.PuSignCode
import com.sdmu.guanlitong.ui.sdgy.QrImage
import com.sdmu.guanlitong.ui.sdgy.SdgyLoading
import com.sdmu.guanlitong.ui.sdgy.SdgyScaffold
import kotlinx.coroutines.delay

private val SIGN_QR_SIZE = 320.dp
private const val SIGN_REFRESH_MIN = 5
private const val SIGN_REFRESH_MAX = 600

/**
 * 主办方动态签到码。
 *
 * 选活动 → `PuActions.signCode(id)` → 展示最终 `payload`。降级（payload → slim → fit → raw）
 * 全部在数据层完成，UI 只负责把 `level` 和字节占用如实展示出来，
 * 让主办方知道标题过长已经影响了扫码内容。
 */
@Composable
fun PuSignCodeScreen(
    onBack: () -> Unit,
    initialActivityId: Long? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = rememberPuCredStore()

    var selectedId by remember { mutableStateOf(initialActivityId) }
    var query by remember { mutableStateOf("") }

    // 活动选择
    var choices by remember { mutableStateOf<List<PuActivity>>(emptyList()) }
    var listLoading by remember { mutableStateOf(true) }
    var listError by remember { mutableStateOf<String?>(null) }

    // 动态码
    var code by remember { mutableStateOf<PuSignCode?>(null) }
    var remaining by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var codeLoading by remember { mutableStateOf(true) }
    var codeError by remember { mutableStateOf<String?>(null) }

    var authLost by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedId, refreshKey) {
        if (!store.loggedIn || selectedId != null) return@LaunchedEffect
        listLoading = true
        listError = null
        when (val r = puCall(context) { PuActions.canSignList() }) {
            is PuOutcome.Ok -> choices = r.value
            is PuOutcome.AuthLost -> authLost = true
            is PuOutcome.Fail -> listError = r.message
        }
        listLoading = false
    }

    LaunchedEffect(selectedId, refreshKey) {
        val id = selectedId ?: return@LaunchedEffect
        if (!store.loggedIn) return@LaunchedEffect
        code = null
        codeLoading = true
        codeError = null
        while (true) {
            when (val r = puCall(context) { PuActions.signCode(id) }) {
                is PuOutcome.Ok -> {
                    val fresh = r.value
                    code = fresh
                    codeError = null
                    codeLoading = false
                    val seconds = fresh.refresh.coerceIn(SIGN_REFRESH_MIN, SIGN_REFRESH_MAX)
                    total = seconds
                    remaining = seconds
                    while (remaining > 0) {
                        delay(1_000L)
                        remaining -= 1
                    }
                }

                is PuOutcome.AuthLost -> {
                    authLost = true
                    codeLoading = false
                    return@LaunchedEffect
                }

                is PuOutcome.Fail -> {
                    codeLoading = false
                    codeError = r.message
                    if (code == null) return@LaunchedEffect
                    delay(5_000L)
                }
            }
        }
    }

    fun backToPicker() {
        selectedId = null
        code = null
        codeError = null
        total = 0
        remaining = 0
    }

    SdgyScaffold(
        title = if (selectedId == null) "生成签到码" else "签到码",
        onBack = onBack,
        modifier = modifier,
        actions = {
            if (store.loggedIn && !authLost) {
                IconButton(
                    onClick = { refreshKey++ },
                    enabled = !listLoading && !codeLoading,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
            }
        },
    ) { padding ->
        when {
            !store.loggedIn -> PuLoginPrompt(onBack, Modifier.fillMaxSize().padding(padding))

            authLost -> PuErrorState(PU_AUTH_LOST, null, Modifier.fillMaxSize().padding(padding))

            selectedId == null -> {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(26.dp),
                        placeholder = { Text("按标题筛选可签到的活动") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "清空")
                                }
                            }
                        },
                    )

                    val filtered = remember(choices, query) {
                        val key = query.trim()
                        if (key.isEmpty()) choices else choices.filter { it.name.contains(key, ignoreCase = true) }
                    }

                    when {
                        listLoading -> SdgyLoading("正在读取可签到活动…", Modifier.fillMaxSize())

                        listError != null -> PuErrorState(
                            listError.orEmpty(),
                            onRetry = { refreshKey++ },
                            modifier = Modifier.fillMaxSize(),
                        )

                        choices.isEmpty() -> PuEmptyState(
                            "当前账号没有可签到的活动（需要已报名且处于签到时间窗内）",
                            Modifier.fillMaxSize(),
                        )

                        filtered.isEmpty() -> PuEmptyState("没有标题包含「${query.trim()}」的活动", Modifier.fillMaxSize())

                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(filtered, key = { it.id }) { activity ->
                                PuActivityCard(
                                    activity = activity,
                                    onClick = {
                                        selectedId = activity.id
                                        query = ""
                                    },
                                )
                            }
                        }
                    }
                }
            }

            codeLoading && code == null -> SdgyLoading("正在生成签到码…", Modifier.fillMaxSize().padding(padding))

            code == null -> PuErrorState(
                codeError ?: "签到码生成失败",
                onRetry = { refreshKey++ },
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> CodePanel(
                code = code!!,
                padding = padding,
                remaining = remaining,
                total = total,
                error = codeError,
                onChangeActivity = { backToPicker() },
                onRefresh = { refreshKey++ },
            )
        }
    }
}

@Composable
private fun CodePanel(
    code: PuSignCode,
    padding: PaddingValues,
    remaining: Int,
    total: Int,
    error: String?,
    onChangeActivity: () -> Unit,
    onRefresh: () -> Unit,
) {
    val payloadBytes = remember(code.payload) {
        code.payload.toByteArray(Charsets.UTF_8).size
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            QrImage(
                text = code.payload,
                modifier = Modifier.size(SIGN_QR_SIZE),
                sizePx = 1024,
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "降级级别",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Surface(
                color = levelColor(code.level),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    levelLabel(code.level),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "二维码 120 字节上限，标题过长会降级：完整内容 → 精简 → 截断 → 原文。当前占用 $payloadBytes 字节。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        if (total > 0) {
            val fraction = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (remaining > 0) "$remaining 秒后自动刷新" else "正在刷新…",
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
            Spacer(Modifier.height(10.dp))
            Text(
                "刷新失败，展示的是上一张码",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "码内活动 ID ${code.mid} · 来源 ${code.src}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                Text("立即刷新")
            }
            FilledTonalButton(onClick = onChangeActivity, modifier = Modifier.weight(1f)) {
                Text("更换活动")
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "把屏幕亮度调大、让参与者正对屏幕扫码；签到码随倒计时自动变化。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun levelLabel(level: String): String = when (level.lowercase()) {
    "payload" -> "payload · 完整"
    "slim" -> "slim · 精简"
    "fit" -> "fit · 截断"
    "raw" -> "raw · 原文"
    else -> level.ifBlank { "未知" }
}

@Composable
private fun levelColor(level: String): androidx.compose.ui.graphics.Color = when (level.lowercase()) {
    "payload" -> MaterialTheme.colorScheme.secondaryContainer
    "slim" -> MaterialTheme.colorScheme.primaryContainer
    "fit" -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.errorContainer
}