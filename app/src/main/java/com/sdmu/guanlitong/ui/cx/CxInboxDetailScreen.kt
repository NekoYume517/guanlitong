package com.sdmu.guanlitong.ui.cx

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.cx.CxApi
import com.sdmu.guanlitong.data.cx.CxCheckinApi
import com.sdmu.guanlitong.data.cx.CxInboxAction
import com.sdmu.guanlitong.data.cx.CxNoticeEntity
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxSignResult
import com.sdmu.guanlitong.data.cx.CxSignHistoryResult
import com.sdmu.guanlitong.data.cx.CxSignKind
import com.sdmu.guanlitong.data.cx.CxSignRecord
import com.sdmu.guanlitong.data.cx.CxSignRecordStatus
import com.sdmu.guanlitong.data.cx.CxSignStore
import com.sdmu.guanlitong.data.cx.signAccountKey
import com.sdmu.guanlitong.data.cx.signResultHistory
import com.sdmu.guanlitong.data.cx.cxMessage
import com.sdmu.guanlitong.data.cx.inboxAction
import com.sdmu.guanlitong.data.cx.inboxTypeLabel
import com.sdmu.guanlitong.data.cx.parseSignTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 收件箱详情：标题 / 来源 / 时间 / 正文 / 链接，并按类型路由。
 *
 * - 签到（sourceType=2 或 actionUrl 含 sign）：展示活动信息 +「点击签到」，点了才提交；
 *   开了自动签到就只展示后台处理状态，不再要求手点。
 * - 作业：交给作业答题页（[onOpenWork] 由集成轮接路由）。
 * - 其它：只读 + 打开链接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CxInboxDetailScreen(
    notice: CxNoticeEntity,
    /** 从签到卡片进来时带上名称，手划页写「最近记录」用；普通通知为空。 */
    courseName: String = "",
    activeName: String = "",
    onBack: () -> Unit,
    onOpenWork: (actionUrl: String, title: String) -> Unit,
    /** 手势签到兜底：能从头链接里解析出活动/课程/班级时才提供入口。 */
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
    val scope = rememberCoroutineScope()

    val action = remember(notice.ntype, notice.actionUrl) {
        inboxAction(notice.ntype, notice.actionUrl)
    }
    // 自动签到开关由签到设置页维护；开了就交给后台，详情页不再要求手点。
    val autoCheckin = remember(context, notice.id) {
        CxSignStore(context.applicationContext).settings().checkinEnabled
    }
    var signing by remember { mutableStateOf(false) }
    var signMessage by remember { mutableStateOf<String?>(null) }
    // 这条消息里能识别出的签到目标；缺课程/班级/活动 id 时为 null。
    val signTarget = remember(notice.actionUrl) { parseSignTarget(notice.actionUrl) }
    // 结果明确指出「要当场手划」才给手势页入口；二维码、拍照等其它手动类型不冒充手势。
    var needGesture by remember { mutableStateOf(false) }

    fun doSign() {
        if (signing) return
        val target = signTarget
        if (target == null || target.activeId.isBlank()) {
            signMessage = "这条消息里没有可识别的签到活动，请到课程里签到"
            return
        }
        scope.launch {
            signing = true
            signMessage = null
            needGesture = false
            try {
                val result = repo.withSession { session ->
                    val api = CxCheckinApi(CxApi(session.cookies), session.uid)
                    val actives = api.activeList(target.courseId, target.clazzId)
                    // 只认 actionUrl 里那一条活动：拿不到 id 时宁可不签，也不能签错活动。
                    val active = actives.firstOrNull {
                        it.isCheckin && it.id == target.activeId
                    }
                    val outcome = if (active == null) {
                        CxSignResult(false, "没有找到这条签到活动，可能已结束")
                    } else {
                        val settings = CxSignStore(context.applicationContext).settings()
                        api.checkin(
                            active = active,
                            courseId = target.courseId,
                            clazzId = target.clazzId,
                            settingsLat = settings.seedLat,
                            settingsLng = settings.seedLng,
                            settingsAddress = settings.address,
                        )
                    }
                    // 手动签到与自动签到共用同一套台账/历史写回：成功同时落 SUCCESS 台账
                    // （后续扫描据此把这条从「进行中」排掉），并把服务端附言带进最近记录。
                    val key = signAccountKey(session)
                    val signStore = CxSignStore(context.applicationContext)
                    val now = System.currentTimeMillis()
                    when {
                        outcome.success -> {
                            signStore.save(
                                key,
                                target.activeId,
                                CxSignKind.CHECKIN,
                                CxSignRecord(CxSignRecordStatus.SUCCESS, attempts = 1, atMs = now),
                            )
                            signStore.appendHistory(
                                key,
                                signResultHistory(
                                    activeId = target.activeId,
                                    kind = CxSignKind.CHECKIN,
                                    result = CxSignHistoryResult.SUCCESS,
                                    atMs = now,
                                    courseName = courseName,
                                    activeName = activeName.ifBlank { active?.name.orEmpty() },
                                    courseId = target.courseId,
                                    clazzId = target.clazzId,
                                    detail = outcome.message,
                                ),
                            )
                        }

                        outcome.needManual -> signStore.appendHistory(
                            key,
                            signResultHistory(
                                activeId = target.activeId,
                                kind = CxSignKind.CHECKIN,
                                result = CxSignHistoryResult.MANUAL,
                                atMs = now,
                                courseName = courseName,
                                activeName = activeName.ifBlank { active?.name.orEmpty() },
                                courseId = target.courseId,
                                clazzId = target.clazzId,
                                detail = outcome.message,
                            ),
                        )

                        else -> signStore.appendHistory(
                            key,
                            signResultHistory(
                                activeId = target.activeId,
                                kind = CxSignKind.CHECKIN,
                                result = CxSignHistoryResult.FAILED,
                                atMs = now,
                                courseName = courseName,
                                activeName = activeName.ifBlank { active?.name.orEmpty() },
                                courseId = target.courseId,
                                clazzId = target.clazzId,
                                detail = outcome.message,
                            ),
                        )
                    }
                    outcome
                }
                signMessage = result.message
                needGesture = result.needManual && result.message.contains("手势")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val message = e.cxMessage()
                signMessage = message
                // 网络/登录异常也留一条失败记录（尽力而为，取不到会话就算了）。
                runCatching {
                    repo.withSession { session ->
                        CxSignStore(context.applicationContext).appendHistory(
                            signAccountKey(session),
                            signResultHistory(
                                activeId = target.activeId,
                                kind = CxSignKind.CHECKIN,
                                result = CxSignHistoryResult.FAILED,
                                atMs = System.currentTimeMillis(),
                                courseName = courseName,
                                activeName = activeName,
                                courseId = target.courseId,
                                clazzId = target.clazzId,
                                detail = message,
                            ),
                        )
                    }
                }
            } finally {
                signing = false
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("消息详情") },
                subtitle = { Text(inboxTypeLabel(notice.ntype)) },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 12.dp,
                bottom = 24.dp + padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(notice.title, style = MaterialTheme.typography.headlineSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${inboxTypeLabel(notice.ntype)} · ${formatTime(notice.timeMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            item { HorizontalDivider() }

            item {
                if (notice.content.isBlank()) {
                    Text(
                        "（这条消息没有正文）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SelectionContainer {
                        Text(notice.content, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            if (!notice.actionUrl.isNullOrBlank()) {
                item {
                    Text(
                        text = notice.actionUrl.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when (action) {
                CxInboxAction.SIGN -> item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("签到", style = MaterialTheme.typography.titleMedium)
                            if (autoCheckin) {
                                Text(
                                    "自动签到已开启，后台会自动处理这条签到，不需要手动点击。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "需要签到时点下面的按钮；能否成功取决于签到方式和位置。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = { doSign() }, enabled = !signing) {
                                    Text(if (signing) "正在签到…" else "点击签到")
                                }
                            }
                            signMessage?.let { message ->
                                Text(message, style = MaterialTheme.typography.bodyMedium)
                            }
                            // 解析不出活动/课程/班级就不显示：宁可不签，也不能随便签一条。
                            if (needGesture && signTarget != null && signTarget.activeId.isNotBlank()) {
                                Button(
                                    onClick = {
                                        onOpenGesture(
                                            signTarget.activeId,
                                            signTarget.courseId,
                                            signTarget.clazzId,
                                            courseName,
                                            activeName,
                                        )
                                    },
                                ) {
                                    Text("去手划签到")
                                }
                            }
                        }
                    }
                }

                CxInboxAction.WORK -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                notice.actionUrl?.let { onOpenWork(it, notice.title) }
                            },
                            enabled = !notice.actionUrl.isNullOrBlank(),
                        ) {
                            Text("去完成作业")
                        }
                        if (notice.actionUrl.isNullOrBlank()) {
                            Text(
                                "这条消息没有作业链接",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                CxInboxAction.LINK -> item {
                    TextButton(onClick = {
                        val url = notice.actionUrl ?: return@TextButton
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }) {
                        Text("用浏览器打开")
                    }
                }

                CxInboxAction.NONE -> Unit
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))