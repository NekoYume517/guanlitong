package com.sdmu.guanlitong.ui.jwxt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdmu.guanlitong.data.jwxt.Course
import com.sdmu.guanlitong.data.jwxt.JcTime
import com.sdmu.guanlitong.data.jwxt.JwxtAccess
import com.sdmu.guanlitong.data.jwxt.JwxtCache
import com.sdmu.guanlitong.data.jwxt.JwxtException
import com.sdmu.guanlitong.data.jwxt.JwxtSessionExpiredException
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import com.sdmu.guanlitong.data.jwxt.Timetable
import com.sdmu.guanlitong.data.jwxt.WeekInfo
import com.sdmu.guanlitong.data.jwxt.parseWeekStart
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private sealed interface TimetableState {
    data object Loading : TimetableState
    data class Ready(
        val timetable: Timetable,
        val updatedAt: Long,
        val fromCache: Boolean,
        val needsLogin: Boolean = false,
        val notice: String? = null,
    ) : TimetableState

    data class Failed(val message: String, val sessionExpired: Boolean) : TimetableState
}

/**
 * 原生课表页。
 *
 * 登录态来自 WebView 的 Cookie：进入页面时读取一次，之后由 [JwxtClient] 直接请求教务系统。
 * 未登录/失效时不自动弹网页，而是给出「网页登录」入口，登录成功后由外层导航回到这里并刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwxtScreen(
    access: JwxtAccess,
    refreshToken: Int,
    initialUsername: String,
    initialPassword: String,
    rememberInitial: Boolean,
    onLoginSuccess: (username: String, password: String, remember: Boolean, cookies: String) -> Unit,
    onGrabLogin: () -> Unit,
    onBack: () -> Unit,
    onOpenWeb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val origin = remember(access) { access.origin }
    val usingGrab = access.source == JwxtSource.Grab
    val cached = remember(origin) {
        if (origin.isBlank()) null else JwxtCache.load(context, origin)
    }

    var attempt by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var state by remember(origin) {
        mutableStateOf<TimetableState>(
            cached?.let {
                TimetableState.Ready(
                    timetable = it.timetable,
                    updatedAt = it.savedAt,
                    fromCache = true,
                )
            } ?: TimetableState.Loading,
        )
    }

    val cookie = remember(access, refreshToken, attempt) { access.cookie() }
    val client = remember(access, cookie) { access.client(cookie) }

    LaunchedEffect(access, refreshToken, attempt, cookie) {
        // 有缓存时保持课表可见，在后台刷新；没有缓存才显示整页加载
        val cachedState = state as? TimetableState.Ready
        if (cachedState == null) state = TimetableState.Loading
        refreshing = true
        try {
            when {
                origin.isBlank() -> {
                    state = TimetableState.Failed("还没有配置${access.source.label}地址", false)
                }

                cookie.isBlank() -> {
                    val notice = if (usingGrab) {
                        "尚未登录抢课助手，当前显示上次缓存"
                    } else {
                        "尚未登录教务系统，当前显示上次缓存"
                    }
                    state = cachedState?.copy(
                        fromCache = true,
                        needsLogin = true,
                        notice = notice,
                    ) ?: TimetableState.Failed(
                        if (usingGrab) {
                            "尚未登录抢课助手，请先在「教务系统 → 数据来源」里登录"
                        } else {
                            "尚未登录教务系统，请先在网页中完成登录"
                        },
                        true,
                    )
                }

                else -> {
                    state = try {
                        val timetable = client?.fetchTimetable()
                            ?: throw JwxtException("数据来源不可用")
                        val saved = JwxtCache.save(context, origin, timetable)
                        TimetableState.Ready(
                            timetable = timetable,
                            updatedAt = saved.savedAt,
                            fromCache = false,
                        )
                    } catch (e: JwxtSessionExpiredException) {
                        cachedState?.copy(
                            fromCache = true,
                            needsLogin = true,
                            notice = "登录状态已失效，当前显示上次缓存",
                        ) ?: TimetableState.Failed(e.message ?: "登录状态已失效", true)
                    } catch (e: JwxtException) {
                        cachedState?.copy(
                            fromCache = true,
                            notice = e.message ?: "刷新失败，当前显示上次缓存",
                        ) ?: TimetableState.Failed(e.message ?: "读取课表失败", false)
                    } catch (e: Exception) {
                        cachedState?.copy(
                            fromCache = true,
                            notice = "读取课表失败：${e.message ?: "未知错误"}，当前显示上次缓存",
                        ) ?: TimetableState.Failed("读取课表失败：${e.message ?: "未知错误"}", false)
                    }
                }
            }
        } finally {
            refreshing = false
        }
    }

    val openLogin = {
        if (usingGrab) onGrabLogin() else showLogin = true
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("课表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { attempt++ },
                        enabled = state !is TimetableState.Loading && !refreshing,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onOpenWeb) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = "在网页中打开",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val current = state) {
                TimetableState.Loading -> LoadingView()
                is TimetableState.Failed -> FailedView(
                    message = current.message,
                    sessionExpired = current.sessionExpired,
                    loginLabel = if (usingGrab) "登录抢课助手" else "直接登录",
                    onNativeLogin = openLogin,
                    onOpenWeb = onOpenWeb,
                    onRetry = { attempt++ },
                )

                is TimetableState.Ready -> TimetableContent(
                    timetable = current.timetable,
                    updatedAt = current.updatedAt,
                    fromCache = current.fromCache,
                    needsLogin = current.needsLogin,
                    notice = current.notice,
                    refreshing = refreshing,
                    onRetry = { attempt++ },
                    onNativeLogin = openLogin,
                )
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
            onLoggedIn = { user, pwd, remember, cookies ->
                onLoginSuccess(user, pwd, remember, cookies)
                showLogin = false
            },
            onOpenWeb = {
                showLogin = false
                onOpenWeb()
            },
        )
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "正在从教务系统读取课表…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedView(
    message: String,
    sessionExpired: Boolean,
    loginLabel: String,
    onNativeLogin: () -> Unit,
    onOpenWeb: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = if (sessionExpired) Icons.Outlined.CalendarMonth else Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (sessionExpired) "需要登录教务系统" else "课表读取失败",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onNativeLogin,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(loginLabel)
                }
                TextButton(onClick = onOpenWeb, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("改用网页登录")
                }
                TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("重试")
                }
            }
        }
    }
}

@Composable
private fun TimetableContent(
    timetable: Timetable,
    updatedAt: Long,
    fromCache: Boolean,
    needsLogin: Boolean,
    notice: String?,
    refreshing: Boolean,
    onRetry: () -> Unit,
    onNativeLogin: () -> Unit,
) {
    val hasWeeks = timetable.weeks.isNotEmpty()
    var selectedWeek by remember(timetable) {
        mutableIntStateOf(if (hasWeeks) timetable.currentWeek else 0)
    }
    var detail by remember { mutableStateOf<Course?>(null) }

    val weekCourses = if (!hasWeeks || selectedWeek == 0) {
        timetable.courses
    } else {
        timetable.coursesInWeek(selectedWeek)
    }

    val today = remember { LocalDate.now(ZoneId.of("Asia/Shanghai")) }
    val weekStart = remember(timetable, selectedWeek) {
        timetable.weeks.getOrNull(selectedWeek - 1)?.let { parseWeekStart(it.zcrq) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        if (refreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
            Spacer(Modifier.height(6.dp))
        }

        if (notice != null || fromCache) {
            CacheNotice(
                notice = notice,
                updatedAt = updatedAt,
                refreshing = refreshing,
                needsLogin = needsLogin,
                onRetry = onRetry,
                onNativeLogin = onNativeLogin,
            )
            Spacer(Modifier.height(10.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }

        SemesterCard(
            timetable = timetable,
            courseCount = weekCourses.map { it.name }.distinct().size,
        )

        if (hasWeeks) {
            Spacer(Modifier.height(12.dp))
            WeekSelector(
                weeks = timetable.weeks,
                selectedWeek = selectedWeek,
                currentWeek = timetable.currentWeek,
                onSelect = { selectedWeek = it },
            )
        }

        Spacer(Modifier.height(12.dp))
        TimetableGrid(
            timetable = timetable,
            courses = weekCourses,
            weekStart = weekStart,
            today = today,
            onCourseClick = { detail = it },
        )

        if (weekCourses.isEmpty()) {
            Text(
                text = if (hasWeeks) "本周没有排课" else "本学期暂无课程数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        }

        Spacer(Modifier.height(28.dp))
    }

    detail?.let { course ->
        CourseDetailDialog(
            course = course,
            jcTimes = timetable.jcTimes,
            onDismiss = { detail = null },
        )
    }
}

@Composable
private fun SemesterCard(
    timetable: Timetable,
    courseCount: Int,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = semesterLabel(timetable.xnm, timetable.xqm),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val student = listOf(timetable.studentName, timetable.studentId)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (student.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = student,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
            ) {
                Text(
                    text = "本周 $courseCount 门课",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 缓存/刷新状态条：显示上次更新时间，并给出更新或登录入口。 */
@Composable
private fun CacheNotice(
    notice: String?,
    updatedAt: Long,
    refreshing: Boolean,
    needsLogin: Boolean,
    onRetry: () -> Unit,
    onNativeLogin: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = notice ?: "当前显示本地缓存的课表",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = buildString {
                        append("更新于 ")
                        append(formatUpdatedAt(updatedAt))
                        if (refreshing) append(" · 正在更新…")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                )
            }
            if (!refreshing) {
                TextButton(
                    onClick = if (needsLogin) onNativeLogin else onRetry,
                ) {
                    Text(if (needsLogin) "登录" else "更新")
                }
            }
        }
    }
}

private fun formatUpdatedAt(timestamp: Long): String {
    if (timestamp <= 0L) return "未知时间"
    return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
}

@Composable
private fun WeekSelector(
    weeks: List<WeekInfo>,
    selectedWeek: Int,
    currentWeek: Int,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedWeek) {
        if (selectedWeek in 1..weeks.size) {
            listState.animateScrollToItem((selectedWeek - 1).coerceAtLeast(0))
        }
    }

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(weeks) { index, week ->
            val number = index + 1
            FilterChip(
                selected = number == selectedWeek,
                onClick = { onSelect(number) },
                label = {
                    Text(week.zs.ifBlank { number.toString() } + " 周")
                },
                leadingIcon = if (number == currentWeek) {
                    {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun TimetableGrid(
    timetable: Timetable,
    courses: List<Course>,
    weekStart: LocalDate?,
    today: LocalDate,
    onCourseClick: (Course) -> Unit,
) {
    val periodCount = remember(timetable, courses) {
        maxOf(
            11,
            timetable.jcTimes.keys.maxOrNull() ?: 0,
            courses.maxOfOrNull { it.endPeriod } ?: 0,
        ).coerceAtMost(14)
    }
    val cellHeight = if (periodCount > 11) 48.dp else 52.dp
    val timeWidth = 42.dp
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(modifier = Modifier.padding(horizontal = 6.dp)) {
                Spacer(Modifier.width(timeWidth))
                for (day in 1..7) {
                    DayHeader(
                        day = day,
                        start = weekStart,
                        fallbackLabel = timetable.headerDates[day],
                        today = today,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                color = gridColor,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .height(cellHeight * periodCount.toFloat()),
            ) {
                TimeColumn(
                    jcTimes = timetable.jcTimes,
                    periodCount = periodCount,
                    cellHeight = cellHeight,
                    width = timeWidth,
                    gridColor = gridColor,
                )
                for (day in 1..7) {
                    val placed = remember(courses, day) {
                        placeDay(courses.filter { it.dayOfWeek == day })
                    }
                    DayColumn(
                        placed = placed,
                        periodCount = periodCount,
                        cellHeight = cellHeight,
                        gridColor = gridColor,
                        onCourseClick = onCourseClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(
    day: Int,
    start: LocalDate?,
    fallbackLabel: String?,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val date = start?.plusDays((day - 1).toLong())
    val isToday = date == today
    val label = when {
        date != null -> "${date.monthValue}.${date.dayOfMonth}"
        !fallbackLabel.isNullOrBlank() -> fallbackLabel
        else -> ""
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = WEEKDAYS[day - 1],
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun TimeColumn(
    jcTimes: Map<Int, JcTime>,
    periodCount: Int,
    cellHeight: Dp,
    width: Dp,
    gridColor: Color,
) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .drawBehind {
                drawLine(
                    color = gridColor,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f,
                )
                for (period in 1 until periodCount) {
                    val y = cellHeight.toPx() * period
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (period in 1..periodCount) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cellHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = period.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                jcTimes[period]?.start?.takeIf { it.isNotBlank() }?.let { start ->
                    Text(
                        text = start,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 8.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    placed: List<PlacedCourse>,
    periodCount: Int,
    cellHeight: Dp,
    gridColor: Color,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    BoxWithConstraints(
        modifier = modifier.drawBehind {
            for (period in 1 until periodCount) {
                val y = cellHeight.toPx() * period
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            }
        },
    ) {
        val columnWidth = maxWidth
        placed.forEach { item ->
            val course = item.course
            val span = course.periodSpan
            val cardWidth = columnWidth / item.lanes.toFloat()
            val accent = accentFor(course.name)
            // 多门课并排时列宽只剩一半，只保留课程名并收小字号
            val compact = item.lanes >= 2

            Row(
                modifier = Modifier
                    .offset(
                        x = cardWidth * item.lane.toFloat(),
                        y = cellHeight * (course.startPeriod - 1).toFloat(),
                    )
                    .width(cardWidth)
                    .height(cellHeight * span.toFloat())
                    .padding(horizontal = 1.dp, vertical = 1.5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = if (dark) 0.34f else 0.15f))
                    .clickable { onCourseClick(course) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (compact) 3.dp else 6.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = course.name,
                        fontSize = if (compact) 7.sp else 8.sp,
                        lineHeight = if (compact) 8.5.sp else 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = when {
                            compact -> 5
                            span <= 1 -> 2
                            else -> 3
                        },
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (compact && course.room.isNotBlank() && span >= 2) {
                        Text(
                            text = "@${course.room}",
                            fontSize = 7.sp,
                            lineHeight = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!compact && course.room.isNotBlank()) {
                        Text(
                            text = "@${course.room}",
                            fontSize = 8.sp,
                            lineHeight = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!compact && span >= 3 && course.teacher.isNotBlank()) {
                        Text(
                            text = course.teacher,
                            fontSize = 8.sp,
                            lineHeight = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseDetailDialog(
    course: Course,
    jcTimes: Map<Int, JcTime>,
    onDismiss: () -> Unit,
) {
    val periodText = if (course.startPeriod == course.endPeriod) {
        "第 ${course.startPeriod} 节"
    } else {
        "第 ${course.startPeriod}-${course.endPeriod} 节"
    }
    val start = jcTimes[course.startPeriod]?.start.orEmpty()
    val end = jcTimes[course.endPeriod]?.end.orEmpty()
    val timeText = when {
        start.isNotBlank() && end.isNotBlank() -> "$start – $end"
        start.isNotBlank() -> "从 $start 开始"
        else -> ""
    }
    val weekText = course.rawWeeks.ifBlank {
        if (course.weeksDesc.isBlank()) "" else "第 ${course.weeksDesc} 周"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.School, contentDescription = null) },
        title = { Text(course.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (course.teacher.isNotBlank()) DetailRow("教师", course.teacher)
                if (course.room.isNotBlank()) DetailRow("教室", course.room)
                if (course.campus.isNotBlank()) DetailRow("校区", course.campus)
                if (weekText.isNotBlank()) DetailRow("周次", weekText)
                DetailRow("节次", periodText)
                if (timeText.isNotBlank()) DetailRow("时间", timeText)
                if (course.className.isNotBlank()) DetailRow("教学班", course.className)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── 布局工具 ──────────────────────────────────────────────

private data class PlacedCourse(val course: Course, val lane: Int, val lanes: Int)

/**
 * 同一天内时间重叠的课程并排显示。
 * 先把课程按节次分成「互相重叠的簇」，簇内按车道分配，簇的最大车道数决定宽度。
 */
private fun placeDay(courses: List<Course>): List<PlacedCourse> {
    if (courses.isEmpty()) return emptyList()
    val sorted = courses.sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }))
    val result = mutableListOf<PlacedCourse>()

    var cluster = mutableListOf<Course>()
    var clusterEnd = 0

    fun flush() {
        if (cluster.isEmpty()) return
        val laneEnds = mutableListOf<Int>()
        val assignments = mutableListOf<Pair<Course, Int>>()
        for (course in cluster) {
            var lane = laneEnds.indexOfFirst { it < course.startPeriod }
            if (lane == -1) {
                lane = laneEnds.size
                laneEnds.add(course.endPeriod)
            } else {
                laneEnds[lane] = course.endPeriod
            }
            assignments.add(course to lane)
        }
        val lanes = laneEnds.size.coerceAtLeast(1)
        assignments.forEach { (course, lane) ->
            result.add(PlacedCourse(course = course, lane = lane, lanes = lanes))
        }
        cluster = mutableListOf()
        clusterEnd = 0
    }

    for (course in sorted) {
        if (cluster.isNotEmpty() && course.startPeriod > clusterEnd) {
            flush()
        }
        cluster.add(course)
        if (course.endPeriod > clusterEnd) clusterEnd = course.endPeriod
    }
    flush()

    return result
}

private fun semesterLabel(xnm: String, xqm: String): String {
    if (xnm.isBlank() && xqm.isBlank()) return "本学期"
    val start = xnm.toIntOrNull()
    val year = if (start != null) "$start–${start + 1} 学年" else xnm
    val term = when (xqm) {
        "3" -> "第 1 学期"
        "12" -> "第 2 学期"
        "" -> ""
        else -> "学期 $xqm"
    }
    return listOf(year, term)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val COURSE_COLORS = listOf(
    Color(0xFF4F7CF7),
    Color(0xFF16A085),
    Color(0xFFE8713A),
    Color(0xFF8E5BD9),
    Color(0xFF3AA76D),
    Color(0xFFD4517E),
    Color(0xFFB9880F),
    Color(0xFF3A86C8),
)

private fun accentFor(name: String): Color =
    COURSE_COLORS[(name.hashCode() and 0x7fffffff) % COURSE_COLORS.size]