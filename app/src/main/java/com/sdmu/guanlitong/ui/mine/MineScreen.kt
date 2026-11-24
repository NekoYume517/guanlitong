package com.sdmu.guanlitong.ui.mine

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import com.sdmu.guanlitong.ui.components.ServiceBadge
import com.sdmu.guanlitong.ui.components.SectionTitle
import com.sdmu.guanlitong.ui.components.StatusDot
import com.sdmu.guanlitong.ui.update.UpdateState
import com.sdmu.guanlitong.ui.update.updateStatusText

@Composable
fun MineScreen(
    accounts: Map<String, Account>,
    onOpenAccount: (SchoolService) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    jwxtSource: JwxtSource = JwxtSource.Official,
    versionName: String = "",
    updateState: UpdateState = UpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
) {
    val accountServices = ServiceCatalog.accountServices
    // 账号入口只有一个，登录状态跟随当前教务数据来源显示。
    fun statusAccountOf(service: SchoolService): Account? =
        accounts[if (service.id == "jwxt" && jwxtSource == JwxtSource.Grab) "grab" else service.id]

    val loggedInCount = accountServices.count { statusAccountOf(it)?.loggedIn == true }
    var showClearDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ProfileCard(
                loggedInCount = loggedInCount,
                totalCount = accountServices.size,
            )
        }
        item {
            SectionTitle(
                text = "账号管理",
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
        }
        items(accountServices.size) { index ->
            val service = accountServices[index]
            AccountRow(
                service = service,
                account = statusAccountOf(service),
                onClick = { onOpenAccount(service) },
            )
        }
        item {
            SectionTitle(
                text = "应用设置",
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
        }
        item {
            SettingsRow(
                icon = Icons.Outlined.SystemUpdate,
                title = "检查更新",
                subtitle = updateStatusText(updateState, versionName),
                onClick = onCheckUpdate,
            )
        }
        item {
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = "关于管院通",
                subtitle = "非官方校园服务整合工具",
                onClick = { showAboutDialog = true },
            )
        }
        item {
            SettingsRow(
                icon = Icons.Outlined.DeleteOutline,
                title = "清除所有登录状态",
                subtitle = "退出全部账号并清空网页 Cookie",
                destructive = true,
                onClick = { showClearDialog = true },
            )
        }
        item {
            Text(
                text = "管院通 ${versionName.ifBlank { "1.2.3" }} · 仅用于个人学习与校园生活",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除所有登录状态？") },
            text = { Text("所有服务的账号记录和网页登录状态都会被清除，需要重新登录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearAll()
                    },
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于管院通") },
            text = {
                Text(
                    "把学校散落的教务、门户、图书馆和第三方应用收进一个 App。\n\n" +
                        "账号由你在「我的」里自行管理，App 默认不强制登录。\n\n" +
                        "本应用为非官方工具，与山东管理学院官方无关。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun ProfileCard(loggedInCount: Int, totalCount: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "我的校园账号",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "已登录 $loggedInCount / $totalCount 个服务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AccountRow(
    service: SchoolService,
    account: Account?,
    onClick: () -> Unit,
) {
    val loggedIn = account?.loggedIn == true
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServiceBadge(service = service, size = 42.dp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = service.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = account?.displayName?.takeIf { it.isNotBlank() } ?: "未登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusDot(loggedIn = loggedIn)
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}