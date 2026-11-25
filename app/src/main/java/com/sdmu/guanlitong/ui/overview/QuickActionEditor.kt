package com.sdmu.guanlitong.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.FeatureCatalog
import com.sdmu.guanlitong.data.QuickActionStore
import com.sdmu.guanlitong.data.QuickActionStore.Companion.MAX_ACTIONS
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.ServiceFeature
import com.sdmu.guanlitong.data.sdgy.SdgyAppGroup
import androidx.compose.runtime.key
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableRow

/**
 * 候选入口只给「已经登录的服务」；
 * 已添加到快捷区的卡片不受影响，退出登录后仍保留原绑定。
 */
private fun ServiceFeature.availableFor(loggedIn: Set<String>): Boolean = when {
    grabSourceOnly -> "grab" in loggedIn
    // 教务功能官方与抢课助手共用，任一已登录的教务账号都能打开（数据来源在教务页切换）。
    serviceId == "jwxt" -> "jwxt" in loggedIn || "grab" in loggedIn
    else -> serviceId in loggedIn
}

/**
 * 快捷功能编辑页。已选区固定在顶部，候选区独立滚动；排序期间不响应候选区滚轮/拖动。
 * ReorderableRow 只在松手后提交实际顺序，邻居卡片由库内的弹簧动画让位。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QuickActionEditorSheet(
    store: QuickActionStore,
    widgetId: String,
    focusId: String?,
    selectedFeatures: List<ServiceFeature>,
    sdgyGroups: List<SdgyAppGroup>,
    /** 已登录的服务 ID；候选区只展示已登录服务的入口。 */
    loggedInServices: Set<String>,
    onRefreshSdgy: suspend () -> List<SdgyAppGroup>,
    onDismiss: () -> Unit,
) {
    val idsByWidget by store.idsByWidget.collectAsState()
    val storedIds = idsByWidget[widgetId] ?: store.featureIds(widgetId)
    val context = androidx.compose.ui.platform.LocalContext.current
    val cachedGroups = remember { com.sdmu.guanlitong.data.sdgy.SdgyAppsStore.load(context)?.groups.orEmpty() }
    val availableGroups = sdgyGroups.ifEmpty { cachedGroups }
    var ids by remember(widgetId) { mutableStateOf(storedIds) }
    var isReordering by remember { mutableStateOf(false) }
    // 拖拽松手后排序库还要播放让位动画，onSettle 在动画结束才回调；
    // 这段时间点「完成」不能直接把编辑页关掉，否则排序会丢。
    var awaitingSettle by remember(widgetId) { mutableStateOf(false) }
    var pendingDismiss by remember(widgetId) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    val features = ids.mapNotNull { id ->
        FeatureCatalog.byId(id) ?: selectedFeatures.firstOrNull { it.id == id }
            ?: findSdgyFeature(id, availableGroups)
    }
    val allCandidates = ServiceCatalog.featureServices.flatMap { service ->
        if (service.id == "sdgy") {
            availableGroups.flatMap { it.apps }.map(FeatureCatalog::sdgyApp) +
                FeatureCatalog.forService(service.id)
        } else {
            FeatureCatalog.forService(service.id)
        }
    }.distinctBy { it.id }.filter { it.availableFor(loggedInServices) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        // 山管未登录、服务器关机或断网时只保留本地候选；
        // 异常如果抛进组合协程会直接闪退，这里必须吞掉。
        try {
            onRefreshSdgy()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 忽略：候选区继续使用缓存与静态入口。
        }
    }

    LaunchedEffect(widgetId, storedIds) { ids = storedIds }
    LaunchedEffect(pendingDismiss, awaitingSettle) {
        if (!pendingDismiss) return@LaunchedEffect
        // onSettle 会把 awaitingSettle 置回 false 并重启本效果；最多等 800ms 兜底。
        if (awaitingSettle) delay(800)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isReordering) pendingDismiss = true },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "编辑快捷功能",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "${features.size}/$MAX_ACTIONS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { if (!isReordering) pendingDismiss = true }) { Text("完成") }
            }

            Spacer(Modifier.height(18.dp))
            if (features.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("添加快捷功能") }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    // ReorderableItem 是自定义子 Layout，weight 不会传给外层 Row，必须显式给槽宽。
                    val gap = 8.dp
                    val tileWidth = (maxWidth - gap * (features.size - 1)) / features.size
                    ReorderableRow(
                        list = features,
                        onSettle = { from, to ->
                            // features 会过滤掉暂未解析的山管入口，不能把它的索引直接
                            // 套到完整 ids 上；按功能 ID 找到真实位置再移动。
                            val movingId = features.getOrNull(from)?.id
                            val targetId = features.getOrNull(to)?.id
                            val fromIndex = movingId?.let(ids::indexOf) ?: -1
                            val toIndex = targetId?.let(ids::indexOf) ?: -1
                            if (fromIndex >= 0 && toIndex >= 0) {
                                val next = ids.toMutableList().apply {
                                    add(toIndex, removeAt(fromIndex))
                                }
                                ids = next
                                store.setOrder(widgetId, next)
                            }
                            awaitingSettle = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) { _, feature, isDragging ->
                        key(feature.id) {
                            ReorderableItem {
                                EditorSelectedTile(
                                    feature = feature,
                                    highlighted = feature.id == focusId,
                                    elevated = isDragging,
                                    modifier = Modifier
                                        .width(tileWidth)
                                        .longPressDraggableHandle(
                                            interactionSource = remember {
                                                androidx.compose.foundation.interaction.MutableInteractionSource()
                                            },
                                            onDragStarted = {
                                                isReordering = true
                                                awaitingSettle = false
                                                pendingDismiss = false
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragStopped = {
                                                isReordering = false
                                                awaitingSettle = true
                                            },
                                        ),
                                    onRemove = {
                                        ids = ids - feature.id
                                        store.remove(widgetId, feature.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("搜索功能") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清空搜索") } }
                } else null,
                singleLine = true,
                shape = CircleShape,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                userScrollEnabled = !isReordering,
                contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val keyword = query.trim()
                ServiceCatalog.featureServices.forEach { service ->
                    val staticFeatures = FeatureCatalog.forService(service.id)
                        .filter { it.availableFor(loggedInServices) }
                    val sourceGroups = if (service.id == "sdgy") {
                        val seen = mutableSetOf<String>()
                        fun deduplicate(features: List<ServiceFeature>) = features.filter { seen.add(it.id) }
                        val official = availableGroups.map { group ->
                            val dynamic = group.apps
                                .filter { it.nativePage != null || FeatureCatalog.byId("sdgy.app.${it.id}") == null }
                                .map(FeatureCatalog::sdgyApp)
                                .filter { it.availableFor(loggedInServices) }
                            group.name to deduplicate(staticFeatures + dynamic)
                        }
                        val known = availableGroups.flatMap { it.apps }
                            .map { FeatureCatalog.sdgyApp(it).id }.toSet()
                        official + listOf(
                            "其他入口" to deduplicate(staticFeatures.filter { it.id !in known }),
                        )
                    } else {
                        listOf("全部功能" to staticFeatures)
                    }
                    val visibleGroups = sourceGroups.map { (title, candidates) ->
                        title to candidates.filter { feature ->
                            keyword.isEmpty() || listOf(
                                feature.title, feature.subtitle, service.name, title,
                            ).any { it.contains(keyword, ignoreCase = true) }
                        }
                    }.filter { it.second.isNotEmpty() }
                    if (visibleGroups.isEmpty()) return@forEach
                    item(key = "source-${service.id}") {
                        Text(
                            service.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    // 分组标题可能重复（服务端分组名重复或与「其他入口」同名），
                    // LazyColumn 的 key 重复会直接崩溃，所以用序号而不是标题做 key。
                    visibleGroups.forEachIndexed { groupIndex, (groupName, candidates) ->
                        item(key = "group-${service.id}-$groupIndex") {
                            Text(
                                groupName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(candidates, key = { it.id }) { candidate ->
                            EditorCandidateRow(
                                feature = candidate,
                                selected = candidate.id in ids,
                                canAdd = ids.size < MAX_ACTIONS,
                                onClick = {
                                    // 焦点绑定稳定的功能 ID，不绑定列表位置；移除或排序后不能误改另一个入口。
                                    val replaceIndex = focusId?.let { ids.indexOf(it) }?.takeIf { it >= 0 }
                                    if (replaceIndex != null) {
                                        if (candidate.id == ids[replaceIndex]) {
                                            // 点击当前入口只是关闭焦点，不改变绑定。
                                        } else if (candidate.id !in ids) {
                                            val previousId = ids[replaceIndex]
                                            ids = ids.toMutableList().apply { this[replaceIndex] = candidate.id }
                                            store.replace(widgetId, previousId, candidate.id)
                                        }
                                    } else if (candidate.id in ids) {
                                        ids = ids - candidate.id
                                        store.remove(widgetId, candidate.id)
                                    } else if (ids.size < MAX_ACTIONS) {
                                        ids = ids + candidate.id
                                        store.add(widgetId, candidate.id)
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    if (keyword.isNotEmpty() && allCandidates.none { feature ->
                        listOf(feature.title, feature.subtitle).any { it.contains(keyword, ignoreCase = true) }
                    }) {
                        Text(
                            "没有找到「$keyword」相关的入口",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorSelectedTile(
    feature: ServiceFeature,
    highlighted: Boolean,
    elevated: Boolean,
    modifier: Modifier,
    onRemove: () -> Unit,
) {
    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (highlighted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            shadowElevation = if (elevated) 8.dp else 0.dp,
            modifier = Modifier.fillMaxWidth().height(94.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    QuickActionIcon(feature = feature, size = 36.dp)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        feature.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter).size(16.dp),
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "移除${feature.title}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun EditorCandidateRow(
    feature: ServiceFeature,
    selected: Boolean,
    canAdd: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = selected || canAdd, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickActionIcon(feature = feature, size = 36.dp)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(feature.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                feature.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            shape = CircleShape,
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else if (canAdd) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (selected) Icons.Outlined.Check else Icons.Outlined.Add,
                    contentDescription = if (selected) "已添加${feature.title}" else "添加${feature.title}",
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun findSdgyFeature(id: String, groups: List<SdgyAppGroup>): ServiceFeature? =
    groups.asSequence().flatMap { it.apps }.map(FeatureCatalog::sdgyApp).firstOrNull { it.id == id }

@Composable
private fun QuickActionIcon(feature: ServiceFeature, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        val officialIcon = com.sdmu.guanlitong.ui.components.rememberRemoteIcon(feature.iconUrl)
        if (officialIcon != null) {
            androidx.compose.foundation.Image(
                bitmap = officialIcon,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.size(26.dp),
            )
        } else {
            Icon(
                feature.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
