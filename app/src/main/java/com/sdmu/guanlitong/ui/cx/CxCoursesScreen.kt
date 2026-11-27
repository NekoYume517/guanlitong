package com.sdmu.guanlitong.ui.cx

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.cx.CxCourse
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.cxMessage
import com.sdmu.guanlitong.service.CxNotifySchedule
import com.sdmu.guanlitong.service.CxSignRecovery
import com.sdmu.guanlitong.ui.components.rememberRemoteIcon
import com.sdmu.guanlitong.ui.sdgy.SdgyMessage
import kotlinx.coroutines.CancellationException

/** 学习通图片 CDN 不带 Referer 一律 403，课程封面必须带教务域。 */
private const val IMAGE_REFERER = "https://mooc1.chaoxing.com/"

/**
 * 学习通「我的课程」。
 *
 * 只展示课程封面、课程名与教师；点击进入占位详情，
 * 章节 / 任务点 / 签到 / 考试 / 刷课 / 题库 / AI 都不在第一期范围。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CxCoursesScreen(
    onBack: () -> Unit,
    onOpenCourse: (CxCourse) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(context) { CxRepository(context.applicationContext) }

    var courses by remember { mutableStateOf<List<CxCourse>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    // 会话状态不是 Snapshot 状态；这个页面不提供登录/退出，进入后读一次即可。
    val loggedIn = repo.loggedIn

    // 自动签到开关还开着、进程被杀过的话，进学习通页面时把前台服务拉回来。
    LaunchedEffect(Unit) { CxSignRecovery.ensureRunning(context) }

    // 收件箱/提醒的周期任务：开关开着就确保已入队（幂等），全关则取消。
    LaunchedEffect(Unit) { CxNotifySchedule.ensure(context) }

    LaunchedEffect(refreshKey, loggedIn) {
        if (!loggedIn) return@LaunchedEffect
        loading = true
        error = null
        try {
            courses = repo.courses()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.cxMessage()
        } finally {
            loading = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("我的课程") },
                subtitle = { Text("学习通") },
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
            when {
                !loggedIn -> SdgyMessage(
                    text = "请先在「我的 → 学习通」登录",
                    modifier = Modifier.fillMaxSize(),
                )

                loading && courses == null -> CxColdStartLoading(
                    text = "正在读取课程列表",
                    resetKey = refreshKey,
                    onRetry = { refreshKey++ },
                )

                error != null && courses == null -> SdgyMessage(
                    text = error.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { refreshKey++ },
                )

                courses.isNullOrEmpty() -> SdgyMessage(
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
                    error?.let { text ->
                        item {
                            Text(
                                "刷新失败：$text",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                    items(courses.orEmpty(), key = { "${it.courseId}-${it.clazzId}" }) { course ->
                        CxCourseCard(course = course, onClick = { onOpenCourse(course) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CxCourseCard(course: CxCourse, onClick: () -> Unit) {
    val cover = rememberRemoteIcon(course.coverUrl, referer = IMAGE_REFERER)
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
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    course.teacher.ifBlank { "暂无教师信息" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}