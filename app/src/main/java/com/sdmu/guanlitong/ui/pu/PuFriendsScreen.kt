package com.sdmu.guanlitong.ui.pu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuFriend
import com.sdmu.guanlitong.data.pu.asFriend
import com.sdmu.guanlitong.ui.sdgy.SdgyLoading
import com.sdmu.guanlitong.ui.sdgy.SdgyScaffold
import kotlinx.coroutines.launch

private const val FRIEND_PAGE_LIMIT = 30

private val FRIEND_TABS = listOf(
    Triple("friend", "好友", "互相关注"),
    Triple("followee", "关注", "我关注的"),
    Triple("follower", "粉丝", "关注我的"),
)

/**
 * 好友 / 关注 / 粉丝。三 tab 对应 `/apis/relations/{type}/list` 的 type 参数。
 *
 * 头像地址不做网络加载（项目没有图片库），用昵称首字圆形占位；
 * 点击条目打开好友主页（`otherUser`），主页里也能关注/取关。
 */
@Composable
fun PuFriendsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = rememberPuCredStore()
    val scope = rememberCoroutineScope()

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<PuFriend>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var count by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var authLost by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var busyUid by remember { mutableStateOf<String?>(null) }

    var profileUid by remember { mutableStateOf<String?>(null) }

    val type = FRIEND_TABS[tabIndex.coerceIn(0, FRIEND_TABS.lastIndex)].first

    // PuPage.list 的元素类型固定是 PuActivity（§6.3），好友记录由数据层压成
    // 「外壳 PuActivity」，这里用 asFriend() 还原成 PuFriend。
    suspend fun fetch(target: Int) {
        if (target == 1) loading = true else loadingMore = true
        val result = puCall(context) {
            PuActions.friends(type = type, page = target, limit = FRIEND_PAGE_LIMIT)
        }
        when (result) {
            is PuOutcome.Ok -> {
                val paged = result.value
                val list = paged.list.map { it.asFriend() }
                items = if (target == 1) list else items + list
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

    fun toggleFollow(friend: PuFriend, inProfile: Boolean = false) {
        if (busyUid != null) return
        scope.launch {
            busyUid = friend.uid
            val following = friend.isFollowee
            val result = puCall(context) {
                if (following) PuActions.unfollow(friend.uid) else PuActions.follow(friend.uid)
            }
            when (result) {
                is PuOutcome.Ok -> {
                    toast(context, if (following) "已取消关注" else "已关注")
                    if (inProfile) profileUid = profileUid else refreshKey++
                }
                is PuOutcome.AuthLost -> authLost = true
                is PuOutcome.Fail -> toast(context, result.message)
            }
            busyUid = null
        }
    }

    SdgyScaffold(
        title = "好友",
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
                        FRIEND_TABS.forEachIndexed { index, (_, title, _) ->
                            Tab(
                                selected = tabIndex == index,
                                onClick = { if (tabIndex != index) tabIndex = index },
                                text = { Text(title, style = MaterialTheme.typography.labelLarge) },
                            )
                        }
                    }

                    when {
                        loading -> SdgyLoading("正在加载${FRIEND_TABS[tabIndex].second}…", Modifier.fillMaxSize())

                        error != null -> PuErrorState(
                            error.orEmpty(),
                            onRetry = { refreshKey++ },
                            modifier = Modifier.fillMaxSize(),
                        )

                        items.isEmpty() -> PuEmptyState(
                            when (type) {
                                "followee" -> "还没有关注任何人"
                                "follower" -> "还没有粉丝"
                                else -> "还没有互相关注的好友"
                            },
                            Modifier.fillMaxSize(),
                        )

                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(items, key = { it.uid }) { friend ->
                                FriendRow(
                                    friend = friend,
                                    busy = busyUid == friend.uid,
                                    onClick = { profileUid = friend.uid },
                                    onToggle = { toggleFollow(friend) },
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

    val currentProfile = items.firstOrNull { it.uid == profileUid }
    if (profileUid != null) {
        FriendProfileDialog(
            uid = profileUid!!,
            cached = currentProfile,
            busy = busyUid != null,
            onClose = { profileUid = null },
            onToggle = { friend -> toggleFollow(friend, inProfile = true) },
        )
    }
}

@Composable
private fun FriendRow(
    friend: PuFriend,
    busy: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarBadge(friend.nickname)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    friend.nickname.ifBlank { "口袋校园用户" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    relationText(friend),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                OutlinedButton(onClick = onToggle) {
                    Text(if (friend.isFollowee) "取关" else "关注")
                }
            }
        }
    }
}

@Composable
private fun AvatarBadge(nickname: String) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            nickname.trim().firstOrNull()?.toString() ?: "友",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun relationText(friend: PuFriend): String = when {
    friend.isFollowee && friend.isFollower -> "互相关注"
    friend.isFollowee -> "我关注了 TA"
    friend.isFollower -> "TA 关注了我"
    else -> "口袋校园用户"
}

/** 好友主页：`otherUser(uid)` 拉取详情，页内可关注/取关。 */
@Composable
private fun FriendProfileDialog(
    uid: String,
    cached: PuFriend?,
    busy: Boolean,
    onClose: () -> Unit,
    onToggle: (PuFriend) -> Unit,
) {
    val context = LocalContext.current
    var friend by remember(uid) { mutableStateOf(cached) }
    var loading by remember(uid) { mutableStateOf(cached == null) }
    var error by remember(uid) { mutableStateOf<String?>(null) }
    var refreshKey by remember(uid) { mutableIntStateOf(0) }

    LaunchedEffect(uid, refreshKey) {
        loading = true
        error = null
        when (val r = puCall(context) { PuActions.otherUser(uid) }) {
            is PuOutcome.Ok -> friend = r.value
            is PuOutcome.AuthLost -> error = PU_AUTH_LOST
            is PuOutcome.Fail -> error = r.message
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("好友主页") },
        text = {
            Column {
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在读取…", style = MaterialTheme.typography.bodySmall)
                    }

                    error != null -> Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    friend != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarBadge(friend!!.nickname)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    friend!!.nickname.ifBlank { "口袋校园用户" },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    relationText(friend!!),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "UID $uid",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (friend != null && !loading) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { onToggle(friend!!) }) {
                        Text(if (friend!!.isFollowee) "取消关注" else "关注")
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}