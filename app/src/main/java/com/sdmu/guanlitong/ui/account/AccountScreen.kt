package com.sdmu.guanlitong.ui.account

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.ApiClient
import com.sdmu.guanlitong.data.CookieUtils
import com.sdmu.guanlitong.data.LoginMode
import com.sdmu.guanlitong.data.jwxt.JwxtCache
import com.sdmu.guanlitong.data.jwxt.JwxtOrigin
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.ui.components.ServiceBadge
import com.sdmu.guanlitong.ui.components.StatusText
import com.sdmu.guanlitong.ui.format.formatMonthDayTime
import com.sdmu.guanlitong.ui.jwxt.JwxtLoginPanel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    service: SchoolService,
    account: Account,
    serviceUrl: String,
    store: AccountStore,
    onBack: () -> Unit,
    onOpenWeb: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTimetable: (() -> Unit)? = null,
    jwxtSource: JwxtSource = JwxtSource.Official,
    onSelectJwxtSource: ((JwxtSource) -> Unit)? = null,
    grabAccount: Account? = null,
    grabUrl: String = "",
    onGrabLoginChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember(service.id) { mutableStateOf(account.username) }
    var password by remember(service.id) { mutableStateOf(store.passwordOf(service.id).orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember(service.id) { mutableStateOf(account.hasPassword) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    // 官方教务登录后默认只留账号卡片，需要重新登录时再展开验证码表单。
    var jwxtFormOpen by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    // 账号入口只有一个，顶部的登录状态跟随当前数据来源。
    val statusAccount = if (service.id == "jwxt" && jwxtSource == JwxtSource.Grab) {
        grabAccount ?: account
    } else {
        account
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(service.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServiceHeader(service = service, account = statusAccount)

            if (service.id == "jwxt" && onSelectJwxtSource != null) {
                JwxtSourceSection(
                    source = jwxtSource,
                    onSelect = onSelectJwxtSource,
                    grabUrl = grabUrl,
                    grabAccount = grabAccount ?: Account(serviceId = "grab"),
                    store = store,
                    onGrabLoginChanged = onGrabLoginChanged,
                )
            }

            // 第二课堂只用 App 账号，没有网页地址，不需要提示。
            if (serviceUrl.isBlank() && service.id != "pu") {
                WarningCard(
                    text = "这个服务暂时用不了。",
                )
            }

            if (statusAccount.loggedIn) {
                // 账号名和登录方式上面的卡片已经写了，这里只补一个最近登录时间。
                InfoRow(label = "最近登录", value = formatTime(statusAccount.lastLoginAt))
                HorizontalDivider()
            }

            if (service.id == "jwxt" && jwxtSource == JwxtSource.Official) {
                if (account.loggedIn) {
                    LoggedInAccountCard(
                        title = "教务系统账号",
                        name = account.username,
                        onSignOut = {
                            CookieUtils.clearFor(
                                serviceUrl.ifBlank { JwxtOrigin.LOGIN_URL },
                            )
                            JwxtCache.clear(context)
                            store.signOut(service.id, clearPassword = false)
                            jwxtFormOpen = false
                            toast("已退出登录")
                        },
                    )
                    TextButton(
                        onClick = { jwxtFormOpen = !jwxtFormOpen },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (jwxtFormOpen) "收起登录表单" else "重新登录")
                    }
                }
                if (!account.loggedIn || jwxtFormOpen) {
                    JwxtLoginPanel(
                        serviceUrl = serviceUrl.ifBlank { JwxtOrigin.LOGIN_URL },
                        initialUsername = username,
                        initialPassword = password,
                        rememberInitial = rememberPassword,
                        onLoggedIn = { user, pwd, remember, cookies ->
                            CookieUtils.replaceFor(JwxtOrigin.ORIGIN, cookies, "/jwglxt")
                            if (remember) {
                                store.markLoggedIn(
                                    serviceId = service.id,
                                    username = user,
                                    password = pwd,
                                )
                            } else {
                                store.forgetPassword(service.id)
                                store.markLoggedIn(serviceId = service.id, username = user)
                            }
                            username = user
                            rememberPassword = remember
                            jwxtFormOpen = false
                            toast("登录成功")
                        },
                    )
                    HorizontalDivider()
                }
            }

            when {
                service.id == "sdgy" -> SdgyLoginSection(
                    account = account,
                    store = store,
                )

                service.id == "pu" -> PuLoginSection(
                    service = service,
                    account = account,
                    store = store,
                )

                service.loginMode == LoginMode.CREDENTIAL -> CredentialSection(
                    serviceName = service.name,
                    username = username,
                    onUsernameChange = { username = it },
                    password = password,
                    onPasswordChange = { password = it },
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                    rememberPassword = rememberPassword,
                    onRememberPasswordChange = { rememberPassword = it },
                    loading = loading,
                    serviceUrl = serviceUrl,
                    message = message,
                    messageIsError = messageIsError,
                    loggedIn = account.loggedIn,
                    onLogin = {
                        val user = username.trim()
                        if (user.isBlank() || password.isBlank()) {
                            message = "请输入账号和密码"
                            messageIsError = true
                        } else {
                            loading = true
                            message = null
                            scope.launch {
                                val result = ApiClient.login(serviceUrl, user, password)
                                loading = false
                                message = result.message
                                messageIsError = !result.success
                                if (result.success) {
                                    val base = ApiClient.normalizeBaseUrl(serviceUrl)
                                    result.cookie?.let { CookieUtils.inject(base, it) }
                                    if (rememberPassword) {
                                        store.markLoggedIn(
                                            serviceId = service.id,
                                            username = user,
                                            password = password,
                                        )
                                    } else {
                                        store.forgetPassword(service.id)
                                        store.markLoggedIn(serviceId = service.id, username = user)
                                    }
                                    toast("登录成功")
                                }
                            }
                        }
                    },
                    onOpenWeb = onOpenWeb,
                    onSignOut = {
                        if (serviceUrl.isNotBlank()) {
                            CookieUtils.clearFor(ApiClient.normalizeBaseUrl(serviceUrl))
                        }
                        store.signOut(service.id, clearPassword = false)
                        toast("已退出登录")
                    },
                )

                else -> WebSection(
                    service = service,
                    serviceUrl = serviceUrl,
                    loggedIn = account.loggedIn,
                    accountName = account.username,
                    nativeLoginAvailable = jwxtSource == JwxtSource.Official,
                    onOpenWeb = onOpenWeb,
                    onOpenTimetable = onOpenTimetable,
                    onSignOut = {
                        if (serviceUrl.isNotBlank()) {
                            CookieUtils.clearFor(serviceUrl)
                        }
                        if (service.id == "jwxt") {
                            JwxtCache.clear(context)
                        }
                        store.signOut(service.id, clearPassword = true)
                        toast("已退出登录")
                    },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun JwxtSourceSection(
    source: JwxtSource,
    onSelect: (JwxtSource) -> Unit,
    grabUrl: String,
    grabAccount: Account,
    store: AccountStore,
    onGrabLoginChanged: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "数据来源",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            JwxtSource.entries.forEach { option ->
                val selected = option == source
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onSelect(option) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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
        }

        Text(
            text = source.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (source == JwxtSource.Grab) {
            GrabLoginCard(
                grabUrl = grabUrl,
                account = grabAccount,
                store = store,
                onLoggedIn = onGrabLoginChanged,
                onSignedOut = onGrabLoginChanged,
            )
        }
    }
}

@Composable
private fun ServiceHeader(service: SchoolService, account: Account) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = service.accent.copy(alpha = 0.10f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServiceBadge(service = service, size = 56.dp)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = service.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = service.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                StatusText(loggedIn = account.loggedIn)
            }
        }
    }
}

@Composable
private fun CredentialSection(
    serviceName: String,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    rememberPassword: Boolean,
    onRememberPasswordChange: (Boolean) -> Unit,
    loading: Boolean,
    serviceUrl: String,
    message: String?,
    messageIsError: Boolean,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onOpenWeb: () -> Unit,
    onSignOut: () -> Unit,
) {
    // 登录成功后只剩这张卡片和「打开网页」，不再摆一套表单。
    if (loggedIn) {
        LoggedInAccountCard(
            title = "$serviceName 账号",
            name = username,
            onSignOut = onSignOut,
        )
        if (serviceUrl.isNotBlank()) {
            FilledTonalButton(
                onClick = onOpenWeb,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("打开网页")
            }
        }
        return
    }

    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text("账号") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("密码") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onTogglePasswordVisible) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = rememberPassword, onCheckedChange = onRememberPasswordChange)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "记住密码",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (message != null) {
        MessageRow(text = message, isError = messageIsError)
    }

    Button(
        onClick = onLogin,
        enabled = !loading && serviceUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text("登录")
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onOpenWeb,
            enabled = serviceUrl.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("打开网页")
        }
    }

    Text(
        text = "账号密码只用来登录抢课助手。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WebSection(
    service: SchoolService,
    serviceUrl: String,
    loggedIn: Boolean,
    accountName: String,
    nativeLoginAvailable: Boolean = true,
    onOpenWeb: () -> Unit,
    onOpenTimetable: (() -> Unit)?,
    onSignOut: () -> Unit,
) {
    // 登录后这句「怎么登录」就不再需要，卡片已经说明状态。
    if (!loggedIn) {
        Text(
            text = when {
                service.id != "jwxt" ->
                    "用学校统一身份认证登录。"
                nativeLoginAvailable ->
                    "用学号密码登录，验证码会自动识别。"
                else ->
                    "想直接用学校系统，把上方的数据来源切到「官方教务系统」。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (onOpenTimetable != null) {
        FilledTonalButton(
            onClick = onOpenTimetable,
            enabled = serviceUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("查看课表")
        }
    }

    FilledTonalButton(
        onClick = onOpenWeb,
        enabled = serviceUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("打开网页")
    }

    // 官方教务系统在页面上方已经有一张账号卡片，这里不重复摆。
    if (loggedIn && !nativeLoginAvailable) {
        LoggedInAccountCard(
            title = "${service.name}账号",
            name = accountName,
            onSignOut = onSignOut,
        )
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun MessageRow(text: String, isError: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    return formatMonthDayTime(timestamp)
}