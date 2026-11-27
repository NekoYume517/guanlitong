package com.sdmu.guanlitong.ui.cx

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sdmu.guanlitong.data.cx.CxCourse
import com.sdmu.guanlitong.data.cx.CxNoticeEntity
import com.sdmu.guanlitong.data.cx.CxOpenSign
import com.sdmu.guanlitong.data.cx.CxRemoteSignGateway
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxSignHistoryEntry
import com.sdmu.guanlitong.data.cx.CxSignHistoryResult
import com.sdmu.guanlitong.data.cx.CxSignKind
import com.sdmu.guanlitong.data.cx.CxSignListState
import com.sdmu.guanlitong.data.cx.CxSignRestrictedException
import com.sdmu.guanlitong.data.cx.CxSignRestriction
import com.sdmu.guanlitong.data.cx.CxSignScanResult
import com.sdmu.guanlitong.data.cx.CxSignScanner
import com.sdmu.guanlitong.data.cx.CxSignStore
import com.sdmu.guanlitong.data.cx.cxMessage
import com.sdmu.guanlitong.data.cx.cxSignActionLabel
import com.sdmu.guanlitong.data.cx.cxSignPageState
import com.sdmu.guanlitong.data.cx.signAccountKey
import com.sdmu.guanlitong.data.cx.successfulCheckinIds
import com.sdmu.guanlitong.ui.sdgy.SdgyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "CxSignScreen"

/**
 * 学习通「签到」独立页：进行中的签到 + 实时提醒状态 + 最近记录。
 *
 * 进行中与收件箱置顶共用 [CxSignScanner]（同一份课程缓存、同一套并发与节流），
 * 进入即扫、下拉重扫；手势活动走九宫格，其它活动仍进收件箱详情的手动签到路径。
 * 自动签到开着时只显示后台状态，不再要求逐条手点。
 *
 * 最近记录读本地事件日志（与去重台账分开），离线也能看；有课程 id 的记录点开
 * 回课程详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CxSignScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCourse: (CxCourse) -> Unit,
    onOpenWork: (actionUrl: String, title: String) -> Unit,
    onOpenGesture: (
        activeId: String,
        courseId: String,
        classId: String,
        courseName: String,
        activeName: String,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(context) { CxRepository(context.applicationContext) }
    val store = remember(context) { CxSignStore(context.applicationContext) }
    val scope = rememberCoroutineScope()

    val gateway = remember(repo) { CxRemoteSignGateway(repo) }
    val scanner = remember(gateway) { CxSignScanner(gateway) }

    var loggedIn by remember { mutableStateOf(repo.loggedIn) }
    var accountKey by remember { mutableStateOf("") }
    var monitorEnabled by remember { mutableStateOf(store.settings().monitorEnabled) }
    var autoCheckin by remember { mutableStateOf(store.settings().checkinEnabled) }
    var openSigns by remember { mutableStateOf<List<CxOpenSign>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    // 只用于 debug 构建的扫描摘要，不额外发请求。
    var scanSummary by remember { mutableStateOf<CxSignScanResult?>(null) }
    var history by remember { mutableStateOf(emptyList<CxSignHistoryEntry>()) }
    var pageMessage by remember { mutableStateOf<String?>(null) }
    var restriction by remember { mutableStateOf<CxSignRestriction?>(null) }
    var openingCourse by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CxNoticeEntity?>(null) }
    var selectedSign by remember { mutableStateOf<CxOpenSign?>(null) }

    fun reloadLocalState() {
        val settings = store.settings()
        monitorEnabled = settings.monitorEnabled
        autoCheckin = settings.checkinEnabled
        if (accountKey.isNotBlank()) history = store.history(accountKey)
    }

    /** 活动在页面停留期间自然结束：追加一条「已结束」，同活动同类型只留一条。 */
    fun recordEnded(sign: CxOpenSign) {
        val key = accountKey
        if (key.isBlank()) return
        store.appendHistory(
            key,
            CxSignHistoryEntry(
                activeId = sign.activeId,
                kind = CxSignKind.CHECKIN,
                result = CxSignHistoryResult.ENDED,
                atMs = sign.endsAtMs ?: System.currentTimeMillis(),
                courseName = sign.courseName,
                activeName = sign.activeName,
                courseId = sign.courseId,
                clazzId = sign.clazzId,
            ),
        )
        history = store.history(key)
    }

    fun scan() {
        if (scanning || !repo.loggedIn) return
        scanning = true
        scanError = null
        pageMessage = null
        scope.launch {
            try {
                val key = repo.withSession { signAccountKey(it) }
                accountKey = key
                loggedIn = true
                // 先读本地历史：扫描失败 / 离线时最近记录仍然要能看。
                history = store.history(key)
                reloadLocalState()
                // 受限期间不发任何请求，横幅提示解除时间。
                store.restriction(key, System.currentTimeMillis())?.let { active ->
                    restriction = active
                    if (openSigns == null) openSigns = emptyList()
                    Log.i(TAG, "scan skipped: restricted until=${active.untilMs}")
                    return@launch
                }
                // 已成功签到的活动服务端仍返回 status=1，用本地成功记录从「进行中」排掉。
                val succeeded = successfulCheckinIds(store.history(key))
                val result = scanner.scanResult(excludeActiveIds = succeeded)
                restriction = null
                store.clearRestriction(key)
                scanSummary = result
                val now = System.currentTimeMillis()
                // 扫描回来就已经过期的活动（服务器状态还没翻）也补一条结束记录。
                result.open.filter { it.endsAtMs != null && it.endsAtMs <= now }
                    .forEach(::recordEnded)
                openSigns = result.open
                // 扫描期间可能补了「已结束」记录，再读一次。
                history = store.history(key)
            } catch (e: CxSignRestrictedException) {
                if (accountKey.isNotBlank()) store.saveRestriction(accountKey, e.restriction)
                restriction = e.restriction
                Log.w(TAG, "签到扫描被限制 until=${e.restriction.untilMs}")
                if (openSigns == null) openSigns = emptyList()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "签到扫描失败", e)
                scanError = e.cxMessage()
                // 失败不清已有列表：有旧数据就继续展示，只留一行失败提示。
                if (openSigns == null) openSigns = emptyList()
            } finally {
                scanning = false
            }
        }
    }

    /** 有课程 id 才跳课程详情；页面里按 courseId 从课程列表找回完整课程。 */
    fun openCourse(entry: CxSignHistoryEntry) {
        if (entry.courseId.isBlank() || openingCourse) return
        openingCourse = true
        pageMessage = null
        scope.launch {
            try {
                val course = repo.courses().firstOrNull { it.courseId == entry.courseId }
                if (course != null) {
                    onOpenCourse(course)
                } else {
                    pageMessage = "没找到这门课程，可能已经结课"
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                pageMessage = e.cxMessage()
            } finally {
                openingCourse = false
            }
        }
    }

    // 从设置页回来时刷新「实时提醒」开关与历史；页面在后台时设置被改也能看到。
    val debuggable = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadLocalState()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (repo.loggedIn) {
            scan()
        } else {
            loggedIn = false
            openSigns = emptyList()
        }
    }

    val current = selected
    if (current != null) {
        CxInboxDetailScreen(
            notice = current,
            courseName = selectedSign?.courseName.orEmpty(),
            activeName = selectedSign?.activeName.orEmpty(),
            onBack = {
                selected = null
                selectedSign = null
                scan()
            },
            onOpenWork = onOpenWork,
            onOpenGesture = onOpenGesture,
        )
        return
    }

    val state = cxSignPageState(
        loggedIn = loggedIn,
        monitorEnabled = monitorEnabled,
        scanning = scanning,
        openCount = openSigns?.size ?: 0,
        scanFailed = scanError != null,
        historyCount = history.size,
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("签到") },
                subtitle = { Text("学习通") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (loggedIn) {
                        IconButton(onClick = ::scan, enabled = !scanning) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        if (state.showLoginRequired) {
            SdgyMessage(
                text = "请先在「我的 → 学习通」登录",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
            )
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = scanning,
            onRefresh = ::scan,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 24.dp + padding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    CxSignMonitorRow(
                        monitorEnabled = monitorEnabled,
                        onOpenSettings = onOpenSettings,
                    )
                }

                restriction?.let { active ->
                    item { CxSignRestrictionBanner(active) }
                }

                item { CxSignSectionTitle("进行中") }

                when (state.listState) {
                    CxSignListState.LIST -> items(openSigns.orEmpty(), key = { "open_${it.activeId}" }) { sign ->
                        val record = store.record(accountKey, sign.activeId, CxSignKind.CHECKIN)
                        CxOpenSignCard(
                            sign = sign,
                            actionLabel = cxSignActionLabel(
                                gesture = sign.gesture,
                                autoEnabled = autoCheckin,
                                recordStatus = record?.status,
                            ),
                            onClick = {
                                if (sign.gesture) {
                                    onOpenGesture(
                                        sign.activeId,
                                        sign.courseId,
                                        sign.clazzId,
                                        sign.courseName,
                                        sign.activeName,
                                    )
                                } else {
                                    selectedSign = sign
                                    selected = sign.asInboxNotice(accountKey)
                                }
                            },
                            onExpire = { recordEnded(sign) },
                        )
                    }

                    CxSignListState.LOADING -> item { CxSignLoadingRow() }

                    // 受限时横幅已经说明原因，不能再叠「暂无进行中的签到」误导用户。
                    CxSignListState.EMPTY -> if (restriction == null) item {
                        Column {
                            Text(
                                text = state.openEmptyText.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            // 只在 debug 构建显示：不接 logcat 也能判断扫描到底跑没跑。
                            if (debuggable) {
                                scanSummary?.let { summary ->
                                    Text(
                                        text = "已扫 ${summary.coursesScanned} 门课，" +
                                            "最近扫描时间 ${formatScanStamp(summary.finishedAtMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }

                    CxSignListState.NEEDS_LOGIN -> Unit
                }

                scanError?.let { text ->
                    if (state.listState == CxSignListState.LIST) {
                        item {
                            Text(
                                "签到扫描失败：$text",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }

                item { CxSignSectionTitle("最近记录") }

                if (state.historyEmpty) {
                    item {
                        Text(
                            "还没有签到记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                } else {
                    items(history, key = { "history_${it.activeId}_${it.kind.name}_${it.result.name}" }) { entry ->
                        CxSignHistoryCard(
                            entry = entry,
                            onClick = { openCourse(entry) },
                        )
                    }
                }

                pageMessage?.let { text ->
                    item {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** debug 摘要里的扫描时刻：`HH:mm:ss`。 */
private fun formatScanStamp(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(ms))

/** 实时提醒状态条：开着只报状态，关着给「去开启」直通设置页。 */
@Composable
private fun CxSignMonitorRow(monitorEnabled: Boolean, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "签到实时提醒：${if (monitorEnabled) "开" else "关"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (monitorEnabled) {
                        "发现签到会提醒你；自动签到是否提交在「自动签到」里设置"
                    } else {
                        "现在只会在本页手动查看，发现签到不会提醒"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!monitorEnabled) {
                TextButton(onClick = onOpenSettings) { Text("去开启") }
            }
        }
    }
}

@Composable
private fun CxSignSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun CxSignLoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Text(
            "正在获取签到…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 最近记录的一行：成功/失败用不同颜色区分，有课程 id 才可点回课程详情。 */
@Composable
private fun CxSignHistoryCard(entry: CxSignHistoryEntry, onClick: () -> Unit) {
    val clickable = entry.courseId.isNotBlank()
    val resultColor = when (entry.result) {
        CxSignHistoryResult.SUCCESS -> MaterialTheme.colorScheme.primary
        CxSignHistoryResult.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        onClick = onClick,
        enabled = clickable,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            // 不可点击只是没有课程 id，不代表内容是「失效」的，别把字变灰。
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(entry.kind.label)
                        append(" · ")
                        append(entry.result.label)
                        if (entry.detail.isNotBlank()) {
                            append(" · ")
                            append(entry.detail.take(40))
                        }
                        append(" · ")
                        append(formatCxSignTime(entry.atMs))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = resultColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (clickable) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}