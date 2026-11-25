package com.sdmu.guanlitong.ui.pu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuActivity
import com.sdmu.guanlitong.ui.sdgy.SdgyLoading
import com.sdmu.guanlitong.ui.sdgy.SdgyScaffold
import kotlinx.coroutines.launch

private const val MINE_PAGE_LIMIT = 20

private val MINE_TABS = listOf(
    0 to "全部",
    3 to "已结束未完结",
    4 to "已完结获学时",
)

/**
 * 我的活动：`type` 0 / 3 / 4 是上游唯一真实过滤器（§3.2），切换 Tab 即从第 1 页重取。
 * 终止条件同活动列表：`count` 是记录数、`totalPages` 才是页数。
 */
@Composable
fun PuMineScreen(
    onBack: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = rememberPuCredStore()
    val scope = rememberCoroutineScope()

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<PuActivity>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var count by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var authLost by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val type = MINE_TABS[tabIndex.coerceIn(0, MINE_TABS.lastIndex)].first

    suspend fun fetch(target: Int) {
        if (target == 1) loading = true else loadingMore = true
        val result = puCall(context) {
            PuActions.mine(type = type, page = target, limit = MINE_PAGE_LIMIT)
        }
        when (result) {
            is PuOutcome.Ok -> {
                val paged = result.value
                items = if (target == 1) paged.list else items + paged.list
                page = target
                count = paged.count
                totalPages = paged.totalPages
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

    LaunchedEffect(type, refreshKey) {
        if (!store.loggedIn) return@LaunchedEffect
        items = emptyList()
        page = 1
        fetch(1)
    }

    val hasMore = items.isNotEmpty() && items.size < count && page < totalPages

    SdgyScaffold(
        title = "我的活动",
        onBack = onBack,
        modifier = modifier,
        actions = {
            if (store.loggedIn && !authLost) {
                IconButton(onClick = { refreshKey++ }, enabled = !loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                !store.loggedIn -> PuLoginPrompt(onBack, Modifier.fillMaxSize())
                authLost -> PuErrorState(PU_AUTH_LOST, null, Modifier.fillMaxSize())
                else -> {
                    TabRow(selectedTabIndex = tabIndex) {
                        MINE_TABS.forEachIndexed { index, (_, title) ->
                            Tab(
                                selected = tabIndex == index,
                                onClick = { if (tabIndex != index) tabIndex = index },
                                text = { Text(title, style = MaterialTheme.typography.labelLarge) },
                            )
                        }
                    }

                    when {
                        loading -> SdgyLoading("正在加载我的活动…", Modifier.fillMaxSize())

                        error != null -> PuErrorState(
                            error.orEmpty(),
                            onRetry = { refreshKey++ },
                            modifier = Modifier.fillMaxSize(),
                        )

                        items.isEmpty() -> PuEmptyState(
                            when (type) {
                                3 -> "没有已结束但还没完结的活动"
                                4 -> "还没有拿到学时的活动"
                                else -> "这个账号还没有报名过活动"
                            },
                            Modifier.fillMaxSize(),
                        )

                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(items, key = { it.id }) { activity ->
                                PuActivityCard(
                                    activity = activity,
                                    onClick = { onOpenActivity(activity.id) },
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