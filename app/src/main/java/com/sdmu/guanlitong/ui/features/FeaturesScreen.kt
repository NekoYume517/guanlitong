package com.sdmu.guanlitong.ui.features

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.FavoriteStore
import com.sdmu.guanlitong.data.FeatureAction
import com.sdmu.guanlitong.data.FeatureCatalog
import com.sdmu.guanlitong.data.LoginMode
import com.sdmu.guanlitong.data.SchoolService
import kotlinx.coroutines.launch
import com.sdmu.guanlitong.data.SdgyPage
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.ServiceFeature
import com.sdmu.guanlitong.data.sdgy.SdgyAppCatalog
import com.sdmu.guanlitong.data.sdgy.SdgyAppEntry
import com.sdmu.guanlitong.data.sdgy.SdgyAppGroup
import com.sdmu.guanlitong.data.sdgy.SdgyAppsStore
import com.sdmu.guanlitong.data.targetServiceId
import com.sdmu.guanlitong.ui.components.FeatureLongPressMenu
import com.sdmu.guanlitong.ui.components.FlowChipRow
import com.sdmu.guanlitong.ui.components.LongPressMenuItem
import com.sdmu.guanlitong.ui.sdgy.SdgyAppsContent

/**
 * 功能页：左侧是纯文字的服务二级栏，右侧是该服务的功能胶囊（两列）。
 *
 * 顶部搜索会跨所有服务检索功能；不搜索时只看当前服务。
 * 功能定义都在 [FeatureCatalog]，点击后的跳转由外层处理。
 */
@Composable
fun FeaturesScreen(
    accounts: Map<String, Account>,
    selectedServiceId: String,
    onSelectService: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenAccount: (SchoolService) -> Unit,
    onOpenSdgyNative: (SdgyPage) -> Unit = { _ -> },
    onOpenSdgyWeb: (title: String, appId: String, url: String, lx: String) -> Unit = { _, _, _, _ -> },
    /** 由 AppRoot 保存，覆盖页退出后仍保留各服务右侧面板的滚动位置。 */
    scrollStates: MutableMap<String, ScrollState>,
    searchScrollState: ScrollState,
    sdgyListState: LazyListState,
    modifier: Modifier = Modifier,
    dataSourceLabel: String? = null,
    statusAccount: Account? = null,
    /** 数据来源是抢课助手时，才展示抢课任务这类抢课助手专属功能。 */
    grabSource: Boolean = false,
    /** 功能页的收藏；由 AppRoot 持有，切换页面后收藏不丢。 */
    favorites: FavoriteStore,
) {
    val context = LocalContext.current
    // 「收藏」排在分类最前面，点开后只看收藏过的功能。
    val services = remember { listOf(FAVORITES_TAB) + ServiceCatalog.featureServices }
    val selected = services.firstOrNull { it.id == selectedServiceId } ?: ServiceCatalog.featureServices.first()
    val account = accounts[selected.id]

    // 山管功能表由本页持有：山管面板刷新后写回这里，收藏页立刻能看到新条目。
    var sdgyGroups by remember {
        mutableStateOf(SdgyAppsStore.load(context)?.groups ?: SdgyAppCatalog.fallback())
    }

    val favoriteIds by favorites.ids.collectAsState()
    val favoriteFeatures = remember(favoriteIds, sdgyGroups) {
        favoriteIds.mapNotNull { id -> resolveFavorite(id, sdgyGroups) }
    }
    val favoriteIdSet = remember(favoriteIds) { favoriteIds.toSet() }

    // 右上角三个点切换布局：按分类查看（默认），或把全部服务平铺成一页。
    // 选择存在本机，下次打开还是上次那种。
    val layoutPrefs = remember(context) {
        context.getSharedPreferences("guanlitong_ui", Context.MODE_PRIVATE)
    }
    var stacked by remember {
        mutableStateOf(layoutPrefs.getString(LAYOUT_KEY, LAYOUT_TABS) == LAYOUT_STACKED)
    }
    var layoutMenuOpen by remember { mutableStateOf(false) }
    val chooseLayout: (Boolean) -> Unit = { value ->
        stacked = value
        layoutMenuOpen = false
        layoutPrefs.edit().putString(LAYOUT_KEY, if (value) LAYOUT_STACKED else LAYOUT_TABS).apply()
    }

    // 长按胶囊弹菜单，点了「收藏 / 取消收藏」才真的改。
    var menuTarget by remember { mutableStateOf<LongPressTarget?>(null) }
    var menuVisible by remember { mutableStateOf(false) }
    // 关闭菜单统一走这里：先播退场动画，播完由 onDismiss 真正移除。
    val closeMenu = remember {
        { menuVisible = false }
    }
    // 收藏结果用页面内的提示条：系统 Toast 在弹层关闭的同一瞬间弹出时，
    // 部分真机会把它放大到整屏，页面内的提示条不另开窗口，没有这个问题。
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toggleFavorite: (ServiceFeature) -> Unit = remember(favorites, scope, snackbarHostState) {
        { feature ->
            val removing = favorites.isFavorite(feature.id)
            if (removing) favorites.remove(feature.id) else favorites.add(feature.id)
            menuVisible = false
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = if (removing) "已取消收藏「${feature.title}」" else "已收藏「${feature.title}」",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
    val openMenuFor: (ServiceFeature, Rect) -> Unit = remember(favorites) {
        { feature, anchor ->
            if (menuTarget == null) {
                menuTarget = LongPressTarget(feature, anchor)
                menuVisible = true
            }
        }
    }
    val openMenuForEntry: (SdgyAppEntry, Rect) -> Unit = remember(favorites) {
        { entry, anchor -> openMenuFor(FeatureCatalog.sdgyApp(entry), anchor) }
    }
    // 「收藏」分类里的胶囊不标星，浮起的那份也保持一致。
    val openMenuForFavorite: (ServiceFeature, Rect) -> Unit = remember {
        { feature, anchor ->
            if (menuTarget == null) {
                menuTarget = LongPressTarget(feature, anchor, star = false)
                menuVisible = true
            }
        }
    }

    val keyword = query.trim()
    val searching = keyword.isNotEmpty()
    val results = remember(keyword, grabSource, sdgyGroups) {
        if (keyword.isEmpty()) {
            emptyList()
        } else {
            // 山管的网页功能不在 FeatureCatalog 里，得从功能表里按名字和说明搜。
            val sdgyMatches = sdgyGroups.asSequence()
                .flatMap { it.apps.asSequence() }
                .filter { entry ->
                    entry.name.contains(keyword, ignoreCase = true) ||
                        entry.description.contains(keyword, ignoreCase = true)
                }
                .map { FeatureCatalog.sdgyApp(it) }
                .toList()
            // 山管的一卡通、用电等已由上面的条目覆盖，目录里不再重复列一遍。
            val featureMatches = FeatureCatalog.features.filter { feature ->
                feature.serviceId != "sdgy" &&
                    (grabSource || !feature.grabSourceOnly) &&
                    (
                        feature.title.contains(keyword, ignoreCase = true) ||
                            feature.subtitle.contains(keyword, ignoreCase = true) ||
                            ServiceCatalog.byId(feature.serviceId)
                                ?.name?.contains(keyword, ignoreCase = true) == true
                        )
            }
            featureMatches + sdgyMatches
        }
    }

    // 弹层的坐标原点是页面左上角，胶囊量到的却是窗口坐标；
    // 记下页面原点，把胶囊位置减掉它，才和弹层对齐（否则整体偏一个状态栏）。
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { pageOrigin = it.positionInWindow() },
    ) {
        // 手机高度优先：分类占一列会把右侧挤成一条窄缝，改成顶部横滑。
        val compact = maxWidth < 600.dp
        // 长按菜单打开时虚化整个页面；Android 12 起才支持模糊，低版本只是弹出菜单。
        // 点菜单外面由菜单自己的全屏点击层接住，事件到不了下面的胶囊。
        val blurMenu = menuTarget != null && Build.VERSION.SDK_INT >= 31

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurMenu) Modifier.blur(12.dp) else Modifier),
        ) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "功能",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { layoutMenuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "布局方式")
                        }
                        DropdownMenu(
                            expanded = layoutMenuOpen,
                            onDismissRequest = { layoutMenuOpen = false },
                        ) {
                            LayoutMenuItem(
                                text = "按分类查看",
                                selected = !stacked,
                                onClick = { chooseLayout(false) },
                            )
                            LayoutMenuItem(
                                text = "全部平铺",
                                selected = stacked,
                                onClick = { chooseLayout(true) },
                            )
                        }
                    }
                }
                Spacer(Modifier.padding(top = 4.dp))
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("搜索功能") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.padding(top = 12.dp))

            if (!searching && stacked) {
                // 全部平铺：一页里按服务分节，一级标题是服务，二级标题是官方分组。
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    StackedFeaturesPane(
                        services = services,
                        favoriteFeatures = favoriteFeatures,
                        favoriteIds = favoriteIdSet,
                        onOpenMenuForFavorite = openMenuForFavorite,
                        sdgyGroups = sdgyGroups,
                        grabSource = grabSource,
                        onOpenFeature = onOpenFeature,
                        onOpenMenuFor = openMenuFor,
                        scrollState = scrollStates.getOrPut(LAYOUT_STACKED) { ScrollState(0) },
                        modifier = (if (compact) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.widthIn(max = 760.dp).fillMaxWidth()
                        }).fillMaxHeight(),
                    )
                }
            } else if (compact) {
                // 搜索时只留搜索结果，分类 tab 不占位置。
                if (!searching) {
                    ServiceChipBar(
                        services = services,
                        selectedId = selected.id,
                        onSelect = onSelectService,
                    )
                    Spacer(Modifier.padding(top = 8.dp))
                }
                FeaturePaneContent(
                    selected = selected,
                    account = account,
                    searching = searching,
                    keyword = keyword,
                    results = results,
                    dataSourceLabel = dataSourceLabel,
                    statusAccount = statusAccount,
                    grabSource = grabSource,
                    onOpenFeature = onOpenFeature,
                    onOpenAccount = onOpenAccount,
                    onOpenSdgyNative = onOpenSdgyNative,
                    onOpenSdgyWeb = onOpenSdgyWeb,
                    scrollStates = scrollStates,
                    searchScrollState = searchScrollState,
                    sdgyListState = sdgyListState,
                    sdgyGroups = sdgyGroups,
                    onSdgyGroupsChange = { sdgyGroups = it },
                    favoriteFeatures = favoriteFeatures,
                    favoriteIds = favoriteIdSet,
                    onOpenMenuFor = openMenuFor,
                    onOpenMenuForFavorite = openMenuForFavorite,
                    onOpenMenuForEntry = openMenuForEntry,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // 搜索时只留搜索结果，左侧分类栏不占位置。
                    if (!searching) {
                        LazyColumn(
                            modifier = Modifier
                                .width(124.dp)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(services.size, key = { services[it].id }) { index ->
                                val service = services[index]
                                ServiceRailTab(
                                    service = service,
                                    selected = service.id == selected.id,
                                    onClick = { onSelectService(service.id) },
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        )
                    }

                    FeaturePaneContent(
                        selected = selected,
                        account = account,
                        searching = searching,
                        keyword = keyword,
                        results = results,
                        dataSourceLabel = dataSourceLabel,
                        statusAccount = statusAccount,
                        grabSource = grabSource,
                        onOpenFeature = onOpenFeature,
                        onOpenAccount = onOpenAccount,
                        onOpenSdgyNative = onOpenSdgyNative,
                        onOpenSdgyWeb = onOpenSdgyWeb,
                        scrollStates = scrollStates,
                        searchScrollState = searchScrollState,
                        sdgyListState = sdgyListState,
                        sdgyGroups = sdgyGroups,
                        onSdgyGroupsChange = { sdgyGroups = it },
                        favoriteFeatures = favoriteFeatures,
                        favoriteIds = favoriteIdSet,
                        onOpenMenuFor = openMenuFor,
                        onOpenMenuForFavorite = openMenuForFavorite,
                        onOpenMenuForEntry = openMenuForEntry,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 收藏结果的提示条，贴在页面底部、导航栏上方。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        )

        // 长按菜单：所有胶囊共用这一个。
        val target = menuTarget
        if (target != null) {
            BackHandler(enabled = true) { closeMenu() }
            FeatureLongPressMenu(
                anchor = target.anchor.translate(-pageOrigin.x, -pageOrigin.y),
                itemCount = 1,
                // 页面自身占的范围，菜单不会压到底部导航栏上。
                bounds = Rect(
                    0f,
                    0f,
                    constraints.maxWidth.toFloat(),
                    constraints.maxHeight.toFloat(),
                ),
                visible = menuVisible,
                onDismiss = { menuTarget = null },
                onRequestClose = closeMenu,
                modifier = Modifier.fillMaxSize(),
                // 被长按的胶囊浮在最上层，保持清晰。
                lifted = {
                    FeatureChipBody(
                        feature = target.feature,
                        favorited = target.star && target.feature.id in favoriteIdSet,
                    )
                },
            ) {
                // 已收藏的用实心星，和「取消收藏」对应；没收藏的是描边星。
                val removing = favorites.isFavorite(target.feature.id)
                LongPressMenuItem(
                    text = if (removing) "取消收藏" else "收藏",
                    icon = {
                        Icon(
                            if (removing) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            contentDescription = null,
                        )
                    },
                    onClick = { toggleFavorite(target.feature) },
                )
            }
        }
    }
}

@Composable
private fun ServiceChipBar(
    services: List<SchoolService>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(selectedId) {
        val index = services.indexOfFirst { it.id == selectedId }
        if (index >= 0) state.animateScrollToItem(index)
    }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(services.size, key = { services[it].id }) { index ->
            val service = services[index]
            val selected = service.id == selectedId
            Surface(
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSelect(service.id) },
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = service.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 右上角布局菜单的一项，当前生效的那项带勾。 */
@Composable
private fun LayoutMenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        trailingIcon = {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = "当前布局")
        },
        onClick = onClick,
    )
}

/**
 * 「全部平铺」布局：所有服务排在同一页里。
 * 一级标题是服务名，二级标题是山管官方分组；有收藏时收藏排在最前面。
 */
@Composable
private fun StackedFeaturesPane(
    services: List<SchoolService>,
    favoriteFeatures: List<ServiceFeature>,
    favoriteIds: Set<String>,
    sdgyGroups: List<SdgyAppGroup>,
    grabSource: Boolean,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenMenuFor: (ServiceFeature, Rect) -> Unit,
    onOpenMenuForFavorite: (ServiceFeature, Rect) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 120.dp),
    ) {
        if (favoriteFeatures.isNotEmpty()) {
            StackedSectionTitle("收藏")
            FeatureChipRows(favoriteFeatures, onOpenFeature, onOpenMenuForFavorite)
        }

        services.filter { it.id != FAVORITES_ID }.forEach { service ->
            if (service.id == "sdgy") {
                StackedSectionTitle(service.name)
                sdgyGroups.forEach { group ->
                    if (group.apps.isEmpty()) return@forEach
                    StackedGroupTitle(group.name)
                    FeatureChipRows(
                        features = group.apps.map { FeatureCatalog.sdgyApp(it) },
                        onOpenFeature = onOpenFeature,
                        onLongPress = onOpenMenuFor,
                        favoriteIds = favoriteIds,
                    )
                }
            } else {
                val features = FeatureCatalog.forService(service.id)
                    .filter { grabSource || !it.grabSourceOnly }
                if (features.isEmpty()) return@forEach
                StackedSectionTitle(service.name)
                FeatureChipRows(features, onOpenFeature, onOpenMenuFor, favoriteIds)
            }
        }
    }
}

@Composable
private fun StackedSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
    )
}

@Composable
private fun StackedGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun FeaturePaneContent(
    selected: SchoolService,
    account: Account?,
    searching: Boolean,
    keyword: String,
    results: List<ServiceFeature>,
    dataSourceLabel: String?,
    statusAccount: Account?,
    grabSource: Boolean,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenAccount: (SchoolService) -> Unit,
    onOpenSdgyNative: (SdgyPage) -> Unit,
    onOpenSdgyWeb: (title: String, appId: String, url: String, lx: String) -> Unit,
    scrollStates: MutableMap<String, ScrollState>,
    searchScrollState: ScrollState,
    sdgyListState: LazyListState,
    sdgyGroups: List<SdgyAppGroup>,
    onSdgyGroupsChange: (List<SdgyAppGroup>) -> Unit,
    favoriteFeatures: List<ServiceFeature>,
    favoriteIds: Set<String>,
    onOpenMenuFor: (ServiceFeature, Rect) -> Unit,
    onOpenMenuForFavorite: (ServiceFeature, Rect) -> Unit,
    onOpenMenuForEntry: (SdgyAppEntry, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 切换分类时做一个短交叉淡入，比整块硬切更容易跟上。
    Crossfade(
        targetState = if (searching) null else selected,
        animationSpec = tween(durationMillis = 180),
        label = "featurePane",
        modifier = modifier,
    ) { pane ->
        when {
            pane == null -> SearchResultsPane(
                keyword = keyword,
                results = results,
                onOpenFeature = onOpenFeature,
                onOpenMenuFor = onOpenMenuFor,
                favoriteIds = favoriteIds,
                scrollState = searchScrollState,
                modifier = Modifier.fillMaxSize(),
            )
            pane.id == FAVORITES_ID -> FavoritesPane(
                features = favoriteFeatures,
                onOpenFeature = onOpenFeature,
                onOpenMenuFor = onOpenMenuForFavorite,
                scrollState = scrollStates.getOrPut(pane.id) { ScrollState(0) },
                modifier = Modifier.fillMaxSize(),
            )
            pane.id == "sdgy" -> SdgyAppsContent(
                onOpenNative = onOpenSdgyNative,
                onOpenWeb = onOpenSdgyWeb,
                onOpenAccount = { onOpenAccount(pane) },
                modifier = Modifier.fillMaxSize(),
                listState = sdgyListState,
                showAccountCard = false,
                showSearch = false,
                externalQuery = "",
                flowChips = true,
                externalGroups = sdgyGroups,
                onGroupsChange = onSdgyGroupsChange,
                onLongPressEntry = onOpenMenuForEntry,
                favoriteIds = favoriteIds,
            )
            else -> ServicePane(
                service = pane,
                account = account,
                dataSourceLabel = dataSourceLabel,
                statusAccount = statusAccount,
                grabSource = grabSource,
                onOpenFeature = onOpenFeature,
                onOpenAccount = onOpenAccount,
                onOpenSdgyNative = onOpenSdgyNative,
                onOpenSdgyWeb = onOpenSdgyWeb,
                onOpenMenuFor = onOpenMenuFor,
                favoriteIds = favoriteIds,
                scrollStates = scrollStates,
                sdgyListState = sdgyListState,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ServiceRailTab(
    service: SchoolService,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中项多一条主色竖条，比只换底色更容易扫到。
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                ),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = service.name,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ServicePane(
    service: SchoolService,
    account: Account?,
    dataSourceLabel: String? = null,
    statusAccount: Account? = null,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenAccount: (SchoolService) -> Unit,
    onOpenSdgyNative: (SdgyPage) -> Unit = { _ -> },
    onOpenSdgyWeb: (title: String, appId: String, url: String, lx: String) -> Unit = { _, _, _, _ -> },
    onOpenMenuFor: (ServiceFeature, Rect) -> Unit,
    favoriteIds: Set<String>,
    scrollStates: MutableMap<String, ScrollState>,
    sdgyListState: LazyListState,
    modifier: Modifier = Modifier,
    grabSource: Boolean = false,
) {
    val features = FeatureCatalog.forService(service.id)
        .filter { grabSource || !it.grabSourceOnly }
    val scrollState = scrollStates.getOrPut(service.id) { ScrollState(0) }

    Column(modifier = modifier.fillMaxHeight()) {
        if (service.id == "sdgy") {
            // 山管app 不再单开二级页：官方功能总表直接铺在右侧面板里。
            SdgyAppsContent(
                onOpenNative = onOpenSdgyNative,
                onOpenWeb = onOpenSdgyWeb,
                onOpenAccount = { onOpenAccount(service) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                listState = sdgyListState,
                showAccountCard = false,
                showSearch = false,
                externalQuery = "",
                flowChips = true,
                favoriteIds = favoriteIds,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 120.dp),
            ) {
                if (features.isEmpty()) {
                    Text(
                        text = "这个服务还没有配置功能",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FeatureChipRows(
                        features = features,
                        onOpenFeature = onOpenFeature,
                        onLongPress = onOpenMenuFor,
                        favoriteIds = favoriteIds,
                    )
                }
            }
        }
    }
}

/** 「收藏」分类：只放收藏过的功能，长按可以取消收藏。 */
@Composable
private fun FavoritesPane(
    features: List<ServiceFeature>,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenMenuFor: (ServiceFeature, Rect) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(scrollState)
            .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 120.dp),
    ) {
        if (features.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.StarOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "还没有收藏",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "在别的分类里长按一个功能，就会出现在这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            FeatureChipRows(features = features, onOpenFeature = onOpenFeature, onLongPress = onOpenMenuFor)
        }
    }
}

@Composable
private fun SearchResultsPane(
    keyword: String,
    results: List<ServiceFeature>,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenMenuFor: (ServiceFeature, Rect) -> Unit,
    favoriteIds: Set<String>,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
    ) {
        Text(
            text = "搜索结果",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        // 有结果时列表本身已经说明了一切，只有没搜到才需要写一句。
        if (results.isEmpty()) {
            Text(
                text = "没有找到「$keyword」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        results.groupBy { it.serviceId }.forEach { (serviceId, features) ->
            val serviceName = ServiceCatalog.byId(serviceId)?.name ?: serviceId
            Text(
                text = serviceName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
            )
            FeatureChipRows(
                features = features,
                onOpenFeature = onOpenFeature,
                onLongPress = onOpenMenuFor,
                favoriteIds = favoriteIds,
            )
        }
    }
}

@Composable
private fun FeatureChipRows(
    features: List<ServiceFeature>,
    onOpenFeature: (ServiceFeature) -> Unit,
    /** 长按胶囊，带上它在窗口里的位置，用来定位菜单。 */
    onLongPress: ((ServiceFeature, Rect) -> Unit)? = null,
    /** 已收藏的入口在图标前打一颗星；「收藏」分类里全都有，就不用重复标。 */
    favoriteIds: Set<String> = emptySet(),
) {
    FlowChipRow(modifier = Modifier.fillMaxWidth()) {
        features.forEach { feature ->
            FeatureChip(
                feature = feature,
                favorited = feature.id in favoriteIds,
                onClick = { onOpenFeature(feature) },
                onLongPress = onLongPress,
            )
        }
    }
}

/** 与山管 App 的胶囊保持同一尺寸：36dp 高、11dp 横向内边距、18dp 图标。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeatureChip(
    feature: ServiceFeature,
    onClick: () -> Unit,
    onLongPress: ((ServiceFeature, Rect) -> Unit)? = null,
    favorited: Boolean = false,
) {
    // 长按时才取位置；用最新的布局坐标，避免闭包拿到过期的。
    var chipBounds by remember { mutableStateOf(Rect.Zero) }
    val accent = ServiceCatalog.byId(feature.targetServiceId)?.accent
        ?: MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .onGloballyPositioned { chipBounds = it.boundsInWindow() }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress?.let { open -> { open(feature, chipBounds) } },
            ),
    ) {
        ChipBody(feature, accent, favorited)
    }
}

/**
 * 只负责画胶囊，不带点击。
 * 列表里和长按菜单里浮起的那一份用的是同一个，所以两处外观完全一致。
 */
@Composable
internal fun FeatureChipBody(feature: ServiceFeature, favorited: Boolean = false) {
    ChipBody(
        feature = feature,
        accent = ServiceCatalog.byId(feature.targetServiceId)?.accent ?: MaterialTheme.colorScheme.primary,
        favorited = favorited,
    )
}

@Composable
private fun ChipBody(feature: ServiceFeature, accent: Color, favorited: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (favorited) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = "已收藏",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            val officialIcon = com.sdmu.guanlitong.ui.components.rememberRemoteIcon(feature.iconUrl)
            if (officialIcon != null) {
                androidx.compose.foundation.Image(
                    bitmap = officialIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = feature.title,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 「收藏」只是功能页里的一个分类，不是需要登录的服务，所以不进 [ServiceCatalog]。 */
private const val FAVORITES_ID = "favorites"

/** 布局偏好的存储：按分类查看（默认）或全部平铺。 */
private const val LAYOUT_KEY = "feature_layout"
private const val LAYOUT_TABS = "tabs"
private const val LAYOUT_STACKED = "stacked"

/** 正被长按的功能，以及它在窗口里的位置。 */
private data class LongPressTarget(
    val feature: ServiceFeature,
    val anchor: Rect,
    /** 「收藏」分类里本来就不标星，浮起的胶囊也跟着不标。 */
    val star: Boolean = true,
)

private val FAVORITES_TAB = SchoolService(
    id = FAVORITES_ID,
    name = "收藏",
    summary = "",
    defaultUrl = "",
    loginMode = LoginMode.WEB,
    icon = Icons.Outlined.Star,
    accent = Color.Unspecified,
    requiresAccount = false,
)

/**
 * 把收藏的 ID 还原成功能。教务的走静态目录；山管的条目要从功能表里找，
 * 服务端下架后就找不到，收藏页里不再显示（收藏记录本身保留，重新上架会回来）。
 */
private fun resolveFavorite(id: String, sdgyGroups: List<SdgyAppGroup>): ServiceFeature? {
    FeatureCatalog.byId(id)?.let { return it }
    if (!id.startsWith("sdgy.app.")) return null
    val entryId = id.removePrefix("sdgy.app.")
    val entry = sdgyGroups.asSequence().flatMap { it.apps }.firstOrNull { it.id == entryId } ?: return null
    return FeatureCatalog.sdgyApp(entry)
}