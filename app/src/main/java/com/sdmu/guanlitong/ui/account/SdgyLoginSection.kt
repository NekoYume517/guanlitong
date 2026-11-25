package com.sdmu.guanlitong.ui.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.sdgy.SdgyClient
import com.sdmu.guanlitong.data.sdgy.SdgyException
import com.sdmu.guanlitong.data.sdgy.SdgyPasswordResetException
import com.sdmu.guanlitong.data.sdgy.SdgySession
import com.sdmu.guanlitong.data.sdgy.SdgySessionManager
import com.sdmu.guanlitong.data.sdgy.SdgySessionState
import com.sdmu.guanlitong.data.sdgy.SdgySessionStore
import com.sdmu.guanlitong.ui.sdgy.sdgyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 山管app 的账号区。
 *
 * 登录会把官方山管 App 的同账号会话顶下线，这是服务端的单会话限制；
 * 掉线后由 [SdgySessionManager] 用保存的密码自动重登，失败也不退出登录。
 */
@Composable
internal fun SdgyLoginSection(
    account: Account,
    store: AccountStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionStore = remember { SdgySessionStore(context) }

    var username by remember { mutableStateOf(account.username) }
    var password by remember { mutableStateOf(store.passwordOf("sdgy").orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(account.hasPassword) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var resetUrl by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf(sessionStore.lastError()) }
    var sessionReady by remember { mutableStateOf(sessionStore.load()?.hasSession == true) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    LaunchedEffect(account.username) {
        if (account.username.isNotBlank() && username.isBlank()) username = account.username
    }
    LaunchedEffect(account.hasPassword) {
        if (account.hasPassword) {
            rememberPassword = true
            if (password.isBlank()) password = store.passwordOf("sdgy").orEmpty()
        }
    }

    // 进入账号页时检查一次会话；失败只记状态，不退出登录。
    LaunchedEffect(Unit) {
        if (!account.loggedIn) return@LaunchedEffect
        checking = true
        try {
            val result = SdgySessionManager.ensure(context, force = true)
            statusError = if (result.state == SdgySessionState.WaitingRetry) result.message else null
            sessionReady = sessionStore.load()?.hasSession == true
        } finally {
            checking = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SdgyKickNotice()

        if (account.loggedIn) {
            when {
                checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在检查登录状态…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                statusError != null -> SdgyRetainedCard(statusError!!)

                !sessionReady -> SdgyRetainedCard("暂时连不上山管，联网后会自动恢复。")
            }
        }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("账号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = rememberPassword,
                onCheckedChange = { checked ->
                    rememberPassword = checked
                    if (checked) {
                        if (password.isNotBlank()) store.rememberPassword("sdgy", password)
                    } else {
                        store.forgetPassword("sdgy")
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "记住密码，被顶号后自动重登",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (messageIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        Button(
            onClick = {
                val user = username.trim()
                if (user.isBlank() || password.isBlank()) {
                    message = "请输入账号和密码"
                    messageIsError = true
                } else {
                    loading = true
                    message = null
                    resetUrl = null
                    scope.launch {
                        val deviceId = sessionStore.deviceId()
                        try {
                            val session = withContext(Dispatchers.IO) {
                                SdgyClient(deviceId).login(user, password)
                            }
                            val saved = finishLogin(
                                session = session,
                                username = user,
                                password = password,
                                remember = rememberPassword,
                                store = store,
                                sessionStore = sessionStore,
                            )
                            sessionReady = saved
                            statusError = null
                            message = if (saved) "登录成功" else "已登录，但没能保存，重启 App 后要重新登录"
                            messageIsError = !saved
                        } catch (e: SdgyPasswordResetException) {
                            resetUrl = e.resetUrl
                            message = "需要先在官方山管 App 重置密码"
                            messageIsError = true
                        } catch (e: SdgyException) {
                            message = e.sdgyMessage()
                            messageIsError = true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: java.io.IOException) {
                            if (rememberPassword) {
                                // 服务器半夜关机时也保留登录状态，等它恢复后自动重登。
                                store.markLoggedIn("sdgy", user, password = password)
                                val text = "现在连不上山管服务器（夜间可能关机），登录没有退出，恢复后会自动重试"
                                sessionStore.setError(text)
                                statusError = text
                                sessionReady = false
                                message = text
                                messageIsError = true
                            } else {
                                message = "连不上山管服务器，联网后再试"
                                messageIsError = true
                            }
                        } catch (e: Exception) {
                            message = e.sdgyMessage()
                            messageIsError = true
                        } finally {
                            loading = false
                        }
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (account.loggedIn) "重新登录山管 App" else "登录山管 App",
            )
        }

        if (account.loggedIn) {
            OutlinedButton(
                onClick = {
                    loading = true
                    message = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                SdgySessionManager.signOut(context)
                            }
                            store.signOut("sdgy", clearPassword = false)
                            sessionReady = false
                            statusError = null
                            message = "已退出登录"
                            messageIsError = false
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("退出登录") }
        }

        Text(
            text = "账号密码只发给学校，密码加密保存在本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    resetUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { resetUrl = null },
            title = { Text("请先重置密码") },
            text = {
                Text(
                    "该账号还没有在官方山管 App 里重置密码，暂时拿不到登录状态。\n\n" +
                        "请先在官方「山管」App 完成密码重置，再回到这里登录。",
                )
            },
            confirmButton = {
                if (url.isNotBlank()) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("山管密码重置链接", url),
                            )
                            resetUrl = null
                            toast("重置链接已复制")
                        },
                    ) { Text("复制重置链接") }
                }
            },
            dismissButton = {
                TextButton(onClick = { resetUrl = null }) { Text("关闭") }
            },
        )
    }
}

private fun finishLogin(
    session: SdgySession,
    username: String,
    password: String,
    remember: Boolean,
    store: AccountStore,
    sessionStore: SdgySessionStore,
): Boolean {
    val saved = sessionStore.save(session)
    if (saved) sessionStore.markOk()
    if (remember) {
        store.markLoggedIn(
            serviceId = "sdgy",
            username = username,
            nickname = session.displayName,
            password = password,
        )
    } else {
        store.forgetPassword("sdgy")
        store.markLoggedIn(
            serviceId = "sdgy",
            username = username,
            nickname = session.displayName,
        )
    }
    return saved
}

@Composable
private fun SdgyKickNotice() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "登录会退出官方山管 App",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "如果又在官方 App 登录，这里掉线后会用保存的密码自动重连。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SdgyRetainedCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "登录状态已保留",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}