package com.sdmu.guanlitong.ui.cx

import android.Manifest
import android.os.Build
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.cx.CxChapterContent
import com.sdmu.guanlitong.data.cx.CxChildContent
import com.sdmu.guanlitong.data.cx.CxCourse
import com.sdmu.guanlitong.data.cx.CxCourseBrowser
import com.sdmu.guanlitong.data.cx.CxCourseSnapshot
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxTaskCard
import com.sdmu.guanlitong.data.cx.cxMessage
import com.sdmu.guanlitong.data.cx.cxTypeLabel
import com.sdmu.guanlitong.data.cx.displayName
import com.sdmu.guanlitong.data.cx.isBrushable
import com.sdmu.guanlitong.data.cx.pendingBrushableTasks
import com.sdmu.guanlitong.service.CxTaskService
import com.sdmu.guanlitong.service.CxTaskState
import com.sdmu.guanlitong.service.CxTaskStatus
import com.sdmu.guanlitong.ui.sdgy.SdgyMessage
import com.sdmu.guanlitong.ui.sdgy.hasPermission
import com.sdmu.guanlitong.ui.sdgy.rememberPermissionRequester
import kotlinx.coroutines.CancellationException

/**
 * 学习通课程详情：章节树 + 任务点状态。
 *
 * 「一键刷完本课程」与单个任务点的「刷」都交给 [CxTaskService] 前台服务执行；
 * 页面只观察 [CxTaskStatus] 显示进度、预计剩余时间与取消按钮。
 *
 * 统计只把 video / audio 当作可自动完成的任务点；文档、作业、测验等一律显示
 * 「需手动完成」——所以「音视频刷完」与「课程学完」是两句话，不能混说。
 * 读取失败的章节单独标红，未通过数按「可能不全」呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CxCourseDetailScreen(
    course: CxCourse?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(context) { CxRepository(context.applicationContext) }
    val browser = remember(repo) { CxCourseBrowser(repo) }

    var snapshot by remember { mutableStateOf<CxCourseSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    val loading = snapshot?.loading == true
    val taskState by CxTaskStatus.state.collectAsState()
    val requestPermission = rememberPermissionRequester { }

    // 流式加载：骨架先出，卡片逐章填；refreshKey 变化（刷新/重试）才强制重扫。
    LaunchedEffect(course, refreshKey) {
        val target = course ?: return@LaunchedEffect
        error = null
        snapshot = null
        try {
            browser.load(target, forceReload = refreshKey > 0) { snap -> snapshot = snap }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.cxMessage()
        }
    }

    fun startStudy(jobid: String? = null) {
        val target = course ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
        CxTaskService.startStudy(context, target, jobid)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(course?.name ?: "课程详情") },
                subtitle = { Text("学习通") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!loading) {
                        IconButton(onClick = { refreshKey++ }) {
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
        if (course == null) {
            SdgyMessage(
                text = "课程信息已丢失，请返回重新打开",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            val snap = snapshot
            when {
                snap == null && error == null -> CxColdStartLoading(
                    text = "正在读取课程",
                    resetKey = refreshKey,
                    onRetry = { refreshKey++ },
                )

                snap == null -> SdgyMessage(
                    text = error.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { refreshKey++ },
                )

                snap.total == 0 && !snap.loading -> SdgyMessage(
                    text = "这门课还没有可读取的章节",
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { refreshKey++ },
                )

                else -> ChapterList(
                    snapshot = snap,
                    taskState = taskState,
                    error = error,
                    onStartAll = { startStudy() },
                    onStartTask = { card -> startStudy(card.jobid) },
                    onCancel = { CxTaskService.cancel(context) },
                    bottomPadding = padding.calculateBottomPadding(),
                )
            }
        }
    }
}

@Composable
private fun ChapterList(
    snapshot: CxCourseSnapshot,
    taskState: CxTaskState,
    error: String?,
    onStartAll: () -> Unit,
    onStartTask: (CxTaskCard) -> Unit,
    onCancel: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val groups = snapshot.groups
    val allCards = groups.flatMap { group -> group.children.flatMap { it.cards } }
    val pendingBrushable = allCards.pendingBrushableTasks()
    val pendingManual = allCards.filter { !it.isPassed && !it.isBrushable }
    val failedChapters = groups.flatMap { it.children }.filter { it.error != null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 24.dp + bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TaskHeader(
                state = taskState,
                snapshot = snapshot,
                pendingBrushable = pendingBrushable,
                pendingManualCount = pendingManual.size,
                failedChapters = failedChapters,
                onStartAll = onStartAll,
                onCancel = onCancel,
            )
        }

        error?.let { text ->
            item {
                Text(
                    "部分章节读取失败：$text",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        groups.forEachIndexed { groupIndex, group ->
            item(key = "group-$groupIndex-${group.groupName}") {
                Text(
                    group.groupName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            group.children.forEachIndexed { childIndex, child ->
                item(key = "child-$groupIndex-$childIndex") {
                    ChildCard(child = child, onStartTask = onStartTask)
                }
            }
        }

        if (snapshot.loading && snapshot.remaining > 0) {
            item(key = "loading-footer") {
                Text(
                    "剩余 ${snapshot.remaining} 章读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TaskHeader(
    state: CxTaskState,
    snapshot: CxCourseSnapshot,
    pendingBrushable: List<CxTaskCard>,
    pendingManualCount: Int,
    failedChapters: List<CxChildContent>,
    onStartAll: () -> Unit,
    onCancel: () -> Unit,
) {
    val pendingVideo = pendingBrushable.count { it.type == "video" }
    val pendingAudio = pendingBrushable.count { it.type == "audio" }
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (snapshot.loading) {
                Text(
                    buildString {
                        append("正在读取章节：已加载 ${snapshot.loaded}/${snapshot.total}")
                        if (snapshot.remaining > 0) append("，还有 ${snapshot.remaining} 章")
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                LinearProgressIndicator(
                    progress = {
                        if (snapshot.total <= 0) 0f
                        else snapshot.loaded.toFloat() / snapshot.total
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.running) {
                Text(
                    "${state.phase} ${state.currentIndex}/${state.total}".trim(),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.duration > 0) {
                    LinearProgressIndicator(
                        progress = { state.taskPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${state.currentTask} · ${state.taskPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val remainMinutes =
                        ((state.duration - state.position) / 120.0).let { if (it < 1) 1 else it.toInt() }
                    Text(
                        "预计剩余约 $remainMinutes 分钟（2 倍速）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        state.message.ifBlank { "正在准备…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) { Text("取消") }
            } else {
                if (!snapshot.loading) {
                    Text(
                        when {
                            pendingBrushable.isNotEmpty() ->
                                "还有 ${pendingBrushable.size} 个未通过的音视频任务点"
                            failedChapters.isNotEmpty() ->
                                "音视频任务点已扫到的都通过了（有章节未读到）"
                            pendingManualCount > 0 -> "音视频任务点已全部通过"
                            else -> "本课程全部任务点已完成"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (pendingBrushable.isNotEmpty() && (pendingVideo > 0 || pendingAudio > 0)) {
                    Text(
                        "视频 $pendingVideo 个 · 音频 $pendingAudio 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pendingManualCount > 0) {
                    Text(
                        "另有 $pendingManualCount 个文档/作业等任务点需手动完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (failedChapters.isNotEmpty()) {
                    Text(
                        "有 ${failedChapters.size} 章读取失败，" +
                            "未通过统计可能不全：${failedChapters.joinToString("、") { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.message.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.fatalError != null || state.incomplete != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Button(
                    onClick = onStartAll,
                    enabled = pendingBrushable.isNotEmpty() && !state.running && !snapshot.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("一键刷完本课程", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun ChildCard(child: CxChildContent, onStartTask: (CxTaskCard) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                child.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (child.loading) {
                Text(
                    "正在读取任务点…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (child.error != null) {
                Text(
                    "本章读取失败，任务点未知：${child.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (child.cards.isEmpty()) {
                Text(
                    "本章没有任务点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            child.cards.forEach { card ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (card.isPassed) Icons.Outlined.CheckCircle else Icons.Outlined.PlayCircle,
                        contentDescription = null,
                        tint = if (card.isPassed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            card.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(if (card.isPassed) "已通过" else "未通过")
                                append(" · ")
                                append(cxTypeLabel(card.type))
                                val seconds = card.attDuration ?: card.duration
                                if (seconds > 0) {
                                    append(" · ")
                                    append(seconds / 60)
                                    append(" 分 ")
                                    append(seconds % 60)
                                    append(" 秒")
                                }
                                if (!card.isPassed && !card.isBrushable) append(" · 需手动完成")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (card.isPassed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (!card.isPassed && card.isBrushable) {
                        // 单节播放：保持本页 Outlined 图标风格；IconButton 自带 48dp 触控区。
                        // 禁用逻辑不变——仍由外面「未通过且可刷」这个条件控制是否出现。
                        IconButton(onClick = { onStartTask(card) }) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = "播放本节",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
