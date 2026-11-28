package com.sdmu.guanlitong.ui.zhs

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.zhs.ZhsAuthCenter
import com.sdmu.guanlitong.data.zhs.ZhsRepository
import com.sdmu.guanlitong.data.zhs.maskZhsAccount
import com.sdmu.guanlitong.data.zhs.zhsMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 知到会话过期后的自动重登弹窗（AppRoot 顶层，任何页面都能弹）。
 *
 * - 账号从 App 本地账号 / 已保存会话回填，展示时脱敏；
 * - 有记住密码：点「开始验证」→ 易盾滑块 → 自动登录；
 * - 没有记住密码：弹窗内补一次密码（默认勾选记住密码）后走同一流程；
 * - 失败保留弹窗可重试，取消后页面才显示原来的「去重新登录」兜底。
 *
 * 验证组件复用 [ZhsCaptchaDialog]，不另造 WebView。
 */
@Composable
fun ZhsReloginDialog() {
    val state by ZhsAuthCenter.state.collectAsState()
    val context = LocalContext.current
    val repo = remember(context) { ZhsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var verifying by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var account by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    // 每次新的重登请求重置一次输入，避免把上一次的密码留在输入框里。
    LaunchedEffect(state.requested) {
        if (state.requested) {
            verifying = false
            password = ""
            remember = true
            account = state.username
            localError = null
        }
    }

    if (!state.requested) return

    if (verifying) {
        ZhsCaptchaDialog(
            onValidate = { validate ->
                verifying = false
                val pwd = if (state.hasStoredPassword) {
                    ZhsAuthCenter.storedPassword()
                } else {
                    password.ifBlank { null }
                }
                if (pwd.isNullOrBlank()) {
                    ZhsAuthCenter.fail("没有可用的密码，请重新输入后再验证")
                    return@ZhsCaptchaDialog
                }
                val name = if (state.username.isNotBlank()) state.username else account.trim()
                if (name.isBlank()) {
                    ZhsAuthCenter.fail("没有找到知到账号，请前往「我的 → 知到」登录")
                    return@ZhsCaptchaDialog
                }
                scope.launch {
                    ZhsAuthCenter.begin()
                    try {
                        repo.login(
                            account = name,
                            password = pwd,
                            validate = validate,
                            rememberPassword = if (state.hasStoredPassword) true else remember,
                        )
                        ZhsAuthCenter.complete()
                        Toast.makeText(context, "已自动重新登录", Toast.LENGTH_SHORT).show()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ZhsAuthCenter.fail(e.zhsMessage())
                    }
                }
            },
            onCancel = {
                verifying = false
                ZhsAuthCenter.fail("已取消验证，可再次点击「开始验证」")
            },
        )
        return
    }

    val error = localError ?: state.error
    AlertDialog(
        onDismissRequest = { if (!state.busy) ZhsAuthCenter.cancel() },
        title = { Text("重新登录知到") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "知到登录已过期。完成滑块验证后会自动重新登录，成功后当前页面会自动刷新。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.username.isNotBlank()) {
                    Text(
                        "账号：${maskZhsAccount(state.username)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("账号") },
                    )
                }
                if (!state.hasStoredPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("密码（补一次即可自动登录）") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = remember, onCheckedChange = { remember = it })
                        Text("记住密码，下次自动登录", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        "已保存密码，验证通过后自动登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                        Text("正在登录…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy,
                onClick = {
                    localError = null
                    val pwd = if (state.hasStoredPassword) {
                        ZhsAuthCenter.storedPassword()
                    } else {
                        password.ifBlank { null }
                    }
                    if (pwd.isNullOrBlank()) {
                        localError = "请输入密码后再开始验证"
                    } else {
                        verifying = true
                    }
                },
            ) { Text("开始验证") }
        },
        dismissButton = {
            TextButton(
                enabled = !state.busy,
                onClick = { ZhsAuthCenter.cancel() },
            ) { Text("取消") }
        },
    )
}