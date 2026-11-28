package com.sdmu.guanlitong.ui.zhs

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.AccountDefaults
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.data.zhs.ZhsRepository
import com.sdmu.guanlitong.data.zhs.maskZhsAccount
import com.sdmu.guanlitong.data.zhs.zhsMessage
import com.sdmu.guanlitong.ui.account.LoggedInAccountCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 「我的 → 知到」账号区（智慧树）。
 *
 * - 原生表单输入账号密码，点登录后弹出易盾滑块，由真人完成验证；
 * - `validate` 拿到后连同账号密码一次性提交，密码不打印、不写日志；
 * - 「记住密码」默认勾选，用 AccountStore（Android Keystore）加密保存；
 *   接口 401 / 登录过期时据此自动回填账号并重新登录；
 * - 登录后只显示脱敏账号与「退出登录」，退出会清掉会话和本机密码。
 */
@Composable
fun ZhsAccountSection(
    service: SchoolService,
    account: Account?,
    store: AccountStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sid = service.id
    val current = account ?: store.accountOf(sid)
    val repo = remember(context, store) { ZhsRepository(context.applicationContext, store) }
    val savedPassword = remember(sid, account?.hasPassword) {
        store.passwordOf(sid)?.takeIf { it.isNotBlank() }
    }
    val sessionReady = repo.loggedIn

    var accountText by remember(sid) {
        mutableStateOf(repo.account.orEmpty().ifBlank { current.username })
    }
    var password by remember(sid) { mutableStateOf(savedPassword.orEmpty()) }
    var rememberPassword by remember(sid) { mutableStateOf(AccountDefaults.REMEMBER_PASSWORD) }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var captchaVisible by remember { mutableStateOf(false) }
    var showForm by remember(sid) { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    fun showMessage(text: String, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun startLogin(validate: String) {
        loading = true
        message = null
        scope.launch {
            try {
                repo.login(
                    account = accountText.trim(),
                    password = password,
                    validate = validate,
                    rememberPassword = rememberPassword,
                )
                if (!rememberPassword) password = ""
                showForm = false
                showMessage("登录成功", false)
                Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showMessage(e.zhsMessage(), true)
            } finally {
                loading = false
            }
        }
    }

    if (captchaVisible) {
        ZhsCaptchaDialog(
            onValidate = { validate ->
                captchaVisible = false
                startLogin(validate)
            },
            onCancel = {
                captchaVisible = false
                showMessage("已取消滑块验证，可以重新登录。", false)
            },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (repo.loggedIn && !showForm) {
            LoggedInAccountCard(
                title = "知到账号",
                name = maskZhsAccount(repo.account.orEmpty().ifBlank { current.username }),
                onSignOut = {
                    repo.logout()
                    accountText = ""
                    password = ""
                    rememberPassword = false
                    showMessage("已退出登录", false)
                },
            )
            Text(
                if (rememberPassword) {
                    "会话与密码都加密保存在本机；登录过期时会自动弹出验证并回填账号。"
                } else {
                    "会话加密保存在本机；密码没有保存，登录过期时需要手动输入密码。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showForm = true }) { Text("重新登录") }
            return@Column
        }

        if (repo.loggedIn && showForm) {
            Text(
                "重新登录会替换当前会话；完成滑块验证后生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (current.loggedIn && !sessionReady) {
            Text(
                "本机会话已失效，请重新登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = accountText,
            onValueChange = { accountText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("手机号 / 账号") },
            placeholder = { Text("智慧树登录账号") },
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = rememberPassword,
                onCheckedChange = {
                    rememberPassword = it
                    if (!it) repo.forgetPassword()
                },
                enabled = !loading,
            )
            Spacer(Modifier.width(10.dp))
            Text("记住密码", style = MaterialTheme.typography.bodyMedium)
        }

        message?.let { text ->
            Text(
                text = text,
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
                when {
                    accountText.isBlank() -> showMessage("请输入账号", true)
                    password.isEmpty() -> showMessage("请输入密码", true)
                    else -> {
                        message = null
                        captchaVisible = true
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
            Text(if (loading) "正在登录…" else "登录知到")
        }

        Text(
            "点登录后会弹出滑块验证，由你本人完成；App 只把验证结果原样提交给智慧树，" +
                "密码不会写入日志。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}