package com.sdmu.guanlitong.ui.zhs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.zhs.ZhsAnswer
import com.sdmu.guanlitong.data.zhs.ZhsAnswerPage
import com.sdmu.guanlitong.data.zhs.ZhsChapter
import com.sdmu.guanlitong.data.zhs.ZhsAuthCenter
import com.sdmu.guanlitong.data.zhs.ZhsCourse
import com.sdmu.guanlitong.data.zhs.ZhsCourseDetail
import com.sdmu.guanlitong.data.zhs.ZhsErrorKind
import com.sdmu.guanlitong.data.zhs.ZhsExam
import com.sdmu.guanlitong.data.zhs.ZhsFlatItem
import com.sdmu.guanlitong.data.zhs.ZhsException
import com.sdmu.guanlitong.data.zhs.ZhsHabit
import com.sdmu.guanlitong.data.zhs.ZhsLesson
import com.sdmu.guanlitong.data.zhs.ZhsQaStat
import com.sdmu.guanlitong.data.zhs.ZhsQuestion
import com.sdmu.guanlitong.data.zhs.ZhsReport
import com.sdmu.guanlitong.data.zhs.ZhsRepository
import com.sdmu.guanlitong.data.zhs.ZhsSpeedChoice
import com.sdmu.guanlitong.data.zhs.ZhsStudyProgress
import com.sdmu.guanlitong.data.zhs.ZhsStudyStatus
import com.sdmu.guanlitong.data.zhs.ZhsTaskController
import com.sdmu.guanlitong.data.zhs.ZhsTaskMode
import com.sdmu.guanlitong.data.zhs.ZhsTaskState
import com.sdmu.guanlitong.data.zhs.formatSpan
import com.sdmu.guanlitong.data.zhs.ZhsSummary
import com.sdmu.guanlitong.data.zhs.ZhsVideo
import com.sdmu.guanlitong.data.zhs.progressPercent
import com.sdmu.guanlitong.data.zhs.zhsMessage
import com.sdmu.guanlitong.ui.cx.CxColdStartLoading
import com.sdmu.guanlitong.ui.sdgy.SdgyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val ZHS_DETAIL_TABS = listOf("章节", "成绩", "问答")

/**
 * 知到课程详情：章节树与进度 / 成绩分析 / 课程问答。
 *
 * 三个 tab 的数据各自懒加载：切到哪个 tab 才请求哪个接口，失败只在当前 tab
 * 显示，互不影响；各 tab 的内部状态挂在页面级，来回切换不会重复请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZhsCourseDetailScreen(
    course: ZhsCourse,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    /** 打开独立刷课进度页；开始任务后由页面自己跳转。 */
    onOpenProgress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(context) { ZhsRepository(context.applicationContext) }

    var tab by remember { mutableIntStateOf(0) }
    val chapters = remember(course) { Loader<ZhsCourseDetail>() }
    val report = remember(course) { Loader<ZhsReport>() }
    val qa = remember(course) { QaState() }
    val brush = remember(course) { BrushState() }

    AutoLoad(tab == 0, chapters) { repo.courseDetail(course) }
    AutoLoad(tab == 1, report) { repo.report(course) }

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(course.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                subtitle = {
                    Text(
                        buildString {
                            append(course.teacher)
                            if (course.school.isNotBlank()) append(" · ").append(course.school)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            when (tab) {
                                0 -> chapters.retry()
                                1 -> report.retry()
                                else -> qa.retry()
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding(),
        ) {
            TabRow(selectedTabIndex = tab) {
                ZHS_DETAIL_TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { if (tab != index) tab = index },
                        text = { Text(title, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            val bottomPadding = padding.calculateBottomPadding()
            when (tab) {
                0 -> ChaptersContent(
                    course = course,
                    loader = chapters,
                    brush = brush,
                    onOpenProgress = onOpenProgress,
                    onOpenAccount = onOpenAccount,
                    bottomPadding = bottomPadding,
                )
                1 -> ReportContent(course, report, onOpenAccount, bottomPadding)
                else -> QaContent(course, repo, qa, onOpenAccount, bottomPadding)
            }
        }
    }
}

// ---------------------------------------------------------------- 通用状态

/** 一个懒加载结果：数据、加载中、错误、是否过期、待加载与重试计数。 */
private class Loader<T> {
    var data by mutableStateOf<T?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var expired by mutableStateOf(false)
    var pending by mutableStateOf(true)
    var retryKey by mutableIntStateOf(0)

    fun retry() {
        if (loading) return
        pending = true
        retryKey++
    }
}

private class QaState {
    var newest by mutableStateOf(false)
    var questions by mutableStateOf<List<ZhsQuestion>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var expired by mutableStateOf(false)
    var pending by mutableStateOf(true)
    var retryKey by mutableIntStateOf(0)

    var open by mutableStateOf<ZhsQuestion?>(null)
    var answers by mutableStateOf<ZhsAnswerPage?>(null)
    var answersLoading by mutableStateOf(false)
    var answersError by mutableStateOf<String?>(null)
    var answersKey by mutableIntStateOf(0)

    var draft by mutableStateOf("")
    var submitting by mutableStateOf(false)
    var submitError by mutableStateOf<String?>(null)
    var submitNotice by mutableStateOf<String?>(null)

    fun switchMode(newestMode: Boolean) {
        if (newest == newestMode) return
        newest = newestMode
        questions = null
        error = null
        expired = false
        pending = true
        retryKey++
    }

    fun openQuestion(question: ZhsQuestion) {
        open = question
        answers = null
        answersError = null
        draft = ""
        submitError = null
        submitNotice = null
        answersKey++
    }

    fun closeQuestion() {
        open = null
        answers = null
        answersError = null
        draft = ""
        submitError = null
        submitNotice = null
    }

    fun retry() {
        if (loading) return
        pending = true
        retryKey++
    }
}

/** 首次进入或重试时加载；同一结果不会被重复请求。 */
@Composable
private fun <T> AutoLoad(active: Boolean, loader: Loader<T>, call: suspend () -> T) {
    val authGeneration by ZhsAuthCenter.generation.collectAsState()
    var seenGeneration by remember { mutableIntStateOf(authGeneration) }
    LaunchedEffect(active, loader.retryKey, authGeneration) {
        if (!active) return@LaunchedEffect
        // 自动重登成功一代 +1：即使数据已在，也强制重新拉一次。
        val force = authGeneration != seenGeneration
        seenGeneration = authGeneration
        if (!loader.pending && !force) return@LaunchedEffect
        loader.pending = false
        loader.loading = true
        loader.error = null
        loader.expired = false
        val generationAtStart = authGeneration
        try {
            loader.data = call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loader.error = e.zhsMessage()
            loader.expired = (e as? ZhsException)?.kind == ZhsErrorKind.EXPIRED
            // 失败在自动重登成功之后才回来时，不要用旧请求再拉起一次弹窗。
            if (loader.expired && authGeneration == generationAtStart) {
                ZhsAuthCenter.requestRelogin()
            }
        } finally {
            loader.loading = false
        }
    }
}

/** 问答列表加载（问题列表与 [QaState] 的其他字段共用 pending/retry）。 */
@Composable
private fun AutoLoadQuestions(qa: QaState, call: suspend () -> List<ZhsQuestion>) {
    val authGeneration by ZhsAuthCenter.generation.collectAsState()
    var seenGeneration by remember { mutableIntStateOf(authGeneration) }
    LaunchedEffect(qa.retryKey, authGeneration) {
        // 自动重登成功后强制重拉，即使问题列表已经在。
        val force = authGeneration != seenGeneration
        seenGeneration = authGeneration
        if (!qa.pending && !force) return@LaunchedEffect
        qa.pending = false
        qa.loading = true
        qa.error = null
        qa.expired = false
        val generationAtStart = authGeneration
        try {
            qa.questions = call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            qa.error = e.zhsMessage()
            qa.expired = (e as? ZhsException)?.kind == ZhsErrorKind.EXPIRED
            if (qa.expired && authGeneration == generationAtStart) {
                ZhsAuthCenter.requestRelogin()
            }
        } finally {
            qa.loading = false
        }
    }
}

// ---------------------------------------------------------------- 章节

/**
 * 章节页的刷课选择（分钟数、倍速）。任务本身由 [ZhsTaskController] 进程内单例持有：
 * 切 tab、离开页面、点通知回来都不影响后台任务；运行中的实时进度从它的 StateFlow 读。
 */
private class BrushState {
    /** 定时刷课的本次目标分钟数。 */
    var minutes by mutableStateOf(25)

    /** 开始前选的倍速；运行中以控制器里的档位为准。 */
    var speed by mutableStateOf(ZhsTaskController.speedChoice)
}

@Composable
private fun ChaptersContent(
    course: ZhsCourse,
    loader: Loader<ZhsCourseDetail>,
    brush: BrushState,
    onOpenProgress: () -> Unit,
    onOpenAccount: () -> Unit,
    bottomPadding: Dp,
) {
    val detail = loader.data
    val chapters = detail?.chapters.orEmpty()
    val context = LocalContext.current
    val task by ZhsTaskController.state.collectAsState()
    val running = task.running
    // 本课程的刷课结束（含单节）后刷新章节学时，和旧项目「结束刷新列表」一致。
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(task.running, task.courseId) {
        if (wasRunning && !task.running && task.courseId == course.courseId) loader.retry()
        wasRunning = task.running
    }
    when {
        loader.loading && detail == null -> CxColdStartLoading(
            text = "正在读取章节与学习进度",
            resetKey = loader.retryKey,
            onRetry = loader::retry,
        )

        loader.error != null && detail == null -> ErrorOrExpired(
            message = loader.error.orEmpty(),
            expired = loader.expired,
            onOpenAccount = onOpenAccount,
            onRetry = loader::retry,
        )

        detail == null || chapters.isEmpty() -> SdgyMessage(
            text = "这门课暂时没有章节数据",
            modifier = Modifier.fillMaxSize(),
            onRetry = loader::retry,
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 24.dp + bottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") { SummaryCard(course, detail.summary) }
            item(key = "brush") {
                BrushCard(
                    state = brush,
                    task = task,
                    courseId = course.courseId,
                    onStart = {
                        ZhsTaskController.startBatch(
                            context = context,
                            course = course,
                            chapters = chapters,
                            minutes = brush.minutes.coerceIn(1, 600),
                            choice = brush.speed,
                        )
                        onOpenProgress()
                    },
                    onOpenProgress = onOpenProgress,
                    onOpenAccount = onOpenAccount,
                )
            }
            loader.error?.let { text ->
                item(key = "error") {
                    Text(
                        "刷新失败：$text",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            itemsIndexed(chapters) { index, chapter ->
                ChapterCard(
                    chapter = chapter,
                    index = index,
                    running = running,
                    progress = task.items,
                    onBrushItem = { item ->
                        ZhsTaskController.startItem(
                            context = context,
                            course = course,
                            item = item,
                            choice = brush.speed,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorOrExpired(
    message: String,
    expired: Boolean,
    onOpenAccount: () -> Unit,
    onRetry: () -> Unit,
) {
    if (expired) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "登录已过期，请重新登录知到",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onOpenAccount) { Text("去重新登录") }
        }
    } else {
        SdgyMessage(text = message, modifier = Modifier.fillMaxSize(), onRetry = onRetry)
    }
}

@Composable
private fun SummaryCard(course: ZhsCourse, summary: ZhsSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (course.shared) "共享课" else "翻转课",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (course.smart) {
                    Text(
                        "智慧课",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val percent = if (summary.totalVideos > 0) {
                summary.percent
            } else {
                course.progress ?: 0
            }
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (summary.totalVideos > 0) {
                        "已看 ${summary.watchedVideos}/${summary.totalVideos} 个视频"
                    } else {
                        "视频进度待更新"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (summary.totalSec > 0) {
                        "${formatSeconds(summary.watchedSec)} / ${formatSeconds(summary.totalSec)}"
                    } else {
                        "$percent%"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BrushCard(
    state: BrushState,
    task: ZhsTaskState,
    courseId: Long,
    onStart: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val mine = task.mode != ZhsTaskMode.NONE && task.courseId == courseId
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "定时刷课",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "自动刷完未看完的视频，到目标学时后按最短视频反复刷；弹题自动作答，" +
                    "遇到滑块验证立即停止。实时进度在独立页面查看，运行中可随时改倍速。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (task.running) {
                if (mine) {
                    LinearProgressIndicator(
                        progress = { task.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "新增 ${formatSpan(task.gained)}" +
                            if (task.target > 0) " / 目标 ${formatSpan(task.target)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        task.currentName.ifBlank { "正在准备…" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "已刷 ${task.lessonsDone}/${task.totalLessons} 节 · 倍速 ${task.speedText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "另一门课的刷课正在进行：${task.courseName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenProgress) { Text("查看进度") }
                    TextButton(onClick = { ZhsTaskController.stop() }) { Text("停止") }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = state.minutes.toString(),
                        onValueChange = { value ->
                            val digits = value.filter(Char::isDigit).take(3)
                            if (digits.isNotEmpty()) {
                                state.minutes = digits.toInt().coerceIn(1, 600)
                            }
                        },
                        modifier = Modifier.width(104.dp),
                        singleLine = true,
                        label = { Text("本次") },
                        suffix = { Text("分钟") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(
                        "刷到达标或全部刷完",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SpeedPicker(
                    state.speed,
                    enabled = true,
                    onSelect = { state.speed = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                        Text("开始定时刷课")
                    }
                    if (task.mode != ZhsTaskMode.NONE) {
                        TextButton(onClick = onOpenProgress) { Text("上次进度") }
                    }
                }
            }
            if (!task.running && mine) {
                task.summary?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.captcha) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                task.error?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (task.expired) {
                        TextButton(onClick = onOpenAccount) { Text("去重新登录") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(
    chapter: ZhsChapter,
    index: Int,
    running: Boolean,
    progress: Map<Pair<Long, Long>, ZhsStudyProgress>,
    onBrushItem: (ZhsFlatItem) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "第 ${index + 1} 章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    chapter.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            chapter.lessons.forEach { lesson ->
                if (lesson.depth == 3) {
                    Text(
                        lesson.name,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    lesson.videos.forEach { video ->
                        VideoRow(
                            video = video,
                            progress = progress[lesson.lessonId to video.lessonVideoId],
                            enabled = !running,
                            onBrush = { onBrushItem(flatItem(chapter, lesson, video)) },
                        )
                    }
                } else {
                    LessonRow(
                        lesson = lesson,
                        progress = progress[lesson.lessonId to 0L],
                        enabled = !running && !lesson.noVideo && lesson.videoId > 0,
                        onBrush = { onBrushItem(flatItem(chapter, lesson, null)) },
                    )
                }
            }
        }
    }
}

/** 章节行 → 刷课条目；videoId 先用 lessonVideoId 兜底，客户端会补 realVideoId 映射。 */
private fun flatItem(chapter: ZhsChapter, lesson: ZhsLesson, video: ZhsVideo?): ZhsFlatItem =
    if (video != null) {
        ZhsFlatItem(
            chapterId = chapter.id,
            lessonId = lesson.lessonId,
            lessonVideoId = video.lessonVideoId,
            videoId = video.lessonVideoId,
            sec = video.sec,
            name = video.name,
            studyTime = video.studyTime,
            watched = video.watched,
        )
    } else {
        ZhsFlatItem(
            chapterId = chapter.id,
            lessonId = lesson.lessonId,
            lessonVideoId = 0,
            videoId = lesson.videoId,
            sec = lesson.sec,
            name = lesson.name,
            studyTime = lesson.studyTime,
            watched = lesson.watched,
        )
    }

@Composable
private fun LessonRow(
    lesson: ZhsLesson,
    progress: ZhsStudyProgress?,
    enabled: Boolean,
    onBrush: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(watched = lesson.watched, noVideo = lesson.noVideo)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                lesson.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                lessonNote(lesson.watched, lesson.sec, lesson.studyTime, lesson.noVideo),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!lesson.noVideo && lesson.videoId > 0) BrushAction(progress, enabled, onBrush)
    }
}

@Composable
private fun VideoRow(
    video: ZhsVideo,
    progress: ZhsStudyProgress?,
    enabled: Boolean,
    onBrush: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 14.dp, top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(watched = video.watched, noVideo = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                video.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                lessonNote(video.watched, video.sec, video.studyTime, false),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BrushAction(progress, enabled, onBrush)
    }
}

/** 每节的刷课按钮与实时进度。 */
@Composable
private fun BrushAction(progress: ZhsStudyProgress?, enabled: Boolean, onBrush: () -> Unit) {
    when (progress?.status) {
        ZhsStudyStatus.RUNNING, null -> {
            if (progress != null && progress.status == ZhsStudyStatus.RUNNING) {
                Text(
                    "${progress.percent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(
                onClick = onBrush,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("刷课", style = MaterialTheme.typography.labelMedium)
            }
        }
        ZhsStudyStatus.DONE -> {
            if (progress.addedSec > 0) {
                Text(
                    "+${progress.addedSec}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(
                onClick = onBrush,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("再刷", style = MaterialTheme.typography.labelMedium)
            }
        }
        ZhsStudyStatus.CAPTCHA -> {
            Text(
                "需验证",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StatusIcon(watched: Boolean, noVideo: Boolean) {
    val (icon, tint) = when {
        noVideo -> Icons.Outlined.RemoveCircleOutline to MaterialTheme.colorScheme.outline
        watched -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
        else -> Icons.Outlined.PlayCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
}

private fun lessonNote(watched: Boolean, sec: Int, studyTime: Int, noVideo: Boolean): String = when {
    noVideo -> "无视频"
    watched -> "已完成 · ${formatSeconds(sec)}"
    sec > 0 -> "已学 ${formatSeconds(studyTime)} / ${formatSeconds(sec)}"
    studyTime > 0 -> "已学 ${formatSeconds(studyTime)}"
    else -> "未开始"
}

/** 秒 → `h:mm:ss` / `m:ss`。 */
internal fun formatSeconds(total: Int): String {
    val safe = total.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

// ---------------------------------------------------------------- 成绩

@Composable
private fun ReportContent(
    course: ZhsCourse,
    loader: Loader<ZhsReport>,
    onOpenAccount: () -> Unit,
    bottomPadding: Dp,
) {
    val report = loader.data
    when {
        loader.loading && report == null -> CxColdStartLoading(
            text = "正在读取成绩",
            resetKey = loader.retryKey,
            onRetry = loader::retry,
        )

        loader.error != null && report == null -> ErrorOrExpired(
            message = loader.error.orEmpty(),
            expired = loader.expired,
            onOpenAccount = onOpenAccount,
            onRetry = loader::retry,
        )

        report == null -> SdgyMessage(
            text = "暂时没有成绩数据",
            modifier = Modifier.fillMaxSize(),
            onRetry = loader::retry,
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 24.dp + bottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "total") { TotalScoreCard(report, course) }
            if (report.learnProgress != null || report.avgProgress != null) {
                item(key = "progress") { ProgressSection(report) }
            }
            if (report.habit != null) {
                item(key = "habit") { HabitCard(report.habit, report.courseStartTime, report.courseEndTime) }
            }
            if (report.qaStat != null) {
                item(key = "qa") { QaScoreCard(report.qaStat) }
            }
            if (report.knowledgeMaster != null) {
                item(key = "master") { KnowledgeCard(report.knowledgeMaster) }
            }
            item(key = "exams") { ExamsSection(report.exams) }
        }
    }
}

@Composable
private fun TotalScoreCard(report: ZhsReport, course: ZhsCourse) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "课程总分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatScore(report.totalScore),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    if (course.shared) "共享课" else "翻转课",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val parts = listOf(
                "平时成绩" to report.usualScore,
                "考试成绩" to report.examScore,
                "见面课" to report.meetScore,
                "论坛分" to report.bbsScore,
                "期末成绩" to report.finalScore,
                "课程成绩" to report.courseScore,
            ).filter { it.second != null }
            if (parts.isNotEmpty()) {
                HorizontalDivider()
                parts.forEach { (label, value) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatScore(value),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressSection(report: ZhsReport) {
    val mine = progressPercent(report.learnProgress)
    val average = progressPercent(report.avgProgress)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("学习进度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            ProgressBar(label = "我的进度", percent = mine, emphasized = true)
            ProgressBar(label = "班级平均", percent = average, emphasized = false)
        }
    }
}

@Composable
private fun ProgressBar(label: String, percent: Int?, emphasized: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                percent?.let { "$it%" } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
        LinearProgressIndicator(
            progress = { (percent ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )
    }
}

@Composable
private fun HabitCard(habit: ZhsHabit, start: String, end: String) {
    InfoCard(title = "学习习惯") {
        InfoRow("习惯分", formatScore(habit.score))
        InfoRow("规律学习", "${habit.regularDays} 天 / 需要 ${habit.needDays} 天")
        InfoRow("建议每日", "${habit.suggestTime} 分钟")
        InfoRow("累计学习", "${habit.totalStudyDay} 天")
        if (start.isNotBlank() || end.isNotBlank()) {
            InfoRow("课程时间", listOf(start, end).filter { it.isNotBlank() }.joinToString(" ~ "))
        }
    }
}

@Composable
private fun QaScoreCard(stat: ZhsQaStat) {
    InfoCard(title = "问答互动") {
        InfoRow("问答分", formatScore(stat.score))
        InfoRow("我的提问 / 回答", "${stat.myAsk} / ${stat.myAnswer}")
        if (stat.validAsk != null || stat.validAnswer != null) {
            InfoRow(
                "有效提问 / 有效回答",
                "${stat.validAsk ?: 0} / ${stat.validAnswer ?: 0}",
            )
        }
        InfoRow("老师回答", "${stat.teacherAnswer}")
        if (stat.hotQuestion.isNotBlank()) {
            InfoRow("热门问题", stat.hotQuestion)
        }
        if (stat.hotAnswer.isNotBlank()) {
            InfoRow("热门回答", stat.hotAnswer)
        }
    }
}

@Composable
private fun KnowledgeCard(master: Double) {
    val percent = progressPercent(master)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("知识点掌握", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            ProgressBar(label = "掌握度", percent = percent, emphasized = true)
        }
    }
}

@Composable
private fun ExamsSection(exams: List<ZhsExam>) {
    InfoCard(title = "考试记录") {
        if (exams.isEmpty()) {
            Text(
                "暂无考试记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            exams.forEach { exam ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            exam.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val note = listOfNotNull(exam.note, exam.status)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                        if (note.isNotBlank()) {
                            Text(
                                note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        exam.score ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分数按整数/一位小数显示，未知用「—」。 */
internal fun formatScore(value: Double?): String {
    if (value == null || value.isNaN()) return "—"
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(java.util.Locale.CHINA, "%.1f", value)
    }
}

// ---------------------------------------------------------------- 问答

@Composable
private fun QaContent(
    course: ZhsCourse,
    repo: ZhsRepository,
    qa: QaState,
    onOpenAccount: () -> Unit,
    bottomPadding: Dp,
) {
    AutoLoadQuestions(qa) { repo.questions(course, qa.newest).questions }
    val open = qa.open
    if (open != null) {
        AnswerContent(course, repo, qa, open, bottomPadding)
    } else {
        QuestionList(course, qa, onOpenAccount, bottomPadding)
    }
}

@Composable
private fun QuestionList(
    course: ZhsCourse,
    qa: QaState,
    onOpenAccount: () -> Unit,
    bottomPadding: Dp,
) {
    val questions = qa.questions
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "hot") {
                FilterChip(
                    selected = !qa.newest,
                    onClick = { qa.switchMode(false) },
                    label = { Text("最热") },
                )
            }
            item(key = "new") {
                FilterChip(
                    selected = qa.newest,
                    onClick = { qa.switchMode(true) },
                    label = { Text("最新") },
                )
            }
        }
        when {
            qa.loading && questions == null -> CxColdStartLoading(
                text = "正在读取问答",
                resetKey = qa.retryKey,
                onRetry = qa::retry,
            )

            qa.error != null && questions == null -> ErrorOrExpired(
                message = qa.error.orEmpty(),
                expired = qa.expired,
                onOpenAccount = onOpenAccount,
                onRetry = qa::retry,
            )

            questions == null || questions.isEmpty() -> SdgyMessage(
                text = "这门课还没有问答",
                modifier = Modifier.fillMaxSize(),
                onRetry = qa::retry,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 16.dp + bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                qa.error?.let { text ->
                    item(key = "error") {
                        Text(
                            "刷新失败：$text",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(questions, key = { it.id }) { question ->
                    QuestionCard(question) { qa.openQuestion(question) }
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(question: ZhsQuestion, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                question.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (question.asker.isNotBlank()) {
                    Text(
                        question.asker,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${question.answerCount} 个回答",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (question.time.isNotBlank()) {
                    Text(
                        question.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerContent(
    course: ZhsCourse,
    repo: ZhsRepository,
    qa: QaState,
    question: ZhsQuestion,
    bottomPadding: Dp,
) {
    val scope = rememberCoroutineScope()
    val authGeneration by ZhsAuthCenter.generation.collectAsState()
    LaunchedEffect(question.id, qa.answersKey, authGeneration) {
        qa.answersLoading = true
        qa.answersError = null
        val generationAtStart = authGeneration
        try {
            qa.answers = repo.answers(course, question.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            qa.answersError = e.zhsMessage()
            if ((e as? ZhsException)?.kind == ZhsErrorKind.EXPIRED &&
                authGeneration == generationAtStart
            ) {
                ZhsAuthCenter.requestRelogin()
            }
        } finally {
            qa.answersLoading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = qa::closeQuestion) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回问答列表")
            }
            TextButton(onClick = { qa.answersKey++ }) { Text("刷新回答") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "question") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            qa.answers?.questionContent?.takeIf { it.isNotBlank() }
                                ?: question.content,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (question.asker.isNotBlank()) {
                                Text(
                                    question.asker,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "${question.answerCount} 个回答",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            val answerPage = qa.answers
            when {
                qa.answersLoading && answerPage == null -> item(key = "loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }

                qa.answersError != null && answerPage == null -> item(key = "error") {
                    SdgyMessage(
                        text = qa.answersError.orEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        onRetry = { qa.answersKey++ },
                    )
                }

                answerPage?.answers.isNullOrEmpty() -> item(key = "empty") {
                    Text(
                        "还没有人回答，来写第一个回答吧",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> itemsIndexed(answerPage!!.answers) { _, answer ->
                    AnswerCard(answer)
                }
            }
            item(key = "composer") {
                AnswerComposer(
                    qa = qa,
                    enabled = !qa.submitting,
                    onSubmit = {
                        val text = qa.draft.trim()
                        if (text.isEmpty() || qa.submitting) return@AnswerComposer
                        scope.launch {
                            qa.submitting = true
                            qa.submitError = null
                            qa.submitNotice = null
                            try {
                                repo.submitAnswer(course, question.id, text)
                                qa.draft = ""
                                qa.submitNotice = "回答已提交"
                                qa.answersKey++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                qa.submitError = e.zhsMessage()
                                if ((e as? ZhsException)?.kind == ZhsErrorKind.EXPIRED) {
                                    ZhsAuthCenter.requestRelogin()
                                }
                            } finally {
                                qa.submitting = false
                            }
                        }
                    },
                )
            }
            item(key = "bottom-space") { Spacer(Modifier.height(8.dp + bottomPadding)) }
        }
    }
}

@Composable
private fun AnswerCard(answer: ZhsAnswer) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (answer.author.isNotBlank()) {
                    Text(
                        answer.author,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (answer.teacher) {
                    Text(
                        "老师",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (answer.time.isNotBlank()) {
                    Text(
                        answer.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(answer.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AnswerComposer(qa: QaState, enabled: Boolean, onSubmit: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = qa.draft,
            onValueChange = { qa.draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("我来回答") },
            minLines = 2,
            maxLines = 5,
            enabled = enabled,
        )
        qa.submitError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        qa.submitNotice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (qa.submitting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
            }
            Button(onClick = onSubmit, enabled = enabled && qa.draft.isNotBlank()) {
                Text("提交回答")
            }
        }
    }
}

