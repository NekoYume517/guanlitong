package com.sdmu.guanlitong.ui.zhs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.CastForEducation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.zhs.ZhsCourse
import com.sdmu.guanlitong.data.zhs.ZhsCourseList
import com.sdmu.guanlitong.data.zhs.ZhsErrorKind
import com.sdmu.guanlitong.data.zhs.ZhsAuthCenter
import com.sdmu.guanlitong.data.zhs.ZhsException
import com.sdmu.guanlitong.data.zhs.ZhsHomework
import com.sdmu.guanlitong.data.zhs.ZhsRepository
import com.sdmu.guanlitong.data.zhs.zhsMessage
import com.sdmu.guanlitong.ui.cx.CxColdStartLoading
import com.sdmu.guanlitong.ui.sdgy.SdgyMessage
import kotlinx.coroutines.CancellationException

/**
 * 知到（智慧树）「我的课程」。
 *
 * 展示共享课 / 翻转课的课程名、教师、学校与学习进度；点开进入课程详情
 * （章节树与学习进度，见 [ZhsCourseDetailScreen]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZhsCoursesScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenCourse: (ZhsCourse) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(context) { ZhsRepository(context.applicationContext) }
    val loggedIn = repo.loggedIn

    var data by remember { mutableStateOf<ZhsCourseList?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expired by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    // 自动重登成功一代 +1：课程与作业一起重新拉取。
    val authGeneration by ZhsAuthCenter.generation.collectAsState()
    // 本机记着账号却已经没有会话（例如会话被清掉）时，同样走自动重登，
    // 不再要求用户绕到「我的 → 知到」手动登录；取消后由下面的按钮兜底。
    val rememberedUsername = remember(loggedIn, authGeneration) {
        ZhsAuthCenter.rememberedUsername()
    }
    LaunchedEffect(loggedIn, authGeneration) {
        if (!loggedIn && rememberedUsername.isNotBlank()) ZhsAuthCenter.requestRelogin()
    }

    var homework by remember { mutableStateOf<List<ZhsHomework>?>(null) }
    var homeworkLoading by remember { mutableStateOf(false) }
    var homeworkError by remember { mutableStateOf<String?>(null) }
    var homeworkKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(tab, refreshKey, homeworkKey, loggedIn, authGeneration) {
        if (!loggedIn || tab != 3) return@LaunchedEffect
        homeworkLoading = true
        homeworkError = null
        val generationAtStart = authGeneration
        try {
            homework = repo.homework()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            homeworkError = e.zhsMessage()
            if ((e as? ZhsException)?.kind == ZhsErrorKind.EXPIRED &&
                authGeneration == generationAtStart
            ) {
                ZhsAuthCenter.requestRelogin()
            }
        } finally {
            homeworkLoading = false
        }
    }

    LaunchedEffect(refreshKey, loggedIn, authGeneration) {
        if (!loggedIn) return@LaunchedEffect
        loading = true
        error = null
        expired = false
        try {
            data = repo.courses()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.zhsMessage()
            expired = (e as? ZhsException)?.kind == ZhsErrorKind.EXPIRED
            if (expired) ZhsAuthCenter.requestRelogin()
        } finally {
            loading = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("我的课程") },
                subtitle = { Text("知到 · 智慧树") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (loggedIn) {
                        IconButton(onClick = { refreshKey++ }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            val list = data
            val shown = when (tab) {
                1 -> list?.shared.orEmpty()
                2 -> list?.flipped.orEmpty()
                else -> (list?.shared.orEmpty() + list?.flipped.orEmpty())
            }
            when {
                !loggedIn -> NotLoggedIn(
                    hasRememberedAccount = rememberedUsername.isNotBlank(),
                    onRelogin = { ZhsAuthCenter.requestRelogin() },
                    onOpenAccount = onOpenAccount,
                )

                tab == 3 -> HomeworkContent(
                    list = homework,
                    loading = homeworkLoading,
                    error = homeworkError,
                    bottomPadding = padding.calculateBottomPadding(),
                    onRetry = { homeworkKey++ },
                    tab = tab,
                    sharedCount = list?.shared?.size ?: 0,
                    flippedCount = list?.flipped?.size ?: 0,
                    onSelect = { tab = it },
                )

                loading && list == null -> CxColdStartLoading(
                    text = "正在读取智慧树课程",
                    resetKey = refreshKey,
                    onRetry = { refreshKey++ },
                )

                error != null && list == null -> {
                    if (expired) {
                        ExpiredMessage(
                            onRelogin = { ZhsAuthCenter.requestRelogin() },
                            onOpenAccount = onOpenAccount,
                        )
                    } else {
                        SdgyMessage(
                            text = error.orEmpty(),
                            modifier = Modifier.fillMaxSize(),
                            onRetry = { refreshKey++ },
                        )
                    }
                }

                list == null || (list.shared.isEmpty() && list.flipped.isEmpty()) -> SdgyMessage(
                    text = "还没有加入任何课程",
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { refreshKey++ },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 24.dp + padding.calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        CourseTabs(
                            tab = tab,
                            sharedCount = list.shared.size,
                            flippedCount = list.flipped.size,
                            onSelect = { tab = it },
                        )
                    }
                    error?.let { text ->
                        item {
                            Text(
                                "刷新失败：$text",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                "这一栏暂时没有课程",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(shown, key = { "${it.shared}-${it.courseId}-${it.recruitId}" }) { course ->
                            ZhsCourseCard(course) { onOpenCourse(course) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotLoggedIn(
    hasRememberedAccount: Boolean,
    onRelogin: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (hasRememberedAccount) {
                "知到需要重新登录，完成人机验证后会使用记住的账号自动登录"
            } else {
                "请先在「我的 → 知到」登录"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (hasRememberedAccount) {
            TextButton(onClick = onRelogin) { Text("重新登录") }
            TextButton(onClick = onOpenAccount) { Text("切换账号") }
        } else {
            TextButton(onClick = onOpenAccount) { Text("去登录") }
        }
    }
}

@Composable
private fun ExpiredMessage(onRelogin: () -> Unit, onOpenAccount: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "登录已过期，完成人机验证后将自动重新登录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRelogin) { Text("重新登录") }
        TextButton(onClick = onOpenAccount) { Text("切换账号") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZhsCourseCard(course: ZhsCourse, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.CastForEducation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    course.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    course.teacher,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(course.school)
                        append(" · ")
                        append(if (course.shared) "共享课" else "翻转课")
                        if (course.smart) append(" · 智慧课")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val progress = course.progress
                if (progress == null) {
                    Text(
                        "进度待更新",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )
                    Text(
                        "已学 $progress%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
@Composable
private fun CourseTabs(
    tab: Int,
    sharedCount: Int,
    flippedCount: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = tab == 0,
            onClick = { onSelect(0) },
            label = { Text("全部") },
        )
        FilterChip(
            selected = tab == 1,
            onClick = { onSelect(1) },
            label = { Text("共享课 $sharedCount") },
        )
        FilterChip(
            selected = tab == 2,
            onClick = { onSelect(2) },
            label = { Text("翻转课 $flippedCount") },
        )
        FilterChip(
            selected = tab == 3,
            onClick = { onSelect(3) },
            label = { Text("作业") },
        )
    }
}

/** 「我的作业」：旧项目仅展示提醒列表（标题、课程、截止时间）。 */
@Composable
private fun HomeworkContent(
    list: List<ZhsHomework>?,
    loading: Boolean,
    error: String?,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onRetry: () -> Unit,
    tab: Int,
    sharedCount: Int,
    flippedCount: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        CourseTabs(
            tab = tab,
            sharedCount = sharedCount,
            flippedCount = flippedCount,
            onSelect = onSelect,
        )
        when {
            loading && list == null -> CxColdStartLoading(
                text = "正在读取作业提醒",
                resetKey = 0,
                onRetry = onRetry,
            )

            error != null && list == null -> SdgyMessage(
                text = error,
                modifier = Modifier.fillMaxSize(),
                onRetry = onRetry,
            )

            list.isNullOrEmpty() -> SdgyMessage(
                text = "暂无作业提醒",
                modifier = Modifier.fillMaxSize(),
                onRetry = onRetry,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 24.dp + bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list) { item -> HomeworkCard(item) }
            }
        }
    }
}

@Composable
private fun HomeworkCard(item: ZhsHomework) {
    val now = System.currentTimeMillis()
    val deadline = item.deadline
    val expired = deadline != null && deadline < now
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Assignment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.courseName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        deadline == null -> "截止时间待公布"
                        expired -> "已截止"
                        else -> "截止 ${formatDeadline(deadline)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (expired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun formatDeadline(epochMs: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
