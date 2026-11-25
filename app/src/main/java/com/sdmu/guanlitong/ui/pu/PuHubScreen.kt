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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdmu.guanlitong.data.PuPage
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuSign
import com.sdmu.guanlitong.data.pu.PuUser
import com.sdmu.guanlitong.data.pu.mask

private data class PuEntry(
    val page: PuPage,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val PU_ENTRIES: List<PuEntry> = listOf(
    PuEntry(PuPage.Hub, "活动中心", "报名 · 签到 · 学时", Icons.Filled.Dashboard),
    PuEntry(PuPage.Activities, "活动列表", "搜索与批量报名", Icons.Filled.EventNote),
    PuEntry(PuPage.Mine, "我的活动", "报名与签到记录", Icons.Filled.History),
    PuEntry(PuPage.Credit, "学时统计", "总量与年度明细", Icons.Filled.WorkspacePremium),
    PuEntry(PuPage.Friends, "好友", "关注与粉丝", Icons.Filled.People),
    PuEntry(PuPage.PersonalCode, "我的二维码", "签到时出示", Icons.Filled.QrCode2),
    PuEntry(PuPage.Scan, "扫一扫", "扫码签到", Icons.Filled.QrCodeScanner),
    PuEntry(PuPage.SignCode, "生成签到码", "主办方动态码", Icons.Filled.AddBox),
)

/**
 * 口袋校园总页：账号卡 + 8 个入口。
 *
 * 契约没有 `onBack`（它是二级抽屉里的根页面），所以这里用自建的 `Scaffold`，
 * 系统返回交给 AppRoot 的 `Dest` 栈处理；右上角「账号」回到账号页。
 * `PuCredStore.loggedIn == false` 时只渲染登录引导，功能格全部置灰不可点，
 * 并且不发起任何网络请求（硬性要求 4）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PuHubScreen(
    onOpen: (PuPage) -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = rememberPuCredStore()
    val loggedIn = store.loggedIn

    var user by remember { mutableStateOf<PuUser?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var authLost by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(loggedIn, retry) {
        if (!loggedIn) {
            user = null
            error = null
            authLost = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        when (val result = puCall(context) { PuActions.me() }) {
            is PuOutcome.Ok -> user = result.value.first
            is PuOutcome.AuthLost -> {
                authLost = true
                user = null
            }
            is PuOutcome.Fail -> error = result.message
        }
        loading = false
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("口袋校园") },
                actions = { TextButton(onClick = onOpenAccount) { Text("账号") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!PuSign.available()) {
                item { SignUnavailableBanner() }
            }

            item {
                when {
                    !loggedIn -> LoginCard(onOpenAccount)
                    authLost -> AuthLostCard(onOpenAccount)
                    loading && user == null -> AccountCardLoading()
                    else -> AccountCard(
                        user = user,
                        error = error,
                        onRetry = { retry++ },
                        onOpenAccount = onOpenAccount,
                    )
                }
            }

            val enabled = loggedIn && !authLost
            item {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    PU_ENTRIES.chunked(4).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            row.forEach { entry ->
                                PuEntryCell(
                                    entry = entry,
                                    enabled = enabled,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpen(entry.page) },
                                )
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            if (!enabled) {
                item {
                    Text(
                        "登录后可查看活动、学时与好友，并使用扫码签到。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignUnavailableBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "本机不支持签名，报名功能不可用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoginCard(onOpenAccount: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("尚未登录口袋校园", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                PU_LOGIN_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onOpenAccount) { Text("去登录") }
        }
    }
}

@Composable
private fun AuthLostCard(onOpenAccount: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                PU_AUTH_LOST,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onOpenAccount) { Text("重新登录") }
        }
    }
}

@Composable
private fun AccountCardLoading() {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "正在读取账号…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountCard(
    user: PuUser?,
    error: String?,
    onRetry: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val name = user?.realname.orEmpty().ifBlank { user?.username.orEmpty() }.ifBlank { "已登录" }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.firstOrNull()?.toString() ?: "口",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val idText = user?.username?.takeIf { it.isNotBlank() }?.let { "学号 ${mask(it)}" }
                Text(
                    idText ?: "已连接口袋校园服务器",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val school = user?.school.orEmpty().ifBlank { "山东管理学院" }
                Text(
                    school,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (error != null && user == null) {
                TextButton(onClick = onRetry) { Text("重试") }
            } else {
                TextButton(onClick = onOpenAccount) { Text("账号") }
            }
        }
        if (error != null && user == null) {
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun PuEntryCell(
    entry: PuEntry,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        modifier
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(54.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    entry.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            entry.title,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            entry.subtitle,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}