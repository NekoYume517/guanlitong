package com.sdmu.guanlitong.ui.jwxt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.jwxt.ChosenCourse
import com.sdmu.guanlitong.data.jwxt.ExamItem
import com.sdmu.guanlitong.data.jwxt.GrabLogInfo
import com.sdmu.guanlitong.data.jwxt.GrabTaskInfo
import com.sdmu.guanlitong.data.jwxt.GradeItem
import com.sdmu.guanlitong.data.jwxt.JwxtAccess
import com.sdmu.guanlitong.data.jwxt.JwxtDataSource
import com.sdmu.guanlitong.data.jwxt.JwxtException
import com.sdmu.guanlitong.data.jwxt.JwxtSessionExpiredException
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import com.sdmu.guanlitong.data.jwxt.SelectionCourse
import com.sdmu.guanlitong.data.jwxt.SelectionStatus
import com.sdmu.guanlitong.data.jwxt.TeachingClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── 页面共用的状态与外壳 ────────────────────────────────────

private fun jwxtDataMessage(error: Throwable, fallback: String): String = when {
    error is JwxtSessionExpiredException -> "登录状态已失效，请重新登录"
    error is SocketTimeoutException -> "连接超时，请检查网络后重试"
    error is UnknownHostException || error is ConnectException || error is IOException -> "网络连接失败，请检查网络后重试"
    error is JwxtException -> error.message
        ?.takeUnless { it.isBlank() || it.contains("HTTP") }
        ?: fallback
    else -> fallback
}

private sealed interface DataState<out T> {
    data object Loading : DataState<Nothing>
    data class Ready<T>(val data: T) : DataState<T>
    data class Failed(val message: String) : DataState<Nothing>
}

private class JwxtPageArgs(
    val refreshToken: Int,
    val initialUsername: String,
    val initialPassword: String,
    val rememberInitial: Boolean,
    val onLoginSuccess: (String, String, Boolean, String) -> Unit,
    val onBack: () -> Unit,
    val onOpenWeb: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JwxtPageScaffold(
    args: JwxtPageArgs,
    title: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    snackbar: SnackbarHostState,
    content: @Composable (padding: PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = args.onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = args.onOpenWeb) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "在浏览器打开")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        content(padding)
    }
}

@Composable
private fun LoginRequired(
    args: JwxtPageArgs,
    reason: String,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    loginLabel: String = "登录教务系统",
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.VpnKey,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        if (extra != null) {
            extra()
        } else {
            Button(onClick = onLogin) { Text(loginLabel) }
        }
        TextButton(onClick = args.onOpenWeb) { Text("改用网页登录") }
    }
}

@Composable
private fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorBox(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

// ── 成绩 ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwxtGradesScreen(
    access: JwxtAccess,
    refreshToken: Int,
    initialUsername: String,
    initialPassword: String,
    rememberInitial: Boolean,
    onLoginSuccess: (String, String, Boolean, String) -> Unit,
    onGrabLogin: () -> Unit,
    onBack: () -> Unit,
    onOpenWeb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val origin = remember(access) { access.origin }
    val usingGrab = access.source == JwxtSource.Grab
    val args = JwxtPageArgs(
        refreshToken, initialUsername, initialPassword, rememberInitial,
        onLoginSuccess, onBack, onOpenWeb,
    )
    val snackbar = remember { SnackbarHostState() }
    var attempt by remember { mutableIntStateOf(0) }
    var showLogin by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var state by remember(origin) { mutableStateOf<DataState<List<GradeItem>>>(DataState.Loading) }
    var semester by remember { mutableStateOf("全部") }

    val cookie = remember(access, refreshToken, attempt) { access.cookie() }
    val client = remember(access, cookie) { access.client(cookie) }
    val openLogin = {
        if (usingGrab) onGrabLogin() else showLogin = true
    }

    LaunchedEffect(access, refreshToken, attempt, cookie) {
        if (origin.isBlank()) {
            state = DataState.Failed("这个数据来源暂时不可用")
            return@LaunchedEffect
        }
        if (cookie.isBlank()) {
            state = DataState.Failed(
                if (usingGrab) "尚未登录抢课助手" else "尚未登录教务系统",
            )
            return@LaunchedEffect
        }
        refreshing = true
        state = DataState.Loading
        state = try {
            DataState.Ready(client?.fetchGrades() ?: throw JwxtException("数据来源不可用"))
        } catch (e: JwxtSessionExpiredException) {
            DataState.Failed("登录状态已失效，请重新登录")
        } catch (e: JwxtException) {
            DataState.Failed(jwxtDataMessage(e, "读取成绩失败，请稍后重试"))
        } catch (e: Exception) {
            DataState.Failed(jwxtDataMessage(e, "读取成绩失败，请稍后重试"))
        } finally {
            refreshing = false
        }
    }

    JwxtPageScaffold(
        args = args,
        title = "成绩查询",
        refreshing = refreshing,
        onRefresh = { attempt++ },
        snackbar = snackbar,
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val current = state) {
                DataState.Loading -> LoadingBox()
                is DataState.Failed -> if (current.message.contains("登录")) {
                    LoginRequired(args, current.message, onLogin = openLogin)
                } else {
                    ErrorBox(current.message, onRetry = { attempt++ })
                }

                is DataState.Ready -> {
                    val grades = current.data
                    val semesters = remember(grades) {
                        listOf("全部") + grades.map { it.semester }.distinct()
                    }
                    if (semester !in semesters) semester = "全部"
                    val filtered = if (semester == "全部") grades else grades.filter { it.semester == semester }
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { GradesSummary(filtered) }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(semesters) { label ->
                                    FilterChip(
                                        selected = label == semester,
                                        onClick = { semester = label },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                        if (filtered.isEmpty()) {
                            item {
                                Text(
                                    text = "这个学期还没有成绩",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 20.dp),
                                )
                            }
                        }
                        items(filtered.size) { index ->
                            GradeCard(filtered[index])
                        }
                    }
                }
            }
        }
    }

    if (showLogin && !usingGrab) {
        JwxtLoginDialog(
            serviceUrl = access.officialUrl,
            initialUsername = initialUsername,
            initialPassword = initialPassword,
            rememberInitial = rememberInitial,
            onDismiss = { showLogin = false },
            onLoggedIn = { user, password, remember, cookies ->
                showLogin = false
                onLoginSuccess(user, password, remember, cookies)
            },
            onOpenWeb = { showLogin = false; onOpenWeb() },
        )
    }
}

@Composable
private fun GradesSummary(grades: List<GradeItem>) {
    val numeric = grades.filter { it.numericScore != null }
    val credits = grades.sumOf { it.credit }
    val weighted = numeric.sumOf { (it.numericScore ?: 0.0) * it.credit }
    val weightSum = numeric.sumOf { it.credit }
    val average = if (weightSum > 0) weighted / weightSum else null

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell("加权平均", average?.let { "%.1f".format(it) } ?: "--", Modifier.weight(1f))
            StatCell("学分", "%.1f".format(credits), Modifier.weight(1f))
            StatCell("课程", "${grades.size}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GradeCard(grade: GradeItem) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = grade.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    if (grade.credit > 0) add("%.1f 学分".format(grade.credit))
                    grade.point?.let { add("绩点 %.2f".format(it)) }
                    if (grade.nature.isNotBlank()) add(grade.nature)
                    if (grade.teacher.isNotBlank()) add(grade.teacher)
                }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (grade.semester.isNotBlank()) {
                    Text(
                        text = grade.semester,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = grade.score,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (grade.passed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

// ── 考试 ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwxtExamsScreen(
    access: JwxtAccess,
    refreshToken: Int,
    initialUsername: String,
    initialPassword: String,
    rememberInitial: Boolean,
    onLoginSuccess: (String, String, Boolean, String) -> Unit,
    onGrabLogin: () -> Unit,
    onBack: () -> Unit,
    onOpenWeb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val origin = remember(access) { access.origin }
    val usingGrab = access.source == JwxtSource.Grab
    val args = JwxtPageArgs(
        refreshToken, initialUsername, initialPassword, rememberInitial,
        onLoginSuccess, onBack, onOpenWeb,
    )
    val snackbar = remember { SnackbarHostState() }
    var attempt by remember { mutableIntStateOf(0) }
    var showLogin by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var state by remember(origin) { mutableStateOf<DataState<List<ExamItem>>>(DataState.Loading) }

    val cookie = remember(access, refreshToken, attempt) { access.cookie() }
    val client = remember(access, cookie) { access.client(cookie) }
    val openLogin = {
        if (usingGrab) onGrabLogin() else showLogin = true
    }

    LaunchedEffect(access, refreshToken, attempt, cookie) {
        if (origin.isBlank()) {
            state = DataState.Failed("这个数据来源暂时不可用")
            return@LaunchedEffect
        }
        if (cookie.isBlank()) {
            state = DataState.Failed(
                if (usingGrab) "尚未登录抢课助手" else "尚未登录教务系统",
            )
            return@LaunchedEffect
        }
        refreshing = true
        state = DataState.Loading
        state = try {
            DataState.Ready(client?.fetchExams() ?: throw JwxtException("数据来源不可用"))
        } catch (e: JwxtSessionExpiredException) {
            DataState.Failed("登录状态已失效，请重新登录")
        } catch (e: JwxtException) {
            DataState.Failed(jwxtDataMessage(e, "读取考试安排失败，请稍后重试"))
        } catch (e: Exception) {
            DataState.Failed(jwxtDataMessage(e, "读取考试安排失败，请稍后重试"))
        } finally {
            refreshing = false
        }
    }

    JwxtPageScaffold(
        args = args,
        title = "考试安排",
        refreshing = refreshing,
        onRefresh = { attempt++ },
        snackbar = snackbar,
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val current = state) {
                DataState.Loading -> LoadingBox()
                is DataState.Failed -> if (current.message.contains("登录")) {
                    LoginRequired(args, current.message, onLogin = openLogin)
                } else {
                    ErrorBox(current.message, onRetry = { attempt++ })
                }

                is DataState.Ready -> {
                    val exams = current.data
                    if (exams.isEmpty()) {
                        Text(
                            text = "暂时没有考试安排",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    } else {
                        val now = remember { System.currentTimeMillis() }
                        val fmt = remember { SimpleDateFormat("M月d日", Locale.CHINA) }
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(exams.size) { index ->
                                val exam = exams[index]
                                ExamCard(exam, now, fmt)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLogin && !usingGrab) {
        JwxtLoginDialog(
            serviceUrl = access.officialUrl,
            initialUsername = initialUsername,
            initialPassword = initialPassword,
            rememberInitial = rememberInitial,
            onDismiss = { showLogin = false },
            onLoggedIn = { user, password, remember, cookies ->
                showLogin = false
                onLoginSuccess(user, password, remember, cookies)
            },
            onOpenWeb = { showLogin = false; onOpenWeb() },
        )
    }
}

@Composable
private fun ExamCard(exam: ExamItem, now: Long, fmt: SimpleDateFormat) {
    val status = exam.status(now)
    val statusText = when (status) {
        0 -> "未开始"
        1 -> "进行中"
        else -> "已结束"
    }
    val statusColor = when (status) {
        0 -> MaterialTheme.colorScheme.primary
        1 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    val dateLabel = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(exam.date)
        parsed?.let { fmt.format(it) }
    }.getOrNull()

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = exam.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = buildString {
                        if (!dateLabel.isNullOrBlank()) append(dateLabel)
                        if (dateLabel.isNullOrBlank() && exam.date.isNotBlank()) append("  ").append(exam.date)
                        if (exam.start.isNotBlank()) {
                            append("  ").append(exam.start)
                            if (exam.end.isNotBlank()) append("-").append(exam.end)
                        }
                    }.trim().ifBlank { exam.timeText },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.EventNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = listOf(exam.location, exam.campus, exam.type)
                        .filter { it.isNotBlank() && it != "--" }
                        .joinToString(" · ")
                        .ifBlank { "--" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (exam.teacher.isNotBlank() && exam.teacher != "--") {
                Text(
                    text = "教师 ${exam.teacher}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ── 选课 ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwxtSelectionScreen(
    access: JwxtAccess,
    refreshToken: Int,
    initialUsername: String,
    initialPassword: String,
    rememberInitial: Boolean,
    onLoginSuccess: (String, String, Boolean, String) -> Unit,
    onGrabLogin: () -> Unit,
    onBack: () -> Unit,
    onOpenWeb: () -> Unit,
    modifier: Modifier = Modifier,
    grabLoginContent: (@Composable () -> Unit)? = null,
) {
    val origin = remember(access) { access.origin }
    val usingGrab = access.source == JwxtSource.Grab
    val args = JwxtPageArgs(
        refreshToken, initialUsername, initialPassword, rememberInitial,
        onLoginSuccess, onBack, onOpenWeb,
    )
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var showLogin by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) } // 0 可选 / 1 已选

    var keyword by remember { mutableStateOf("") }
    var searchState by remember { mutableStateOf<DataState<SelectionStatus>>(DataState.Loading) }
    var chosenState by remember { mutableStateOf<DataState<List<ChosenCourse>>>(DataState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val classesByCourse = remember { mutableStateMapOf<String, List<TeachingClass>>() }
    val loadingClasses = remember { mutableStateMapOf<String, Boolean>() }
    var pendingDrop by remember { mutableStateOf<ChosenCourse?>(null) }
    var pendingChoose by remember { mutableStateOf<Pair<SelectionCourse, TeachingClass>?>(null) }
    var pendingTask by remember { mutableStateOf<Pair<SelectionCourse, TeachingClass>?>(null) }
    var working by remember { mutableStateOf(false) }

    val cookie = remember(access, refreshToken, attempt) { access.cookie() }
    val client: JwxtDataSource? = remember(access, cookie) { access.client(cookie) }
    val openLogin = {
        if (usingGrab) onGrabLogin() else showLogin = true
    }

    suspend fun reloadChosen() {
        val c = client ?: return
        chosenState = try {
            DataState.Ready(c.fetchChosen())
        } catch (e: JwxtSessionExpiredException) {
            DataState.Failed("登录状态已失效，请重新登录")
        } catch (e: JwxtException) {
            DataState.Failed(jwxtDataMessage(e, "读取已选课程失败，请稍后重试"))
        } catch (e: Exception) {
            DataState.Failed(jwxtDataMessage(e, "读取已选课程失败，请稍后重试"))
        }
    }

    suspend fun search() {
        val c = client ?: return
        refreshing = true
        searchState = DataState.Loading
        searchState = try {
            DataState.Ready(c.queryCourses(keyword.trim()))
        } catch (e: JwxtSessionExpiredException) {
            DataState.Failed("登录状态已失效，请重新登录")
        } catch (e: JwxtException) {
            DataState.Failed(jwxtDataMessage(e, "查询课程失败，请稍后重试"))
        } catch (e: Exception) {
            DataState.Failed(jwxtDataMessage(e, "查询课程失败，请稍后重试"))
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(access, cookie, attempt) {
        if (client == null) {
            val message = DataState.Failed(
                if (usingGrab) "尚未登录抢课助手" else "尚未登录教务系统",
            )
            searchState = message
            chosenState = message
            return@LaunchedEffect
        }
        search()
        reloadChosen()
    }

    LaunchedEffect(tab, attempt, cookie) {
        if (tab == 1 && client != null && chosenState is DataState.Loading) reloadChosen()
    }

    LaunchedEffect(expanded, client) {
        val course = (searchState as? DataState.Ready)?.data?.courses
            ?.firstOrNull { it.kchId == expanded } ?: return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        if (classesByCourse.containsKey(course.kchId)) return@LaunchedEffect
        loadingClasses[course.kchId] = true
        runCatching { c.classes(course) }
            .onSuccess { classesByCourse[course.kchId] = it }
            .onFailure {
                scope.launch { snackbar.showSnackbar(jwxtDataMessage(it, "读取教学班失败，请稍后重试")) }
            }
        loadingClasses.remove(course.kchId)
    }

    JwxtPageScaffold(
        args = args,
        title = "选课",
        refreshing = refreshing || working,
        onRefresh = { attempt++ },
        snackbar = snackbar,
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("可选课程") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("已选课程") })
            }

            val failed = (searchState as? DataState.Failed)?.message
                ?: (chosenState as? DataState.Failed)?.message
            if (failed != null && failed.contains("登录")) {
                LoginRequired(
                    args = args,
                    reason = failed,
                    onLogin = openLogin,
                    loginLabel = if (usingGrab) "登录抢课助手" else "登录教务系统",
                    // 抢课助手只当数据源：直接把账号密码输入框嵌在选课页里。
                    extra = if (usingGrab) grabLoginContent else null,
                )
            } else if (tab == 0) {
                SelectionAvailable(
                    modifier = Modifier.weight(1f),
                    keyword = keyword,
                    onKeywordChange = { keyword = it },
                    state = searchState,
                    expanded = expanded,
                    onExpand = { expanded = if (expanded == it) null else it },
                    classesByCourse = classesByCourse,
                    loadingClasses = loadingClasses,
                    onSearch = { scope.launch { search() } },
                    onChoose = { course, klass -> pendingChoose = course to klass },
                    grabTasksEnabled = usingGrab,
                    onCreateTask = { course, klass -> pendingTask = course to klass },
                )
            } else {
                ChosenList(
                    modifier = Modifier.weight(1f),
                    state = chosenState,
                    onDrop = { pendingDrop = it },
                    onRetry = { scope.launch { reloadChosen() } },
                )
            }
        }
    }

    pendingChoose?.let { (course, klass) ->
        AlertDialog(
            onDismissRequest = { pendingChoose = null },
            title = { Text("确认选课？") },
            text = {
                Text(
                    "${course.name}\n${klass.time}\n${klass.place}\n" +
                        "教师 ${klass.teacher}",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingChoose = null
                        val c = client ?: return@TextButton
                        working = true
                        scope.launch {
                            val result = c.choose(course.kchId, klass.doJxbId.ifBlank { klass.jxbId }, course.name)
                            snackbar.showSnackbar(result.message)
                            if (result.success) {
                                classesByCourse.remove(course.kchId)
                                expanded = null
                                reloadChosen()
                            }
                            working = false
                        }
                    },
                ) { Text("选课") }
            },
            dismissButton = { TextButton(onClick = { pendingChoose = null }) { Text("取消") } },
        )
    }

    pendingTask?.let { (course, klass) ->
        AlertDialog(
            onDismissRequest = { pendingTask = null },
            title = { Text("创建抢课任务？") },
            text = {
                Text(
                    "${course.name}\n${klass.time} · ${klass.place}\n\n" +
                        "课满时会持续监控余量，一有位置就自动帮你选课；" +
                        "任务创建后可以在「抢课任务」里查看进度或取消。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTask = null
                        val c = client ?: return@TextButton
                        working = true
                        scope.launch {
                            val message = runCatching { c.createTask(course, klass) }
                                .fold({ it.message }, { jwxtDataMessage(it, "创建抢课任务失败，请稍后重试") })
                            snackbar.showSnackbar(message)
                            working = false
                        }
                    },
                ) { Text("创建任务") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTask = null }) { Text("取消") }
            },
        )
    }

    pendingDrop?.let { course ->
        AlertDialog(
            onDismissRequest = { pendingDrop = null },
            title = { Text("确认退课？") },
            text = { Text(course.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDrop = null
                        val c = client ?: return@TextButton
                        working = true
                        scope.launch {
                            var result = c.drop(course.kchId, course.doJxbId.ifBlank { course.jxbId })
                            if (!result.success && course.jxbId.isNotBlank() && course.jxbId != course.doJxbId) {
                                result = c.drop(course.kchId, course.jxbId)
                            }
                            snackbar.showSnackbar(result.message)
                            if (result.success) reloadChosen()
                            working = false
                        }
                    },
                ) { Text("退课") }
            },
            dismissButton = { TextButton(onClick = { pendingDrop = null }) { Text("取消") } },
        )
    }

    if (showLogin && !usingGrab) {
        JwxtLoginDialog(
            serviceUrl = access.officialUrl,
            initialUsername = initialUsername,
            initialPassword = initialPassword,
            rememberInitial = rememberInitial,
            onDismiss = { showLogin = false },
            onLoggedIn = { user, password, remember, cookies ->
                showLogin = false
                onLoginSuccess(user, password, remember, cookies)
            },
            onOpenWeb = { showLogin = false; onOpenWeb() },
        )
    }
}

@Composable
private fun SelectionAvailable(
    modifier: Modifier,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    state: DataState<SelectionStatus>,
    expanded: String?,
    onExpand: (String) -> Unit,
    classesByCourse: Map<String, List<TeachingClass>>,
    loadingClasses: Map<String, Boolean>,
    onSearch: () -> Unit,
    onChoose: (SelectionCourse, TeachingClass) -> Unit,
    grabTasksEnabled: Boolean = false,
    onCreateTask: (SelectionCourse, TeachingClass) -> Unit = { _, _ -> },
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("课程名称或课程号") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = CircleShape,
            )
            FilledTonalButton(onClick = onSearch) { Text("查询") }
        }

        when (state) {
            DataState.Loading -> LoadingBox(modifier = Modifier.weight(1f))
            is DataState.Failed -> ErrorBox(
                message = state.message,
                onRetry = onSearch,
                modifier = Modifier.weight(1f),
            )

            is DataState.Ready -> {
                val status = state.data
                if (!status.open) {
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                    return
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(status.courses.size) { index ->
                        val course = status.courses[index]
                        CourseCard(
                            course = course,
                            expanded = expanded == course.kchId,
                            onExpand = { onExpand(course.kchId) },
                            classes = classesByCourse[course.kchId],
                            loading = loadingClasses[course.kchId] == true,
                            grabTasksEnabled = grabTasksEnabled,
                            onChoose = onChoose,
                            onCreateTask = onCreateTask,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseCard(
    course: SelectionCourse,
    expanded: Boolean,
    onExpand: () -> Unit,
    classes: List<TeachingClass>?,
    loading: Boolean,
    grabTasksEnabled: Boolean,
    onChoose: (SelectionCourse, TeachingClass) -> Unit,
    onCreateTask: (SelectionCourse, TeachingClass) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(
                            course.code.takeIf { it.isNotBlank() }?.let { "课程号 $it" },
                            course.credit.takeIf { it.isNotBlank() }?.let { "$it 学分" },
                        ).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(22.dp)) }
                } else if (classes.isNullOrEmpty()) {
                    Text(
                        text = "没有可用教学班",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    )
                } else {
                    classes.forEach { klass ->
                        TeachingClassRow(
                            klass = klass,
                            grabTaskAvailable = grabTasksEnabled && !klass.canSelect,
                            onChoose = { onChoose(course, klass) },
                            onCreateTask = { onCreateTask(course, klass) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TeachingClassRow(
    klass: TeachingClass,
    grabTaskAvailable: Boolean = false,
    onChoose: () -> Unit,
    onCreateTask: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = klass.time,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(klass.place)
                    if (klass.teacher != "--") append(" · ").append(klass.teacher)
                    if (klass.selected != null && klass.capacity != null) {
                        append(" · ").append(klass.selected).append("/").append(klass.capacity)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (klass.canSelect) {
            FilledTonalButton(onClick = onChoose) { Text("选课") }
        } else {
            Text(
                text = "已满",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            if (grabTaskAvailable) {
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(onClick = onCreateTask) { Text("创建抢课任务") }
            }
        }
    }
}

@Composable
private fun ChosenList(
    modifier: Modifier,
    state: DataState<List<ChosenCourse>>,
    onDrop: (ChosenCourse) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        DataState.Loading -> LoadingBox(modifier)
        is DataState.Failed -> ErrorBox(state.message, onRetry, modifier)

        is DataState.Ready -> {
            if (state.data.isEmpty()) {
                Text(
                    text = "还没有已选课程",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
                return
            }
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.data.size) { index ->
                    val course = state.data[index]
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = course.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = listOf(course.time, course.place, course.teacher)
                                        .filter { it.isNotBlank() && it != "--" }
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,

                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            TextButton(onClick = { onDrop(course) }) { Text("退课") }
                        }
                    }
                }
            }
        }
    }
}

// ── 抢课任务 ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwxtTasksScreen(
    access: JwxtAccess,
    refreshToken: Int,
    onBack: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenSelection: () -> Unit,
    modifier: Modifier = Modifier,
    grabLoginContent: (@Composable () -> Unit)? = null,
) {
    val usingGrab = access.source == JwxtSource.Grab
    val args = JwxtPageArgs(
        refreshToken, "", "", false, { _, _, _, _ -> }, onBack, onOpenWeb,
    )
    val cookie = remember(access, refreshToken) { access.cookie() }
    val client: JwxtDataSource? = remember(access, cookie) { access.client(cookie) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DataState<List<GrabTaskInfo>>>(DataState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var cancelingId by remember { mutableStateOf<Int?>(null) }
    var detailTask by remember { mutableStateOf<GrabTaskInfo?>(null) }

    suspend fun load() {
        val c = client ?: run {
            state = DataState.Failed("尚未登录抢课助手")
            return
        }
        refreshing = true
        state = try {
            DataState.Ready(c.fetchTasks())
        } catch (e: JwxtSessionExpiredException) {
            DataState.Failed("登录状态已失效，请重新登录")
        } catch (e: JwxtException) {
            DataState.Failed(jwxtDataMessage(e, "读取抢课任务失败，请稍后重试"))
        } catch (e: Exception) {
            DataState.Failed(jwxtDataMessage(e, "读取抢课任务失败，请稍后重试"))
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(access, cookie, attempt) { load() }

    JwxtPageScaffold(
        args = args,
        title = "抢课任务",
        refreshing = refreshing || cancelingId != null,
        onRefresh = { attempt++ },
        snackbar = snackbar,
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !usingGrab -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "抢课任务是抢课助手提供的功能",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "当前数据来源是官方教务系统。到「我的 → 教务系统 → 数据来源」切到抢课助手后，" +
                                "在选课页给已满的教学班创建抢课任务。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                client == null -> LoginRequired(
                    args = args,
                    reason = "尚未登录抢课助手",
                    onLogin = { },
                    loginLabel = "登录抢课助手",
                    extra = grabLoginContent,
                )

                else -> when (val current = state) {
                    DataState.Loading -> LoadingBox()

                    is DataState.Failed -> ErrorBox(
                        message = current.message,
                        onRetry = { attempt++ },
                        modifier = Modifier.fillMaxSize(),
                    )

                    is DataState.Ready -> TaskList(
                        tasks = current.data,
                        cancelingId = cancelingId,
                        onCancel = { task ->
                            val c = client ?: return@TaskList
                            cancelingId = task.id
                            scope.launch {
                                val message = runCatching { c.cancelTask(task.id) }
                                    .fold({ it.message }, { jwxtDataMessage(it, "取消任务失败，请稍后重试") })
                                snackbar.showSnackbar(message)
                                cancelingId = null
                                load()
                            }
                        },
                        onOpenDetail = { detailTask = it },
                        onOpenSelection = onOpenSelection,
                    )
                }
            }
        }
    }

    detailTask?.let { task ->
        client?.let { c ->
            TaskDetailDialog(
                client = c,
                initial = task,
                onDismiss = { detailTask = null },
                onChanged = { scope.launch { load() } },
            )
        }
    }
}

private val TASK_FILTERS = listOf(
    "all" to "全部",
    "pending" to "排队中",
    "running" to "抢课中",
    "success" to "已成功",
    "failed" to "失败",
    "cancelled" to "已取消",
)

@Composable
private fun TaskList(
    tasks: List<GrabTaskInfo>,
    cancelingId: Int?,
    onCancel: (GrabTaskInfo) -> Unit,
    onOpenDetail: (GrabTaskInfo) -> Unit,
    onOpenSelection: () -> Unit,
) {
    var statusFilter by remember { mutableStateOf("all") }
    if (tasks.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.HowToReg,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "还没有抢课任务",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "在选课页为满员课程创建任务",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSelection) { Text("去选课") }
        }
        return
    }

    val visible = if (statusFilter == "all") tasks else tasks.filter { it.status == statusFilter }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(TASK_FILTERS.size) { index ->
                val (key, label) = TASK_FILTERS[index]
                FilterChip(
                    selected = statusFilter == key,
                    onClick = { statusFilter = key },
                    label = { Text(label) },
                )
            }
        }
        if (visible.isEmpty()) {
            Text(
                text = "「${TASK_FILTERS.first { it.first == statusFilter }.second}」状态下还没有任务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = "共 ${visible.size} 个任务",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(visible.size) { index ->
                TaskCard(
                    task = visible[index],
                    canceling = cancelingId == visible[index].id,
                    onCancel = { onCancel(visible[index]) },
                    onOpenDetail = { onOpenDetail(visible[index]) },
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: GrabTaskInfo,
    canceling: Boolean,
    onCancel: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val statusLabel = taskStatusLabel(task.status)
    val statusColor = taskStatusColor(task.status)

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.courseName.ifBlank { "未命名课程" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            val detail = listOf(
                task.teacher.takeIf { it.isNotBlank() && it != "--" },
                task.classInfo.takeIf { it.isNotBlank() },
                task.credit.takeIf { it.isNotBlank() }?.let { "$it 学分" },
            ).filterNotNull().joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = buildString {
                val created = task.createdAt.replace('T', ' ').take(16)
                if (created.isNotBlank()) append("创建于 ").append(created)
                if (task.retryCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("已尝试 ").append(task.retryCount).append(" 次")
                }
                if (task.taskType == "scheduled" && task.scheduledTime.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("定时 ").append(task.scheduledTime.replace('T', ' ').take(16))
                }
            }
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (task.resultMessage.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = task.resultMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.status == "failed") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenDetail) { Text("查看日志") }
                if (task.active) {
                    TextButton(onClick = onCancel, enabled = !canceling) {
                        if (canceling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (canceling) "正在取消…" else "取消任务")
                    }
                }
            }
        }
    }
}

private fun taskStatusLabel(status: String): String = when (status) {
    "pending" -> "排队中"
    "running" -> "抢课中"
    "success" -> "已成功"
    "failed" -> "失败"
    "cancelled" -> "已取消"
    "expired" -> "已过期"
    else -> "状态未知"
}

@Composable
private fun taskStatusColor(status: String): Color = when (status) {
    "pending", "running" -> MaterialTheme.colorScheme.primary
    "success" -> Color(0xFF3BA55D)
    "failed" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

private fun taskTime(value: String): String =
    value.replace('T', ' ').take(19).trim()

@Composable
private fun TaskDetailDialog(
    client: JwxtDataSource,
    initial: GrabTaskInfo,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf(initial) }
    var logs by remember { mutableStateOf<List<GrabLogInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var canceling by remember { mutableStateOf(false) }

    // 任务还没结束时每 2 秒刷新一次详情和日志，结束后停在最终状态。
    LaunchedEffect(task.id) {
        while (true) {
            val fresh = runCatching { client.fetchTask(initial.id) }.getOrNull()
            val fetched = runCatching { client.fetchLogs(initial.id) }.getOrNull()
            if (fresh != null) task = fresh
            if (fetched != null) logs = fetched.sortedBy { it.id }
            loaded = true
            if ((fresh ?: task).active.not()) break
            delay(2_000)
        }
    }

    fun refreshOnce() {
        scope.launch {
            runCatching { client.fetchTask(initial.id) }.getOrNull()?.let { task = it }
            runCatching {
                client.fetchLogs(initial.id)
            }.getOrNull()?.let { logs = it.sortedBy { log -> log.id } }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = task.courseName.ifBlank { "任务详情" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = taskStatusLabel(task.status),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = taskStatusColor(task.status),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (task.taskType == "scheduled") "定时抢课" else "立即抢课",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.teacher.isNotBlank() && task.teacher != "--") {
                    DetailLine("教师", task.teacher)
                }
                if (task.classInfo.isNotBlank()) DetailLine("教学班", task.classInfo)
                if (task.credit.isNotBlank()) DetailLine("学分", task.credit)
                DetailLine(
                    "重试",
                    if (task.maxRetries > 0) {
                        "${task.retryCount} / ${task.maxRetries}"
                    } else {
                        "${task.retryCount} 次"
                    },
                )
                if (task.createdAt.isNotBlank()) DetailLine("创建", taskTime(task.createdAt))
                if (task.startedAt.isNotBlank()) DetailLine("开始", taskTime(task.startedAt))
                if (task.completedAt.isNotBlank()) DetailLine("结束", taskTime(task.completedAt))
                if (task.scheduledTime.isNotBlank()) {
                    DetailLine("定时", taskTime(task.scheduledTime))
                }
                if (task.resultMessage.isNotBlank()) DetailLine("结果", task.resultMessage)

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "执行日志",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                when {
                    !loaded -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(22.dp)) }

                    logs.isEmpty() -> Text(
                        text = "还没有日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )

                    else -> logs.forEach { log ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = taskTime(log.createdAt).takeLast(8),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                text = log.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (log.level) {
                                    "error" -> MaterialTheme.colorScheme.error
                                    "success" -> Color(0xFF3BA55D)
                                    "warning" -> Color(0xFFE08A1E)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = if (task.active) {
            {
                TextButton(
                    enabled = !canceling,
                    onClick = {
                        canceling = true
                        scope.launch {
                            runCatching { client.cancelTask(task.id) }
                            canceling = false
                            refreshOnce()
                            onChanged()
                        }
                    },
                ) { Text(if (canceling) "正在取消…" else "取消任务") }
            }
        } else {
            null
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ── 教务首页（原生入口） ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwxtHubScreen(
    access: JwxtAccess,
    onBack: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenTimetable: () -> Unit,
    onOpenGrades: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenSelection: () -> Unit,
    onOpenTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("教务系统") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenWeb) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "在浏览器打开")
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.School,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = access.source.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when (access.source) {
                                JwxtSource.Official -> "使用教务系统账号读取数据"
                                JwxtSource.Grab -> "使用抢课助手读取课表、成绩与选课数据"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            HubEntry(Icons.Outlined.CalendarMonth, "课表", "按周查看课程", onOpenTimetable)
            Spacer(Modifier.height(10.dp))
            HubEntry(Icons.Outlined.Assessment, "成绩查询", "各学期成绩与绩点", onOpenGrades)
            Spacer(Modifier.height(10.dp))
            HubEntry(Icons.Outlined.EventNote, "考试安排", "考试时间与考场", onOpenExams)
            Spacer(Modifier.height(10.dp))
            HubEntry(Icons.Outlined.HowToReg, "选课", "查询课程、选课与退课", onOpenSelection)
            if (access.source == JwxtSource.Grab) {
                Spacer(Modifier.height(10.dp))
                HubEntry(Icons.Outlined.Bolt, "抢课任务", "课程满员后自动等待名额", onOpenTasks)
            }

            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onOpenWeb, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("遇到问题？改用网页版")
            }
        }
    }
}

@Composable
private fun HubEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}