package com.sdmu.guanlitong.ui.sdgy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.SdgyPage
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.sdgy.SdgyAppCatalog
import com.sdmu.guanlitong.data.sdgy.SdgyAppEntry
import com.sdmu.guanlitong.data.sdgy.SdgyAppGroup
import com.sdmu.guanlitong.data.sdgy.SdgyAppsStore
import com.sdmu.guanlitong.ui.components.FlowChipRow
import com.sdmu.guanlitong.ui.components.rememberRemoteIcon
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 山管 App 的功能总页。
 *
 * 版式对齐官方「应用」页：服务端按分类下发的应用，四列图标网格。
 * 官方条目绝大多数是网页，点开进 [SdgyWebScreen]；课表、付款码、
 * 请销假、宿舍用电则换成我们自己的原生页面。
 */
@Composable
fun SdgyHubScreen(
    onBack: () -> Unit,
    onOpenNative: (SdgyPage) -> Unit,
    onOpenWeb: (title: String, appId: String, url: String, lx: String) -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SdgyScaffold(
        title = "山管 App",
        onBack = onBack,
        modifier = modifier,
    ) { padding ->
        SdgyAppsContent(
            onOpenNative = onOpenNative,
            onOpenWeb = onOpenWeb,
            onOpenAccount = onOpenAccount,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/**
 * 官方功能总页的主体，可以直接嵌进「功能」页的右侧面板，也可以套一层页面单独显示。
 * 页面自己拉 `api/app/list`、做缓存和搜索，外层只负责导航。
 */
@Composable
fun SdgyAppsContent(
    onOpenNative: (SdgyPage) -> Unit,
    onOpenWeb: (title: String, appId: String, url: String, lx: String) -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
    /** 可由内嵌功能页的 AppRoot 提权保存，避免从功能详情返回时回到顶部。 */
    listState: LazyListState = rememberLazyListState(),
    showAccountCard: Boolean = true,
    showSearch: Boolean = true,
    externalQuery: String? = null,
    /** 右侧面板里用教务系统同款的胶囊按钮，按官方分类分组铺开。 */
    flowChips: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = 12.dp,
        bottom = 28.dp,
    ),
    /**
     * 由功能页提权保存的功能表；传入后本组件不再自己维护分组，
     * 刷新结果通过 [onGroupsChange] 交回外部（收藏页要读同一份数据）。
     */
    externalGroups: List<SdgyAppGroup>? = null,
    onGroupsChange: (List<SdgyAppGroup>) -> Unit = {},
    /** 长按某个功能，用来收藏。 */
    onLongPressEntry: ((SdgyAppEntry, Rect) -> Unit)? = null,
) {
    val context = LocalContext.current
    val repository = rememberSdgyRepository()
    val accountStore = remember(context) { AccountStore(context) }
    val accounts by accountStore.accounts.collectAsState()
    val account = accounts["sdgy"]
    val loggedIn = account?.loggedIn == true

    val cached = remember { SdgyAppsStore.load(context) }
    var localGroups by remember {
        mutableStateOf(cached?.groups ?: SdgyAppCatalog.fallback())
    }
    val groups = externalGroups ?: localGroups
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    if (externalQuery != null) query = externalQuery

    LaunchedEffect(loggedIn, refreshToken) {
        if (!loggedIn) return@LaunchedEffect
        refreshing = true
        try {
            val list = repository.call { it.appList("1") }
            val parsed = SdgyAppCatalog.withNativeTools(SdgyAppCatalog.parse(list))
            if (parsed.isNotEmpty()) {
                localGroups = parsed
                onGroupsChange(parsed)
                SdgyAppsStore.save(context, list.toString())
                error = null
            } else {
                error = "服务器没有返回功能列表"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.sdgyMessage()
        } finally {
            refreshing = false
        }
    }

    val shownGroups = remember(groups, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            groups
        } else {
            groups.mapNotNull { group ->
                val hits = group.apps.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.description.contains(q, ignoreCase = true)
                }
                group.copy(apps = hits).takeIf { hits.isNotEmpty() }
            }
        }
    }

    fun openEntry(entry: SdgyAppEntry) {
        val native = entry.nativePage
        if (native != null) {
            onOpenNative(native)
            return
        }
        if (entry.opensExternalApp) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)))
            }.onFailure {
                toast(context, "没有能打开「${entry.name}」的应用")
            }
            return
        }
        if (entry.isAppInternal) {
            toast(context, "这个功能暂时不能在管院通里打开")
            return
        }
        if (!loggedIn) {
            toast(context, "请先登录山管 App")
            onOpenAccount()
            return
        }
        onOpenWeb(entry.name, entry.id, entry.url, entry.lx)
    }

    Column(modifier.fillMaxSize()) {
        if (refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (showAccountCard) {
                item {
                    AccountCard(
                        loggedIn = loggedIn,
                        name = account?.displayName.orEmpty(),
                        studentId = account?.username.orEmpty(),
                        onOpenAccount = onOpenAccount,
                    )
                }
            }
            if (showSearch) {
                item {
                    SearchField(query = query, onQueryChange = { query = it })
                }
            }
            // 分类胶囊模式下，刷新按钮挂到第一个分组标题右边，避免单独占一行留下空白。
            if (query.isBlank() && !flowChips) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RefreshButton(refreshing, loggedIn) { refreshToken++ }
                    }
                }
            }
            if (error != null && loggedIn) {
                item {
                    ErrorCard(
                        message = error.orEmpty(),
                        source = if (cached != null) "上次同步的功能列表" else "常用功能",
                        onRetry = { refreshToken++ },
                    )
                }
            }
            if (query.isNotBlank()) {
                item { SectionTitle("搜索结果") }
                if (shownGroups.isEmpty()) {
                    item {
                        Text(
                            "没有找到「$query」",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    item {
                        if (flowChips) {
                            AppChipRows(
                                shownGroups.flatMap { it.apps }.distinctBy { it.id },
                                onOpen = ::openEntry,
                                onLongPress = onLongPressEntry,
                            )
                        } else {
                            AppGrid(
                                shownGroups.flatMap { it.apps }.distinctBy { it.id },
                                onOpen = ::openEntry,
                            )
                        }
                    }
                }
            } else if (flowChips) {
                // 官方分类标题 + 该分类的胶囊按钮，不再做第三级 tab。
                items(shownGroups, key = { it.name }) { group ->
                    Column {
                        SectionTitle(group.name) {
                            if (group.name == shownGroups.first().name) {
                                RefreshButton(refreshing, loggedIn) { refreshToken++ }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        AppChipRows(group.apps, onOpen = ::openEntry, onLongPress = onLongPressEntry)
                    }
                }
            } else {
                items(shownGroups, key = { it.name }) { group ->
                    Column {
                        SectionTitle(group.name)
                        Spacer(Modifier.height(10.dp))
                        AppGrid(group.apps, onOpen = ::openEntry)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(
    loggedIn: Boolean,
    name: String,
    studentId: String,
    onOpenAccount: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).background(
                    MaterialTheme.colorScheme.primaryContainer,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (loggedIn) name.firstOrNull()?.toString() ?: "山" else "未",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (loggedIn) name.ifBlank { "已登录" } else "还没登录山管 App",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (loggedIn) {
                        studentId.takeIf { it.isNotBlank() }?.let { "学号 $it" } ?: "已连接山管服务器"
                    } else {
                        "登录后可使用官方的全部功能"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loggedIn) {
                TextButton(onClick = onOpenAccount) { Text("账号") }
            } else {
                FilledTonalButton(onClick = onOpenAccount) {
                    Icon(Icons.Outlined.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("去登录")
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("搜索功能") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "清空")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(26.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
private fun ErrorCard(message: String, source: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "功能列表没刷新出来",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                val detail = message.takeUnless {
                    it.isBlank() || it.contains("HTTP") || it.contains("Exception")
                }
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    )
                }
                Text(
                    "现在显示的是$source。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun RefreshButton(
    refreshing: Boolean,
    loggedIn: Boolean,
    onRefresh: () -> Unit,
) {
    IconButton(
        onClick = onRefresh,
        enabled = loggedIn && !refreshing,
        modifier = Modifier.size(36.dp),
    ) {
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "刷新功能列表",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AppChipRows(
    apps: List<SdgyAppEntry>,
    onOpen: (SdgyAppEntry) -> Unit,
    onLongPress: ((SdgyAppEntry, Rect) -> Unit)? = null,
) {
    FlowChipRow(modifier = Modifier.fillMaxWidth()) {
        apps.forEach { entry ->
            AppChip(entry = entry, onOpen = onOpen, onLongPress = onLongPress)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppChip(
    entry: SdgyAppEntry,
    onOpen: (SdgyAppEntry) -> Unit,
    onLongPress: ((SdgyAppEntry, Rect) -> Unit)? = null,
) {
    val accent = ServiceCatalog.byId("sdgy")?.accent ?: MaterialTheme.colorScheme.primary
    // 长按时才取位置；用最新的布局坐标，避免闭包拿到过期的。
    var chipBounds by remember { mutableStateOf(Rect.Zero) }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .onGloballyPositioned { chipBounds = it.boundsInWindow() }
            .combinedClickable(
                onClick = { onOpen(entry) },
                onLongClick = onLongPress?.let { open -> { open(entry, chipBounds) } },
            ),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val remote = rememberRemoteIcon(entry.iconUrl)
            val page = entry.nativePage
            when {
                remote != null -> Image(
                    bitmap = remote,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)),
                    contentScale = ContentScale.Crop,
                )
                page != null -> Icon(
                    page.icon(),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                else -> Icon(
                    Icons.Outlined.Apps,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.name,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppGrid(apps: List<SdgyAppEntry>, onOpen: (SdgyAppEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        apps.chunked(4).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { entry ->
                    AppCell(entry, Modifier.weight(1f), onOpen)
                }
                repeat(4 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AppCell(entry: SdgyAppEntry, modifier: Modifier, onOpen: (SdgyAppEntry) -> Unit) {
    val dark = isSystemInDarkTheme()
    val tint = remember(entry.name, dark) { appTint(entry.name, dark) }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpen(entry) }
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(16.dp),
            color = tint,
        ) {
            Box(contentAlignment = Alignment.Center) {
                val remote = rememberRemoteIcon(entry.iconUrl)
                val page = entry.nativePage
                when {
                    remote != null -> Image(
                        bitmap = remote,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                        contentScale = ContentScale.Fit,
                    )
                    page != null -> Icon(
                        page.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp),
                    )
                    else -> Text(
                        entry.name.firstOrNull()?.toString() ?: "·",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun appTint(name: String, dark: Boolean): Color {
    val hue = (name.hashCode().and(0x7FFFFFFF) % 360).toFloat()
    return if (dark) Color.hsv(hue, 0.34f, 0.34f) else Color.hsv(hue, 0.28f, 0.94f)
}

private fun SdgyPage.icon(): ImageVector = when (this) {
    SdgyPage.Timetable -> Icons.Outlined.CalendarMonth
    SdgyPage.Pay -> Icons.Outlined.CreditCard
    SdgyPage.Power -> Icons.Outlined.ElectricalServices
    SdgyPage.Shot -> Icons.Outlined.EditNote
    SdgyPage.Hub -> Icons.Outlined.Search
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}