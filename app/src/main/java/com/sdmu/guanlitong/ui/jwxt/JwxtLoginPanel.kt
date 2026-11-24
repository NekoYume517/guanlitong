package com.sdmu.guanlitong.ui.jwxt

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sdmu.guanlitong.data.jwxt.CaptchaOcr
import com.sdmu.guanlitong.data.jwxt.JwxtLoginClient
import kotlinx.coroutines.launch

/**
 * 教务系统原生登录面板。
 *
 * 验证码由 ML Kit 离线识别后自动填入，识别不准可以直接改；账号密码默认不落盘，
 * 勾选「记住密码」才会用 Android Keystore 加密保存。
 */
@Composable
fun JwxtLoginPanel(
    serviceUrl: String,
    initialUsername: String,
    initialPassword: String,
    rememberInitial: Boolean,
    onLoggedIn: (username: String, password: String, remember: Boolean, cookies: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenWeb: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val client = remember(serviceUrl) { JwxtLoginClient(serviceUrl) }

    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }
    var passwordVisible by remember { mutableStateOf(false) }
    var remember by remember { mutableStateOf(rememberInitial) }

    var captcha by remember { mutableStateOf<ImageBitmap?>(null) }
    var code by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var captchaLoading by remember { mutableStateOf(true) }
    var captchaNonce by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf(false) }

    var loggingIn by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(client, captchaNonce) {
        captchaLoading = true
        isError = false
        message = null
        // 换图后旧验证码一定失效，先清空，识别成功再填。
        code = ""
        try {
            // 登录页必须在取验证码之前访问：它负责建立会话并下发 csrftoken。
            if (!opened) {
                client.openLoginPage()
                opened = true
            }
            if (client.alreadyLoggedIn) {
                hint = "当前登录状态仍然有效，可以直接返回课表"
                return@LaunchedEffect
            }

            val bytes = client.fetchCaptcha()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            captcha = bitmap?.asImageBitmap()
            captchaLoading = false
            if (bitmap == null) {
                hint = "验证码图片解析失败，请点击刷新"
                return@LaunchedEffect
            }

            hint = "正在识别验证码…"
            val recognized = runCatching { CaptchaOcr.recognize(bytes) }.getOrDefault("")
            if (recognized.length == 6) {
                code = recognized.lowercase()
                hint = "已自动识别：$recognized（可修改）"
            } else if (recognized.isBlank()) {
                hint = "未能自动识别，请手动输入图片中的验证码"
            } else {
                hint = "识别结果「$recognized」不完整，请手动输入验证码"
            }
        } catch (e: Exception) {
            message = e.message ?: "获取验证码失败"
            isError = true
        } finally {
            captchaLoading = false
        }
    }

    fun submit() {
        val user = username.trim()
        val pwd = password
        val captchaCode = code.trim()
        when {
            user.isBlank() || pwd.isBlank() -> {
                message = "请输入账号和密码"
                isError = true
                return
            }

            captchaCode.isBlank() -> {
                message = "请输入验证码"
                isError = true
                return
            }
        }

        scope.launch {
            loggingIn = true
            message = null
            try {
                val result = client.login(user, pwd, captchaCode)
                message = result.message
                isError = !result.success
                if (result.success) {
                    onLoggedIn(user, pwd, remember, result.cookies)
                } else if (result.captchaWrong) {
                    captchaNonce++
                }
            } catch (e: Exception) {
                message = e.message ?: "登录失败"
                isError = true
            } finally {
                loggingIn = false
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "验证码会自动识别，识别不准可以直接改。登录成功后，原生课表与内嵌网页共用这份登录态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("学号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { value ->
                    code = value.filter { it.isLetterOrDigit() }.take(8)
                },
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .width(124.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !captchaLoading) { captchaNonce++ },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val bitmap = captcha
                    when {
                        captchaLoading -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )

                        bitmap != null -> Image(
                            bitmap = bitmap,
                            contentDescription = "验证码，点击刷新",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                        )

                        else -> Text(
                            text = "点击刷新",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            IconButton(
                onClick = { captchaNonce++ },
                enabled = !captchaLoading,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新验证码")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = remember, onCheckedChange = { remember = it })
            Spacer(Modifier.width(10.dp))
            Text(
                text = "记住密码（使用系统密钥库加密）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        message?.let { LoginMessage(text = it, isError = isError) }

        Button(
            onClick = { submit() },
            enabled = !loggingIn && !captchaLoading && captcha != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loggingIn) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(if (loggingIn) "正在登录…" else "登录教务系统")
        }

        if (onOpenWeb != null) {
            TextButton(onClick = onOpenWeb, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("改用网页登录（手动输入验证码）")
            }
        }
    }
}

/** 课表未登录时弹出的原生登录框。 */
@Composable
fun JwxtLoginDialog(
    serviceUrl: String,
    initialUsername: String,
    initialPassword: String,
    rememberInitial: Boolean,
    onDismiss: () -> Unit,
    onLoggedIn: (username: String, password: String, remember: Boolean, cookies: String) -> Unit,
    onOpenWeb: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "登录教务系统",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }

                JwxtLoginPanel(
                    serviceUrl = serviceUrl,
                    initialUsername = initialUsername,
                    initialPassword = initialPassword,
                    rememberInitial = rememberInitial,
                    onLoggedIn = { user, pwd, remember, cookies ->
                        onLoggedIn(user, pwd, remember, cookies)
                        onDismiss()
                    },
                    onOpenWeb = onOpenWeb,
                )
            }
        }
    }
}

@Composable
private fun LoginMessage(text: String, isError: Boolean) {
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