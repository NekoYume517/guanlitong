package com.sdmu.guanlitong.ui.account

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.data.cx.CxCredStore
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.cxMessage
import com.sdmu.guanlitong.data.cx.maskPhone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 「我的 → 学习通」账号区。
 *
 * 第一期只有手机号 + 密码登录：
 * - 密码不落盘，也不提供「记住密码」；
 * - 登录后只显示脱敏手机号与「退出登录」；
 * - 请求失败（含 401）不会清会话，只有用户主动退出才清。
 */
@Composable
fun CxLoginSection(
    service: SchoolService,
    account: Account?,
    store: AccountStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sid = service.id
    val current = account ?: store.accountOf(sid)
    val repo = remember(context) { CxRepository(context.applicationContext) }

    var phone by remember(sid) { mutableStateOf(repo.phone.orEmpty().ifBlank { current.username }) }
    var password by remember(sid) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    // 只读快照：会话是否还在本机。登录/退出都会改 loading，重组时能拿到最新值。
    val sessionReady = repo.loggedIn

    fun showError(text: String?) {
        message = text ?: "登录失败"
        messageIsError = true
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (current.loggedIn) {
            LoggedInAccountCard(
                title = "学习通账号",
                name = maskPhone(repo.phone ?: current.username),
                onSignOut = {
                    runCatching { repo.signOut() }
                    store.signOut(sid, clearPassword = false)
                    phone = ""
                    password = ""
                    message = "已退出登录"
                    messageIsError = false
                    Toast.makeText(context, "已退出登录", Toast.LENGTH_SHORT).show()
                },
            )

            if (!sessionReady) {
                Text(
                    "本机会话已失效，请重新登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    "密码只用于本次登录，不保存在本机；登录后的会话加密保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
        }

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("手机号") },
            placeholder = { Text("学习通登录手机号") },
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    )
                }
            },
        )

        message?.let { text ->
            Text(
                text,
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
                val user = phone.trim()
                val pw = password
                when {
                    user.isEmpty() -> showError("请输入手机号")
                    pw.isEmpty() -> showError("请输入密码")
                    else -> scope.launch {
                        loading = true
                        message = null
                        try {
                            repo.login(user, pw)
                            store.markLoggedIn(
                                serviceId = sid,
                                username = user,
                                nickname = maskPhone(user),
                            )
                            phone = user
                            password = ""
                            message = "登录成功"
                            messageIsError = false
                            Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            showError(e.cxMessage())
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
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(if (current.loggedIn) "重新登录学习通" else "登录学习通")
        }

        if (!current.loggedIn) {
            Text(
                "密码只用于本次登录，不保存在本机；登录后的会话加密保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}