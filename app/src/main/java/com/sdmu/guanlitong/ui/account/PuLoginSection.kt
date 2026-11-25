package com.sdmu.guanlitong.ui.account

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import com.sdmu.guanlitong.data.pu.PuSchool
import com.sdmu.guanlitong.data.pu.PuSchoolStore
import com.sdmu.guanlitong.data.pu.PuSchoolsApi
import com.sdmu.guanlitong.data.pu.filterSchools
import com.sdmu.guanlitong.data.pu.mask
import com.sdmu.guanlitong.data.pu.puMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 山东管理学院的第二课堂 `school_go_id`（§6.4），用户可在选校对话框里改。 */

/** 服务端无文案时 401 的兜底提示（与 ui/pu 的 PU_AUTH_LOST 保持一致）。 */
private const val PU_LOGIN_LOST = "登录已失效，请重新登录"

/**
 * 「我的 → 第二课堂」的账号区，仿 [SdgyLoginSection]。
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
    val schoolStore = remember(context) { PuSchoolStore(context.applicationContext) }
    var selectedSchool by rememberSaveable(sid) { mutableStateOf(schoolStore.selected()) }
    var schoolDialogOpen by remember { mutableStateOf(false) }
    var schools by remember { mutableStateOf(schoolStore.cachedSchools()) }
    var schoolsLoading by remember { mutableStateOf(false) }
    var schoolsError by remember { mutableStateOf<String?>(null) }
    var schoolQuery by remember { mutableStateOf("") }
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

    // 学校列表一次取全量，本地搜索；打开对话框时才拉取，列表为空才请求。
    LaunchedEffect(schoolDialogOpen) {
        if (!schoolDialogOpen || schools.isNotEmpty()) return@LaunchedEffect
        schoolsLoading = true
        schoolsError = null
        try {
            schools = PuSchoolsApi.fetch(context.applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            schoolsError = e.puMessage()
        } finally {
            schoolsLoading = false
        }
    }

    if (schoolDialogOpen) {
        SchoolSearchDialog(
            schools = schools,
            query = schoolQuery,
            loading = schoolsLoading,
            error = schoolsError,
            selectedGoId = selectedSchool.goId,
            onQueryChange = { schoolQuery = it },
            onSelect = { school ->
                schoolStore.select(school)
                selectedSchool = school
                schoolDialogOpen = false
            },
            onDismiss = { schoolDialogOpen = false },
            onReload = {
                schools = emptyList()
                schoolsError = null
                schoolsLoading = true
                scope.launch {
                    try {
                        schools = PuSchoolsApi.fetch(context.applicationContext)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        schoolsError = e.puMessage()
                    } finally {
                        schoolsLoading = false
                    }
                }
            },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!current.loggedIn) {
            TokenNotice()
        }

        if (current.loggedIn) {
            LoggedInAccountCard(
                title = "第二课堂账号",
                name = current.nickname.ifBlank { current.username },
                onSignOut = {
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
            )
            if (!tokenReady) {
                StaleTokenCard()
            }
            Text(
                "账号密码仅用于登录第二课堂；登录信息加密保存在本机，不会在界面显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("学号 / 账号") },
            placeholder = { Text("第二课堂登录账号") },
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

        SchoolSelectionField(
            schoolName = selectedSchool.name,
            enabled = !loading,
            onClick = { schoolDialogOpen = true },
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
                val goId = selectedSchool.goId.toLongOrNull()?.takeIf { it > 0L }
                when {
                    user.isEmpty() -> showError("请输入学号或账号")
                    pw.isEmpty() -> showError("请输入密码")
                    goId == null -> showError("请选择学校")
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
            Text(if (current.loggedIn) "重新登录第二课堂" else "登录第二课堂")
        }

        Text(
            "账号密码仅用于登录第二课堂；登录信息加密保存在本机，不会在界面显示。",
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
                    "登录后即可使用第二课堂",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "登录信息用于保持第二课堂登录状态；不勾选「记住密码」时，本应用不会保存密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
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
                    "账号已登录，需要重新验证",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "账号信息已保留，重新输入密码登录即可恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
/** 学校只读字段：把点击叠在输入框上，按「选择学校」按钮对待。 */
@Composable
private fun SchoolSelectionField(
    schoolName: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = schoolName,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = true,
            label = { Text("学校") },
            leadingIcon = { Icon(Icons.Outlined.School, contentDescription = null) },
            trailingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            supportingText = { Text("默认山东管理学院，可按校名或拼音首字母搜索更换。") },
            singleLine = true,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = "选择学校",
                    onClick = onClick,
                ),
        )
    }
}

/** 官方学校列表的搜索选择框：全量列表 + 本地过滤，不做服务端搜索。 */
@Composable
private fun SchoolSearchDialog(
    schools: List<PuSchool>,
    query: String,
    loading: Boolean,
    error: String?,
    selectedGoId: String,
    onQueryChange: (String) -> Unit,
    onSelect: (PuSchool) -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
) {
    val filtered = remember(schools, query) { filterSchools(schools, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择学校") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索学校") },
                    placeholder = { Text("校名，或拼音首字母") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
                when {
                    loading && schools.isEmpty() -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }

                    error != null && schools.isEmpty() -> Column(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onReload) { Text("重试") }
                    }

                    filtered.isEmpty() -> Text(
                        if (query.isBlank()) "暂时没有学校列表" else "没有匹配的学校",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )

                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        if (query.isBlank()) {
                            filtered.groupBy { it.group }.forEach { (group, items) ->
                                item(key = "group-$group") {
                                    Text(
                                        group,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                                    )
                                }
                                items(items, key = { it.goId }) { school ->
                                    SchoolRow(school, school.goId == selectedGoId, onSelect)
                                }
                            }
                        } else {
                            items(filtered, key = { it.goId }) { school ->
                                SchoolRow(school, school.goId == selectedGoId, onSelect)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SchoolRow(school: PuSchool, selected: Boolean, onSelect: (PuSchool) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(school.name) },
        trailingContent = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = "当前学校") }
        } else {
            null
        },
        modifier = Modifier.clickable { onSelect(school) },
    )
}
