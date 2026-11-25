package com.sdmu.guanlitong.ui.account

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
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
import com.sdmu.guanlitong.data.ApiClient
import com.sdmu.guanlitong.data.CookieUtils
import kotlinx.coroutines.launch

/**
 * 抢课助手登录表单。
 *
 * 抢课助手只作为教务数据源，不单独占「我的」里的账号入口；
 * 登录输入框直接嵌在「数据来源 → 抢课助手」和原生选课页里。
 */
@Composable
fun GrabLoginCard(
    grabUrl: String,
    account: Account,
    store: AccountStore,
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
    onSignedOut: () -> Unit = {},
    showTitle: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember(account.username) { mutableStateOf(account.username) }
    var password by remember(account.username, account.hasPassword) {
        mutableStateOf(store.passwordOf("grab").orEmpty())
    }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember(account.username, account.hasPassword) {
        mutableStateOf(account.hasPassword)
    }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    if (account.loggedIn) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "抢课助手账号", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "已登录 ${account.displayName.ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        if (grabUrl.isNotBlank()) {
                            CookieUtils.clearFor(ApiClient.normalizeBaseUrl(grabUrl))
                        }
                        store.signOut("grab", clearPassword = false)
                        toast("已退出抢课助手")
                        onSignedOut()
                    },
                ) { Text("退出登录") }
            }
        }
        return
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showTitle) {
                Text(
                    text = "登录抢课助手",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "用它自己的账号登录，课表、成绩、考试和选课都会从 jwxt.bansonic.top 取数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("抢课助手账号") },
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
                Switch(checked = rememberPassword, onCheckedChange = { rememberPassword = it })
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "记住密码（使用系统密钥库加密）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            message?.let { text ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (messageIsError) {
                            Icons.Outlined.ErrorOutline
                        } else {
                            Icons.Outlined.CheckCircle
                        },
                        contentDescription = null,
                        tint = if (messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }

            Button(
                onClick = {
                    val user = username.trim()
                    when {
                        user.isBlank() || password.isBlank() -> {
                            message = "请输入账号和密码"
                            messageIsError = true
                        }

                        grabUrl.isBlank() -> {
                            message = "还没有配置抢课助手地址"
                            messageIsError = true
                        }

                        else -> {
                            loading = true
                            message = null
                            scope.launch {
                                val result = ApiClient.login(grabUrl, user, password)
                                loading = false
                                message = result.message
                                messageIsError = !result.success
                                if (result.success) {
                                    val base = ApiClient.normalizeBaseUrl(grabUrl)
                                    result.cookie?.let { CookieUtils.inject(base, it) }
                                    if (rememberPassword) {
                                        store.markLoggedIn(
                                            serviceId = "grab",
                                            username = user,
                                            password = password,
                                        )
                                    } else {
                                        store.forgetPassword("grab")
                                        store.markLoggedIn(serviceId = "grab", username = user)
                                    }
                                    toast("登录成功")
                                    onLoggedIn()
                                }
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
                Text("登录抢课助手")
            }
        }
    }
}