package com.sdmu.guanlitong.ui.pu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuActivity
import com.sdmu.guanlitong.data.pu.PuSign
import com.sdmu.guanlitong.ui.sdgy.SdgyLoading
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE_LIMIT = 20
private const val SEARCH_DEBOUNCE_MS = 400L

/** 活动范围。上游 `/apis/activity/list` 只返回本校活动，其余两项只切换视图。 */
private val SCOPE_LABELS = listOf("本校活动", "部落活动", "全国活动")

/**
 * 活动列表：服务端 grep 搜索 + 翻页 + 批量报名（上限 [PU_BATCH_MAX]）。
 *
 * 终止条件按上游真实语义处理：`PuPage.count` 是记录数、`totalPages` 才是页数，
 * 只有「已拿到条数 ≥ count」或「页码 ≥ 总页数」同时不成立时才继续翻页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PuActivitiesScreen(
    onBack: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = rememberPuCredStore()
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<PuActivity>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(1) }
    var count by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var authLost by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var effectiveQuery by remember { mutableStateOf("") }
    var scopeIndex by rememberSaveable { mutableIntStateOf(0) }

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        delay(SEARCH_DEBOUNCE_MS)
        val next = query.trim()
        if (next != effectiveQuery) {
            effectiveQuery = next
        }
    }

    suspend fun fetch(target: Int) {
        if (target == 1) {
            loading = true
        } else {
            loadingMore = true
        }
        val result = puCall(context) {
            PuActions.activities(page = target, limit = PAGE_LIMIT, grep = effectiveQuery)
        }
        when (result) {
            is PuOutcome.Ok -> {
                val paged = result.value
                items = if (target == 1) paged.list else items + paged.list
                page = target
                totalPages = paged.totalPages
                count = paged.count
                error = null
            }
            is PuOutcome.AuthLost -> authLost = true
            is PuOutcome.Fail -> if (target == 1) {
                error = result.message
                items = emptyList()
            } else {
                toast(context, result.message)
            }
        }
        loading = false
        loadingMore = false
    }

    LaunchedEffect(effectiveQuery, refreshKey) {
        if (!store.loggedIn) return@LaunchedEffect
        fetch(1)
    }

    val hasMore = items.isNotEmpty() && items.size < count && page < totalPages

    fun toggle(id: Long) {
        val next = if (id in selected) selected - id else selected + id
        if (next.size > PU_BATCH_MAX && id !in selected) {
            toast(context, "一次最多 $PU_BATCH_MAX 个活动")
            return
        }
        selected = next
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("二课活动") },
                subtitle = { Text("第二课堂") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (store.loggedIn && !authLost) {
                        TextButton(
                            onClick = {
                                selecting = !selecting
                                if (!selecting) selected = emptySet()
                            },
                        ) { Text(if (selecting) "取消" else "批量报名") }
                    }
                    IconButton(
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        },
                    ) {
                        Icon(
                            if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = if (searchOpen) "关闭搜索" else "搜索活动",
                        )
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
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
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !store.loggedIn -> PuLoginPrompt(onBack, Modifier.fillMaxSize())
                authLost -> PuErrorState(PU_AUTH_LOST, null, Modifier.fillMaxSize())
                else -> {
                    AnimatedVisibility(
                        visible = searchOpen,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            placeholder = { Text("搜索活动名称") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "清空")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.extraLarge,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        )
                    }

                    ScopeRow(
                        selected = scopeIndex,
                        onSelect = { scopeIndex = it },
                    )

                    if (selecting) {
                        SelectionBar(
                            selectedCount = selected.size,
                            submitting = submitting,
                            allSelected = items.isNotEmpty() &&
                                items.all { it.id in selected },
                            onToggleAll = {
                                selected = if (items.all { it.id in selected }) {
                                    emptySet()
                                } else {
                                    items.map { it.id }.take(PU_BATCH_MAX).toSet()
                                }
                            },
                            onSubmit = {
                                if (selected.isEmpty()) {
                                    toast(context, "先勾选要报名的活动")
                                    return@SelectionBar
                                }
                                if (!PuSign.available()) {
                                    toast(context, "本机不支持签名，报名功能不可用")
                                    return@SelectionBar
                                }
                                val ids = selected.toLongArray()
                                scope.launch {
                                    submitting = true
                                    when (val r = puCall(context) { PuActions.autoJoin(ids) }) {
                                        is PuOutcome.Ok -> {
                                            toast(context, "已提交 ${ids.size} 个报名")
                                            selecting = false
                                            selected = emptySet()
                                            refreshKey++
                                        }
                                        is PuOutcome.AuthLost -> authLost = true
                                        is PuOutcome.Fail -> toast(context, r.message)
                                    }
                                    submitting = false
                                }
                            },
                        )
                    }

                    when {
                        loading -> SdgyLoading("正在加载活动…", Modifier.fillMaxSize())

                        /* 上游只提供本校数据，其余范围没有可展示的内容。 */
                        scopeIndex != 0 -> PuEmptyState(
                            "暂时只能查看本校活动",
                            Modifier.fillMaxSize(),
                            action = {
                                OutlinedButton(onClick = { scopeIndex = 0 }) {
                                    Text("只看本校活动")
                                }
                            },
                        )

                        error != null -> PuErrorState(
                            error.orEmpty(),
                            onRetry = { refreshKey++ },
                            modifier = Modifier.fillMaxSize(),
                        )

                        items.isEmpty() -> EmptyActivitiesCard(
                            if (effectiveQuery.isBlank()) "暂时没有活动" else "没有找到「$effectiveQuery」",
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 4.dp,
                                bottom = padding.calculateBottomPadding() + 24.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(items, key = { it.id }) { activity ->
                                PuActivityCard(
                                    activity = activity,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .animateItem(),
                                    onClick = {
                                        if (selecting) {
                                            toggle(activity.id)
                                        } else {
                                            onOpenActivity(activity.id)
                                        }
                                    },
                                    trailing = if (selecting) {
                                        {
                                            Checkbox(
                                                checked = activity.id in selected,
                                                onCheckedChange = { toggle(activity.id) },
                                            )
                                        }
                                    } else null,
                                )
                            }
                            item {
                                PuListFooter(
                                    loadingMore = loadingMore,
                                    hasMore = hasMore,
                                    onLoadMore = { scope.launch { fetch(page + 1) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 范围切换：连体 ToggleButton，样式与官方客户端一致。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScopeRow(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SCOPE_LABELS.forEachIndexed { index, label ->
            ToggleButton(
                checked = selected == index,
                onCheckedChange = { onSelect(index) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    SCOPE_LABELS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) { Text(label) }
        }
    }
}

@Composable
private fun EmptyActivitiesCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    submitting: Boolean,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 $selectedCount / $PU_BATCH_MAX",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleAll, enabled = !submitting) {
                Text(if (allSelected) "清空" else "全选本页")
            }
            Spacer(Modifier.width(2.dp))
            if (submitting) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onSubmit, enabled = selectedCount > 0) { Text("报名") }
            }
        }
    }
}