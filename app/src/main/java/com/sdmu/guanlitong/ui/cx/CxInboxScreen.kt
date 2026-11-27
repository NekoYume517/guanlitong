package com.sdmu.guanlitong.ui.cx

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.sdmu.guanlitong.data.cx.CxNoticeEntity
import com.sdmu.guanlitong.data.cx.CxNoticeRunner
import com.sdmu.guanlitong.data.cx.CxNoticeStore
import com.sdmu.guanlitong.data.cx.CxOpenSign
import com.sdmu.guanlitong.data.cx.CxRemoteNoticeGateway
import com.sdmu.guanlitong.data.cx.CxRemoteSignGateway
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxSignRestrictedException
import com.sdmu.guanlitong.data.cx.CxSignRestriction
import com.sdmu.guanlitong.data.cx.CxSignScanner
import com.sdmu.guanlitong.data.cx.CxSignStore
import com.sdmu.guanlitong.data.cx.cxMessage
import com.sdmu.guanlitong.data.cx.inboxTypeLabel
import com.sdmu.guanlitong.data.cx.restrictionBannerText
import com.sdmu.guanlitong.data.cx.signAccountKey
import com.sdmu.guanlitong.data.cx.successfulCheckinIds
import com.sdmu.guanlitong.service.CxNotifySchedule
import com.sdmu.guanlitong.ui.sdgy.SdgyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "CxInboxScreen"

/**
 * 学习通收件箱：按时间倒序、未读高亮、顶部手动刷新。
 *
 * 点击条目即标本地已读并进详情；详情按类型路由（签到/作业/普通链接）。
 * 后台 30 分钟同步由 [CxNotifySchedule] 负责，手动刷新直接跑一次 [CxNoticeRunner]，
 * 不产生系统通知。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CxInboxScreen(
    onBack: () -> Unit,
    onOpenWork: (actionUrl: String, title: String) -> Unit,
    onOpenGesture: (
        activeId: String,
        courseId: String,
        classId: String,
        courseName: String,
        activeName: String,
    ) -> Unit = { _, _, _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(context) { CxRepository(context.applicationContext) }
    val store = remember(context) { CxNoticeStore(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var loggedIn by remember { mutableStateOf(repo.loggedIn) }
    var accountKey by remember { mutableStateOf("") }
    var notices by remember { mutableStateOf<List<CxNoticeEntity>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CxNoticeEntity?>(null) }
    // 详情从置顶签到点进来时记下名称，手划页写「最近记录」要用。
    var selectedSign by remember { mutableStateOf<CxOpenSign?>(null) }

    // 置顶的「正在进行的签到」：与通知列表并行扫描，谁先回谁先显示。
    val signGateway = remember(repo) { CxRemoteSignGateway(repo) }
    val signScanner = remember(signGateway) { CxSignScanner(signGateway) }
    val signStore = remember(context) { CxSignStore(context.applicationContext) }
    var openSigns by remember { mutableStateOf<List<CxOpenSign>?>(null) }
    var signScanning by remember { mutableStateOf(false) }
    var signScanError by remember { mutableStateOf<String?>(null) }
    var signRestriction by remember { mutableStateOf<CxSignRestriction?>(null) }

    fun scanSigns() {
        if (signScanning || !repo.loggedIn) return
        // 先置位再 launch，连点/进入时通知刷新同时触发也只扫一次。
        signScanning = true
        signScanError = null
        scope.launch {
            try {
                if (accountKey.isBlank()) accountKey = repo.withSession { signAccountKey(it) }
                // 受限期间不发任何 activelist 请求，等解除时间过了再扫。
                signStore.restriction(accountKey, System.currentTimeMillis())?.let { active ->
                    signRestriction = active
                    if (openSigns == null) openSigns = emptyList()
                    Log.i("CxInboxScreen", "sign scan skipped: restricted until=${active.untilMs}")
                    return@launch
                }
                // 已签到成功的活动服务端仍返回 status=1，靠本地成功记录排掉。
                val succeeded = successfulCheckinIds(signStore.history(accountKey))
                openSigns = signScanner.scan(excludeActiveIds = succeeded)
                signRestriction = null
                signStore.clearRestriction(accountKey)
            } catch (e: CxSignRestrictedException) {
                signStore.saveRestriction(accountKey, e.restriction)
                signRestriction = e.restriction
                Log.w("CxInboxScreen", "sign scan restricted until=${e.restriction.untilMs}")
                if (openSigns == null) openSigns = emptyList()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "签到扫描失败", e)
                signScanError = e.cxMessage()
                if (openSigns == null) openSigns = emptyList()
            } finally {
                signScanning = false
            }
        }
    }

    fun refresh() {
        // 进入/下拉时签到扫描与通知刷新并行发起，签到不用等通知列表。
        scanSigns()
        if (refreshing) return
        scope.launch {
            refreshing = true
            error = null
            try {
                val key = repo.withSession { signAccountKey(it) }
                accountKey = key
                loggedIn = true
                Log.i(TAG, "收件箱刷新：key=$key")
                val added = CxNoticeRunner(CxRemoteNoticeGateway(repo), store, key).poll()
                val cached = store.notices(key)
                Log.i(TAG, "收件箱刷新完成：key=$key 新增 ${added.size} 条，本地 ${cached.size} 条")
                notices = cached
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "收件箱刷新失败 key=${accountKey.ifBlank { "未知" }}", e)
                error = e.cxMessage()
                // 失败不清缓存：先算出本账号的 key（会话取不到时退回已记下的），
                // 本地有列表就继续展示并只留顶部小字提示；真的空才进中央失败态。
                val key = accountKey.ifBlank {
                    runCatching { repo.withSession { signAccountKey(it) } }.getOrNull().orEmpty()
                }
                if (key.isNotBlank()) accountKey = key
                val cached = if (key.isNotBlank()) {
                    runCatching { store.notices(key) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                if (cached.isNotEmpty()) {
                    notices = cached
                } else if (notices.isNullOrEmpty()) {
                    notices = emptyList()
                }
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        CxNotifySchedule.ensure(context)
        if (repo.loggedIn) {
            refresh()
        } else {
            notices = emptyList()
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
                // 详情里刚手动签过：回列表重扫一次，成功的活动会立刻从置顶消失。
                scanSigns()
            },
            onOpenWork = onOpenWork,
            onOpenGesture = onOpenGesture,
        )
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("收件箱") },
                subtitle = { Text("学习通") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (loggedIn) {
                        IconButton(onClick = { refresh() }, enabled = !refreshing) {
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
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            val list = notices
            val signs = openSigns.orEmpty()
            when {
                !loggedIn -> SdgyMessage(
                    text = "请先在「我的 → 学习通」登录",
                    modifier = Modifier.fillMaxSize(),
                )

                list == null && signs.isEmpty() && signScanError == null &&
                    signRestriction == null -> CxColdStartLoading(
                    text = "正在同步收件箱",
                    resetKey = refreshing,
                    onRetry = { refresh() },
                )

                list.isNullOrEmpty() && signs.isEmpty() -> SdgyMessage(
                    text = error ?: signScanError
                        ?: signRestriction?.let(::restrictionBannerText)
                        ?: "收件箱里还没有消息",
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { refresh() },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 24.dp + padding.calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    signRestriction?.let { active ->
                        item { CxSignRestrictionBanner(active) }
                    }
                    if (signs.isNotEmpty()) {
                        item {
                            Text(
                                "正在进行的签到",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        items(signs, key = { "sign_${it.activeId}" }) { sign ->
                            CxOpenSignCard(
                                sign = sign,
                                actionLabel = if (sign.gesture) "点击手划签到" else "点击去处理",
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
                            )
                        }
                        item {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                    signScanError?.let { text ->
                        item {
                            Text(
                                "签到扫描失败：$text",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                    if (refreshing || list == null) {
                        item {
                            Text(
                                if (list == null) "正在同步收件箱…" else "正在刷新…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                    error?.let { text ->
                        item {
                            Text(
                                "刷新失败：$text",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                    if (list.isNullOrEmpty()) {
                        item {
                            Text(
                                "收件箱里还没有消息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    } else {
                        items(list.sortedByDescending { it.timeMs }, key = { it.id }) { notice ->
                            CxInboxCard(
                                notice = notice,
                                onClick = {
                                    store.markRead(accountKey, notice.id)
                                    notices = notices?.map {
                                        if (it.id == notice.id) it.copy(localRead = true) else it
                                    }
                                    selectedSign = null
                                    selected = notice.copy(localRead = true)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CxInboxCard(notice: CxNoticeEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (notice.localRead) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!notice.localRead) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = notice.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (notice.localRead) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (notice.content.isNotBlank()) {
                    Text(
                        text = notice.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = "${inboxTypeLabel(notice.ntype)} · ${formatNoticeTime(notice.timeMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun formatNoticeTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))