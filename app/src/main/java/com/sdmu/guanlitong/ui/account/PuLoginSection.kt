package com.sdmu.guanlitong.ui.account

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.data.pu.PuCredStore
import com.sdmu.guanlitong.data.pu.PuException
import com.sdmu.guanlitong.data.pu.PuLoginApi
import com.sdmu.guanlitong.data.pu.mask
import com.sdmu.guanlitong.data.pu.puMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 山东管理学院的口袋校园 `school_go_id`（§6.4），允许用户在界面上改。 */
private const val PU_DEFAULT_SCHOOL_ID = "208791531159552"

/** 服务端无文案时 401 的兜底提示（与 ui/pu 的 PU_AUTH_LOST 保持一致）。 */
private const val PU_LOGIN_LOST = "登录已失效，请重新登录"

/**
 * 「我的 → 口袋校园」的账号区，仿 [SdgyLoginSection]。
 *
 * 与山管 App 的差异：
 * - PU 是 **token 授权**（密码只用于换 token），没有 session keeper、没有「被顶号自动重登」，
 *   所以这里只有一张说明卡，不做 `SdgyKickNotice` 那类顶号提示；
 * - 多一个「学校 go_id」输入框（`PuLoginApi.login` 的 `sid` 参数），默认山东管理学院，
 *   换学校/联培单位时改这里；
 * - 界面上**不出现 token 与密码原文**，已登录账号用 `mask()` 显示。
 *
 * 401 语义说明：`PuLoginApi` 把所有 `code != 0` 都抛成 `PuException(code = 401)`，
 * 登录场景下 401 表示「账号密码被服务端拒绝」而不是「会话过期」。所以这里仍然按
 * 硬性要求 5 清掉可能残留的旧 token / 账号态，但提示优先用服务端返回的原因
 * （例如「密码错误」），服务端没给文案时才落回「登录已失效，请重新登录」。
 */
@Composable
fun PuLoginSection(
    service: SchoolService,
    account: Account?,
    store: AccountStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sid = service.id
    val current = account ?: store.accountOf(sid)
    // PuCredStore 由阶段 A 提供（§6.3），内部自己用 applicationContext 落 Keystore。
    val cred = remember(context) { PuCredStore(context.applicationContext) }

    var username by remember(sid) { mutableStateOf(current.username) }
    var password by remember(sid) { mutableStateOf(store.passwordOf(sid).orEmpty()) }
    var schoolId by rememberSaveable(sid) { mutableStateOf(PU_DEFAULT_SCHOOL_ID) }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember(sid) { mutableStateOf(current.hasPassword) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    // 只读快照：token 是否还在 Keystore 里。它不在 snapshot 状态里，
    // 但登录/退出都会改 loading，触发重组时这里能拿到最新值。
    val tokenReady = cred.loggedIn

    fun showError(text: String?) {
        message = text ?: "登录失败"
        messageIsError = true
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TokenNotice()

        if (current.loggedIn) {
            LoggedInCard(service = service, account = current)
        }
        if (current.loggedIn && !tokenReady) {
            StaleTokenCard()
        }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("学号 / 账号") },
            placeholder = { Text("口袋校园登录账号") },
            singleLine = true,
            enabled = !loading,
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

        OutlinedTextField(
            value = schoolId,
            onValueChange = { schoolId = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("学校 go_id") },
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("山东管理学院默认 $PU_DEFAULT_SCHOOL_ID，换校时改成目标学校的 go_id") },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = rememberPassword,
                enabled = !loading,
                onCheckedChange = { checked ->
                    rememberPassword = checked
                    if (checked) {
                        if (password.isNotBlank()) store.rememberPassword(sid, password)
                    } else {
                        store.forgetPassword(sid)
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "记住密码（密钥库加密保存，仅用于本页重新登录）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
                val user = username.trim()
                val pw = password
                val goId = schoolId.trim().toLongOrNull()?.takeIf { it > 0L }
                when {
                    user.isEmpty() -> showError("请输入学号或账号")
                    pw.isEmpty() -> showError("请输入密码")
                    goId == null -> showError("学校 go_id 必须是正整数")
                    else -> scope.launch {
                        loading = true
                        message = null
                        try {
                            val session = PuLoginApi.login(user, pw, goId)
                            // 先落 token，再落账号态：任何一步失败都不会留下「已登录但没有 token」的组合。
                            cred.save(session)
                            if (rememberPassword) {
                                store.rememberPassword(sid, pw)
                            } else {
                                store.forgetPassword(sid)
                            }
                            store.markLoggedIn(
                                serviceId = sid,
                                username = session.account.ifBlank { user },
                                nickname = session.name,
                                password = if (rememberPassword) pw else null,
                            )
                            username = session.account.ifBlank { user }
                            password = if (rememberPassword) pw else ""
                            message = "登录成功，正在同步账号信息"
                            messageIsError = false
                            Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: PuException) {
                            if (e.code == 401) {
                                // 登录被拒：清掉残留的旧 token / 旧账号态，提示用服务端原因。
                                runCatching { cred.clear() }
                                runCatching { store.signOut(sid, clearPassword = false) }
                                showError(e.message?.takeIf { it.isNotBlank() } ?: PU_LOGIN_LOST)
                            } else {
                                showError(e.puMessage())
                            }
                        } catch (e: Throwable) {
                            showError(e.puMessage())
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
            Text(if (current.loggedIn) "重新登录口袋校园" else "登录口袋校园")
        }

        if (current.loggedIn) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        loading = true
                        runCatching { cred.clear() }
                        store.signOut(sid, clearPassword = false)
                        password = ""
                        message = "已退出登录"
                        messageIsError = false
                        loading = false
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("退出登录")
            }
        }

        Text(
            "账号密码只发往口袋校园服务器（api.pocketuni.net）换取 token；" +
                "token 用系统密钥库加密保存，界面不显示 token 原文。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** token 授权说明卡（对应 SdgyLoginSection 的 `SdgyKickNotice` 位置）。 */
@Composable
private fun TokenNotice() {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "token 授权，密码只用于换 token",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "口袋校园的登录接口返回 32 位 token，之后所有请求用它做 Bearer 鉴权；" +
                        "不填「记住密码」时本应用不会保存密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

/** 已登录摘要：不显示 token，账号名走 `mask()`。 */
@Composable
private fun LoggedInCard(service: SchoolService, account: Account) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = service.accent.copy(alpha = 0.16f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        service.icon,
                        contentDescription = null,
                        tint = service.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.nickname.ifBlank { "已登录" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    account.username.takeIf { it.isNotBlank() }?.let { "账号 ${mask(it)}" }
                        ?: "已连接口袋校园服务器",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 账号态是「已登录」但 Keystore 里没有 token（清数据、换机恢复备份等）。 */
@Composable
private fun StaleTokenCard() {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "登录状态已保留，但 token 不在本机",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "重新输入密码登录一次即可恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}