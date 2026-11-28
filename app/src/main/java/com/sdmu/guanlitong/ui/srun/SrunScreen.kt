package com.sdmu.guanlitong.ui.srun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.AccountDefaults
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.srun.SrunLoginOutcome
import com.sdmu.guanlitong.data.srun.SrunOnlineStatus
import com.sdmu.guanlitong.data.srun.SrunRepository
import com.sdmu.guanlitong.data.srun.SrunAutoAuthMonitor
import com.sdmu.guanlitong.data.srun.SrunStore
import com.sdmu.guanlitong.data.srun.SrunStatus
import com.sdmu.guanlitong.data.srun.formatSrunBytes
import com.sdmu.guanlitong.data.srun.srunMessage
import com.sdmu.guanlitong.ui.format.formatMonthDayTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 校园网认证页：门户记录的在线状态、账号密码、登录 / 注销。
 *
 * 状态永远现问门户，外加一次真实的联网探测——门户说「在线」不代表能上网。
 * 登录最多试 4 次、中间要等（与 Go 版一致），所以过程里一直显示进度并可取消。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SrunScreen(
    accounts: AccountStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(accounts) { SrunRepository(context.applicationContext, accounts) }
    val store = remember(context) { SrunStore(context) }
    val autoEnabled by store.autoEnabled.collectAsState()
    val scope = rememberCoroutineScope()

    val savedPassword = remember { repository.savedPassword() }
    var username by remember { mutableStateOf(repository.savedUsername) }
    var password by remember { mutableStateOf(savedPassword.orEmpty()) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var rememberPassword by remember {
        mutableStateOf(savedPassword != null || AccountDefaults.REMEMBER_PASSWORD)
    }
    var status by remember { mutableStateOf<SrunStatus?>(null) }
    var statusLoading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(savedPassword == null) }
    var hasSavedPassword by remember { mutableStateOf(savedPassword != null) }
    val online = status?.portal?.online == true

    fun refreshStatus() {
        scope.launch {
            statusLoading = true
            try {
                status = repository.status()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = SrunStatus(SrunOnlineStatus(online = false, detail = e.srunMessage(), failed = true))
            } finally {
                statusLoading = false
            }
        }
    }

    fun saveCredentials(remember: Boolean = rememberPassword) {
        val account = username.trim()
        if (account.isBlank()) {
            message = "填写账号后才会保存"
            messageIsError = true
            return
        }
        // 账号编辑即保存；记住密码勾选时密码同样立即保存，取消只删密码，绝不动门户在线状态。
        accounts.rememberUsername(SrunRepository.SERVICE_ID, account)
        if (remember && password.isNotEmpty()) {
            accounts.rememberPassword(SrunRepository.SERVICE_ID, password)
        } else if (!remember) {
            repository.forgetPassword()
        }
        (context as? com.sdmu.guanlitong.MainActivity)?.syncSrunAutoAuth()
        hasSavedPassword = repository.savedPassword() != null
    }

    fun login() {
        saveCredentials()
        val account = username.trim()
        if (account.isBlank() || password.isBlank()) {
            message = "请先填写账号和密码"
            messageIsError = true
            return
        }
        busy = true
        progress = "正在登录"
        message = null
        running = scope.launch {
            try {
                val outcome = repository.login(account, password, rememberPassword) { attempt, total ->
                    // 第一次不用报次数；从第二次起让人知道还要等多久。
                    progress = if (attempt > 1) "第 $attempt 次尝试登录（共 $total 次）" else "正在登录"
                }
                message = loginMessage(outcome)
                messageIsError = !outcome.online
                if (outcome.online) editing = false
            } catch (e: CancellationException) {
                message = "已取消"
                messageIsError = false
                throw e
            } catch (e: Throwable) {
                message = e.srunMessage()
                messageIsError = true
            } finally {
                busy = false
                progress = null
                running = null
                // 门户那边的记录变了，连同联网探测重新问一遍。
                refreshStatus()
            }
        }
    }

    fun logout() {
        busy = true
        progress = "正在注销"
        message = null
        running = scope.launch {
            try {
                val result = repository.logout(username.trim())
                message = result.message
                messageIsError = !result.ok
            } catch (e: CancellationException) {
                message = "已取消"
                messageIsError = false
                throw e
            } catch (e: Throwable) {
                message = e.srunMessage()
                messageIsError = true
            } finally {
                busy = false
                progress = null
                running = null
                refreshStatus()
            }
        }
    }

    LaunchedEffect(Unit) { refreshStatus() }

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("校园网认证") },
                subtitle = { Text("深澜门户 · ${repository.portalHost}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshStatus() }, enabled = !busy && !statusLoading) {
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
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status = status, loading = statusLoading)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("校园网账号", style = MaterialTheme.typography.titleMedium)
                    if (online && !editing) {
                        CredentialSummary(username = username, hasPassword = hasSavedPassword)
                        TextButton(
                            onClick = { editing = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("编辑并重新登录") }
                    } else {
                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                username = it
                                if (!busy) saveCredentials()
                            },
                            label = { Text("账号") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                if (!busy) saveCredentials()
                            },
                            label = { Text("密码") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = rememberPassword,
                                onCheckedChange = {
                                    rememberPassword = it
                                    saveCredentials(it)
                                },
                                enabled = !busy,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "记住密码",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("连接校园网后自动认证", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "使用已保存的账号密码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoEnabled,
                            enabled = hasSavedPassword && username.isNotBlank(),
                            onCheckedChange = { enabled ->
                                store.setAutoEnabled(enabled)
                                (context as? com.sdmu.guanlitong.MainActivity)?.syncSrunAutoAuth()
                            },
                        )
                    }

                    progress?.let { text ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { running?.cancel() }) { Text("取消") }
                        }
                    }

                    message?.let { text -> MessageRow(text = text, isError = messageIsError) }

                    Button(
                        onClick = { login() },
                        enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("登录校园网")
                    }
                    OutlinedButton(
                        onClick = { logout() },
                        enabled = !busy && username.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("退出校园网")
                    }
                }
            }

        }
    }
}

/** 在线时的默认卡片：不展示密码编辑框，避免把账号管理误当成登录表单。 */
@Composable
private fun CredentialSummary(username: String, hasPassword: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(username, style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (hasPassword) "密码已保存" else "未保存密码",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 登录一轮结束后的一句话：以门户最后的在线记录为准。 */
private fun loginMessage(outcome: SrunLoginOutcome): String = when {
    outcome.online -> "登录成功"
    outcome.accepted -> "门户接受了登录，但还没显示在线。仍不能上网时可先「退出校园网」再登录"
    outcome.lastMessage.isNotBlank() -> outcome.lastMessage
    else -> "登录没成功：${outcome.lastError ?: "门户没有回应"}"
}

@Composable
private fun StatusCard(status: SrunStatus?, loading: Boolean) {
    val portal = status?.portal
    val online = portal?.online == true
    val title = when {
        status == null -> if (loading) "正在查询状态" else "还没查询状态"
        portal == null || portal.failed -> "暂时查不到在线状态"
        online -> "已连接校园网"
        else -> "未认证"
    }
    val subtitle = when {
        status == null -> null
        portal == null || portal.failed -> portal?.detail
        online -> null
        else -> "连上校园网 Wi-Fi 后在这里登录"
    }
    val reachable = status?.reachable

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (online) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                    contentDescription = null,
                    tint = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (online && portal != null) {
                Spacer(modifier = Modifier.height(10.dp))
                StatusRow("账号", portal.userName)
                StatusRow("IP", portal.onlineIp)
                StatusRow("在线设备", portal.deviceTotal)
                StatusRow("上线时间", portal.addTime.toLongOrNull()?.let { formatMonthDayTime(it * 1000) }.orEmpty())
                StatusRow("已用流量", formatSrunBytes(portal.allBytes))
            }
            if (reachable != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (reachable) "联网检测：能上网" else "联网检测：上不了网",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reachable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (online && !reachable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "门户显示在线，但实际不通。可以先「注销」再登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 门户没给的字段（空串或 `<nil>`）不占一行。 */
@Composable
private fun StatusRow(label: String, value: String) {
    val text = value.takeUnless { it.isBlank() || it == "<nil>" } ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MessageRow(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}
