package com.sdmu.guanlitong.ui.cx

import android.Manifest
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.sdmu.guanlitong.data.cx.CxCourse
import com.sdmu.guanlitong.data.cx.CxDocApi
import com.sdmu.guanlitong.data.cx.CxDocCourse
import com.sdmu.guanlitong.data.cx.CxDocFolder
import com.sdmu.guanlitong.data.cx.CxDocItem
import com.sdmu.guanlitong.data.cx.CxDocMeta
import com.sdmu.guanlitong.data.cx.CxDocParams
import com.sdmu.guanlitong.data.cx.CxDocTaskState
import com.sdmu.guanlitong.data.cx.CxDocTaskStatus
import com.sdmu.guanlitong.data.cx.CxRemoteDocGateway
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.cxMessage
import com.sdmu.guanlitong.service.CxDocService
import com.sdmu.guanlitong.ui.sdgy.SdgyMessage
import com.sdmu.guanlitong.ui.sdgy.hasPermission
import com.sdmu.guanlitong.ui.sdgy.rememberPermissionRequester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 课程资料页：目录浏览 + 文件信息 + 倍速刷时长。
 *
 * 目录协议照老项目 `Browser`：根目录用课程 enc，子目录带 dataName/dataId/parent；
 * 文件夹可以进入，面包屑与「上一级」都能回退。点文件只做选择并拉元信息（页数/字数），
 * 点「开始刷时长」才交给 [CxDocService] 跑心跳，进度从 [CxDocTaskStatus] 读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CxMaterialsScreen(
    course: CxCourse?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(context) { CxRepository(context.applicationContext) }
    val gateway = remember(repo) { CxRemoteDocGateway(repo) }
    val scope = rememberCoroutineScope()
    val requestPermission = rememberPermissionRequester { }
    val docState by CxDocTaskStatus.state.collectAsState()

    var params by remember(course) { mutableStateOf<CxDocParams?>(null) }
    var enc by remember(course) { mutableStateOf("") }
    var path by remember(course) { mutableStateOf(emptyList<CxDocItem>()) }
    var items by remember(course) { mutableStateOf<List<CxDocItem>?>(null) }
    var loading by remember(course) { mutableStateOf(true) }
    var error by remember(course) { mutableStateOf<String?>(null) }
    var refreshKey by remember(course) { mutableIntStateOf(0) }

    var selected by remember(course) { mutableStateOf<CxDocItem?>(null) }
    var meta by remember(course) { mutableStateOf<CxDocMeta?>(null) }
    var metaLoading by remember { mutableStateOf(false) }
    var metaError by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableStateOf(1.0) }

    LaunchedEffect(course, refreshKey, path) {
        val current = course ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            val fetched = params ?: gateway.params(
                current.courseId,
                current.clazzId,
                current.personId,
            ).also { params = it }
            // 与老项目一致：页面没有 cpi 时退回 personId，没有 enc 时用兜底常量。
            val cpi = fetched.cpi.ifBlank { current.personId }
            val currentEnc = enc.ifBlank {
                fetched.enc.ifBlank { CxDocApi.DEFAULT_FILE_ENC }
            }
            val folder = path.lastOrNull()?.let {
                CxDocFolder(
                    name = it.name,
                    id = it.id,
                    parentId = path.dropLast(1).lastOrNull()?.id.orEmpty(),
                )
            }
            val page = gateway.list(
                CxDocCourse(
                    courseId = current.courseId,
                    clazzId = current.clazzId,
                    personId = current.personId,
                    cpi = cpi,
                    enc = currentEnc,
                    name = current.name,
                ),
                folder,
            )
            enc = page.enc.ifBlank { currentEnc }
            items = page.items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.cxMessage()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selected) {
        val item = selected ?: run {
            meta = null
            return@LaunchedEffect
        }
        meta = null
        metaError = null
        metaLoading = true
        try {
            meta = gateway.meta(item.objectId.ifBlank { item.id })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            metaError = e.cxMessage()
        } finally {
            metaLoading = false
        }
    }

    fun startBrushing() {
        val current = course ?: return
        val item = selected ?: return
        val p = params ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            requestPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
        CxDocService.start(
            context = context,
            course = current,
            params = p,
            cpi = p.cpi.ifBlank { current.personId },
            enc = enc,
            item = item,
            speed = speed,
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(course?.name?.takeIf { it.isNotBlank() } ?: "课程资料") },
                subtitle = {
                    Text(if (path.isEmpty()) "资料" else path.joinToString(" / ") { it.name })
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }, enabled = !loading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新资料目录")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (course == null) {
                SdgyMessage("没有取到课程信息，请返回重新选课")
                return@Column
            }

            if (docState.running || docState.message.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                DocTaskCard(
                    state = docState,
                    onStop = { CxDocService.stop(context) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (path.isEmpty()) "课程资料" else path.last().name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (path.isNotEmpty()) {
                    TextButton(onClick = {
                        selected = null
                        path = path.dropLast(1)
                    }) {
                        Text("上一级")
                    }
                }
            }

            val list = items
            when {
                list == null && loading -> CxColdStartLoading(
                    text = "正在读取资料目录",
                    resetKey = refreshKey,
                    onRetry = { refreshKey++ },
                )

                list.isNullOrEmpty() && error != null -> SdgyMessage(
                    text = "读取资料失败：$error",
                    onRetry = { refreshKey++ },
                )

                list == null -> Unit

                list.isEmpty() -> SdgyMessage("这个文件夹里没有资料")

                else -> {
                    if (error != null) {
                        Text(
                            "刷新失败：$error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    selected?.let { item ->
                        DocBrushPanel(
                            item = item,
                            meta = meta,
                            metaLoading = metaLoading,
                            metaError = metaError,
                            speed = speed,
                            onSpeedChange = { speed = it },
                            running = docState.running,
                            onStart = { startBrushing() },
                            onClose = { selected = null },
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(list, key = { it.id + "-" + it.name }) { item ->
                            DocItemCard(
                                item = item,
                                selected = selected?.id == item.id,
                                onClick = {
                                    if (item.isFolder) {
                                        selected = null
                                        path = path + item
                                    } else {
                                        selected = item
                                    }
                                },
                                onBrush = { selected = item },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocItemCard(
    item: CxDocItem,
    selected: Boolean,
    onClick: () -> Unit,
    onBrush: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (item.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (item.isFolder) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildString {
                    if (item.isFolder) append("文件夹") else append(item.type.ifBlank { "文件" })
                    if (item.size.isNotBlank()) append(" · ").append(item.size)
                    if (item.creator.isNotBlank()) append(" · ").append(item.creator)
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!item.isFolder) {
                TextButton(onClick = onBrush) { Text("刷时长") }
            }
        }
    }
}

@Composable
private fun DocBrushPanel(
    item: CxDocItem,
    meta: CxDocMeta?,
    metaLoading: Boolean,
    metaError: String?,
    speed: Double,
    onSpeedChange: (Double) -> Unit,
    running: Boolean,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onClose) { Text("取消") }
            }
            val info = when {
                metaLoading -> "正在读取文件信息…"
                metaError != null -> "读不到文件信息（$metaError），仍可尝试刷时长"
                meta != null && meta.pageNum > 0 -> {
                    val pages = meta.pageNum
                    val words = meta.wordCount.takeIf { it > 0 }?.toString() ?: "未知"
                    "页数 $pages · 字数 $words"
                }
                else -> "学习通没有返回页数，按默认 100 页上报"
            }
            Text(
                info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "倍速只改变上报的阅读时间，不会真的读得更快。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { value ->
                    FilterChip(
                        selected = speed == value,
                        onClick = { onSpeedChange(value) },
                        label = { Text(speedLabel(value)) },
                        enabled = !running,
                    )
                }
            }
            Button(
                onClick = onStart,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (running) "正在刷时长" else "开始刷时长")
            }
        }
    }
}

@Composable
private fun DocTaskCard(state: CxDocTaskState, onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.fatalError != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (state.running) "正在刷《${state.fileName}》" else "上一次刷时长",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                buildString {
                    append(state.message.ifBlank { "准备中" })
                    if (state.running && state.pageNum > 0) {
                        append(" · 进度 ${state.height}/${state.maxHeight}")
                    }
                    if (state.speed > 0) append(" · ${speedLabel(state.speed)}")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.running && state.maxHeight > 0) {
                LinearProgressIndicator(
                    progress = { state.heightPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.running) {
                TextButton(onClick = onStop) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("停止刷时长")
                }
            }
        }
    }
}

private val SPEEDS = listOf(1.0, 10.0, 60.0, 600.0)

private fun speedLabel(speed: Double): String =
    if (speed == speed.toLong().toDouble()) "${speed.toLong()}x"
    else String.format(Locale.CHINA, "%.1fx", speed)