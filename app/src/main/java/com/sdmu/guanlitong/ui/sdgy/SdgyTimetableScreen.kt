package com.sdmu.guanlitong.ui.sdgy

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdmu.guanlitong.data.sdgy.SdgyRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TIMETABLE_PREFS = "sdgy_timetable"
private val WEEK_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val DATE_FMT = SimpleDateFormat("MM.dd", Locale.US)
private val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
private const val ROW_HEIGHT_DP = 56

private data class SdgySlot(
    val day: Int,
    val start: Int,
    val end: Int,
    val name: String,
    val room: String,
    val teacher: String,
    val raw: JSONObject,
)

/**
 * 山管课表（纯原生）。
 *
 * 一次拉取全学期课表，切周只在本地过滤；节次时间、学期首日都来自山管接口，
 * 断网/学校服务器夜间关机时优先显示上次缓存。
 */
@Composable
fun SdgyTimetableScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = rememberSdgyRepository()
    val prefs = remember { context.getSharedPreferences(TIMETABLE_PREFS, Context.MODE_PRIVATE) }

    var term by remember { mutableStateOf<JSONObject?>(null) }
    var courses by remember { mutableStateOf<List<SdgySlot>>(emptyList()) }
    var sections by remember { mutableStateOf<Map<Int, Pair<String, String>>>(emptyMap()) }
    var week by remember { mutableIntStateOf(1) }
    var weekTouched by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var cacheOnly by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    fun readCache() {
        runCatching {
            prefs.getString("term", null)?.let { term = JSONObject(it) }
            prefs.getString("courses", null)?.let { courses = parseCourses(JSONArray(it)) }
            prefs.getString("sections", null)?.let { sections = parseSections(JSONObject(it)) }
        }
    }

    fun syncWeek() {
        val first = term?.optString("ksrq").orEmpty()
        if (first.isBlank() || weekTouched) return
        week = currentWeek(first).coerceIn(1, 25)
    }

    LaunchedEffect(Unit) {
        readCache()
        if (courses.isNotEmpty()) {
            syncWeek()
            loading = false
        }
    }

    LaunchedEffect(tick) {
        if (courses.isEmpty()) loading = true
        error = null
        cacheOnly = false
        val result = runCatching {
            repository.call { client ->
                val t = client.termInfo()
                val list = client.scheduleOfTerm(
                    t.optString("xn"),
                    t.optString("xq"),
                )
                val secs = client.courseSections()
                t to list to secs
            }
        }
        result.onSuccess { (pair, secs) ->
            val (t, list) = pair
            term = t
            courses = parseCourses(list)
            sections = parseSections(secs)
            prefs.edit()
                .putString("term", t.toString())
                .putString("courses", list.toString())
                .putString("sections", secsToJson(parseSections(secs)).toString())
                .apply()
            syncWeek()
            loading = false
            cacheOnly = false
        }.onFailure { e ->
            loading = false
            if (courses.isNotEmpty()) {
                cacheOnly = true
                error = "课表刷新失败，暂时显示上次结果"
            } else {
                error = e.sdgyMessage()
            }
        }
    }

    val firstMonday = term?.optString("ksrq").orEmpty()
    val weekRange = remember(firstMonday, week) { weekRangeText(firstMonday, week) }
    val termLabel = term?.let {
        "${it.optString("xn")}学年 第${it.optString("xq")}学期".replace("学年 第", " 学年 第")
    }.orEmpty()
    val visible = courses.filter { weekMatch(it.raw, week) && slotInWeek(it, week) }

    SdgyScaffold(
        title = "课表",
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = { tick++ }, enabled = !loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (termLabel.isNotBlank() || weekRange.isNotBlank()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (termLabel.isNotBlank()) {
                        Text(termLabel, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "第 $week 周 · $weekRange",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (1..25).forEach { w ->
                    val selected = w == week
                    Surface(
                        modifier = Modifier.clickable {
                            weekTouched = true
                            week = w
                        },
                        shape = RoundedCornerShape(50),
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            "$w",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            if (cacheOnly && error != null) {
                Text(
                    error!!,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when {
                loading && courses.isEmpty() -> SdgyLoading("正在加载课表…")
                courses.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        error ?: "还没有课表",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (error?.contains("登录") == true) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onOpenAccount) { Text("去登录山管 App") }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { tick++ }) { Text("重试") }
                }
                else -> TimetableGrid(
                    week = week,
                    firstMonday = firstMonday,
                    slots = visible,
                    sections = sections,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TimetableGrid(
    week: Int,
    firstMonday: String,
    slots: List<SdgySlot>,
    sections: Map<Int, Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    var detail by remember { mutableStateOf<SdgySlot?>(null) }
    val dates = remember(firstMonday, week) { weekDates(firstMonday, week) }
    val totalHeight = (ROW_HEIGHT_DP * 12).dp

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Spacer(Modifier.width(34.dp))
            dayNames.forEachIndexed { index, name ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "周$name",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        dates.getOrNull(index) ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(34.dp)) {
                (1..12).forEach { period ->
                    Column(
                        Modifier.height(ROW_HEIGHT_DP.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "$period",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sections[period]?.first?.takeIf { it.isNotBlank() }?.let { start ->
                            Text(
                                start,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 7.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            (1..7).forEach { day ->
                Box(Modifier.weight(1f).height(totalHeight)) {
                    Column(Modifier.fillMaxSize()) {
                        repeat(12) {
                            Box(
                                Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp)
                                    .background(
                                        if (it % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow
                                        else Color.Transparent,
                                    ),
                            )
                        }
                    }
                    slots.filter { it.day == day }.groupBy { it.start }.values.forEach { group ->
                        val slot = group.maxByOrNull { it.end - it.start } ?: return@forEach
                        if (group.indexOf(slot) > 0) return@forEach
                        val cardHeight = ((slot.end - slot.start + 1) * ROW_HEIGHT_DP - 4)
                            .coerceAtLeast(34)
                        Box(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 1.dp)
                                .offset(y = ((slot.start - 1) * ROW_HEIGHT_DP + 2).dp)
                                .height(cardHeight.dp)
                                .background(
                                    courseColor(slot.name).copy(alpha = 0.22f),
                                    RoundedCornerShape(7.dp),
                                )
                                .clickable { detail = slot }
                                .padding(3.dp),
                        ) {
                            Column {
                                Text(
                                    slot.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    maxLines = if (cardHeight >= 100) 5 else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (cardHeight >= 72 && slot.room.isNotBlank()) {
                                    Text(
                                        slot.room,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 8.sp,
                                        lineHeight = 10.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "点课程查看详情",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
    }

    detail?.let { slot ->
        CourseDetailDialog(
            slot = slot,
            week = week,
            onClose = { detail = null },
        )
    }
}

@Composable
private fun CourseDetailDialog(
    slot: SdgySlot,
    week: Int,
    onClose: () -> Unit,
) {
    val repository = rememberSdgyRepository()
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var classmates by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var topScorers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var namesLoading by remember { mutableStateOf(false) }
    var namesError by remember { mutableStateOf<String?>(null) }
    var namesTick by remember { mutableIntStateOf(0) }
    var showNames by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(slot, week) {
        loading = true
        error = null
        val result = runCatching {
            repository.call { client ->
                client.courseDetail(
                    detailXlh(slot.raw, slot.start, slot.end),
                    slot.raw.optString("djz").ifBlank { week.toString() },
                    slot.day,
                )
            }
        }
        result.onSuccess {
            if (it.optString("kcmc").isBlank()) error = "该课程没有更多统计信息"
            else detail = it
        }.onFailure { error = it.sdgyMessage() }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(slot.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    listOfNotNull(
                        slot.teacher.takeIf { it.isNotBlank() },
                        slot.room.takeIf { it.isNotBlank() },
                        "周${dayNames.getOrNull(slot.day - 1) ?: slot.day} 第${slot.start}-${slot.end}节",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在查询课程统计…", style = MaterialTheme.typography.bodySmall)
                    }
                    error != null -> Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    detail != null -> {
                        val d = detail!!
                        Row(Modifier.fillMaxWidth()) {
                            StatCell("挂科率",
                                d.optString("gkl").let { if (it.isBlank()) "--" else "$it%" },
                                Modifier.weight(1f))
                            StatCell("平均分", d.optString("pjf").ifBlank { "--" },
                                Modifier.weight(1f))
                            StatCell("上课人数", d.optString("skrs").ifBlank { "--" },
                                Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        InfoLine("教师", d.optString("skls").ifBlank { slot.teacher })
                        InfoLine("地点", d.optString("skdd").ifBlank { slot.room })
                        InfoLine("时间", d.optString("sksj").ifBlank { "--" })
                        val total = d.optInt("nansrs") + d.optInt("nvsrs")
                        if (total > 0 || d.optString("nansbl").isNotBlank()) {
                            InfoLine(
                                "男女比例",
                                "${d.optString("nansbl").ifBlank { d.optInt("nansrs").toString() }} : " +
                                    d.optString("nvsbl").ifBlank { d.optInt("nvsrs").toString() },
                            )
                        }
                        val highest = d.optString("zgfzscj")
                        if (highest.isNotBlank()) {
                            val history = d.optString("lszgf")
                            InfoLine("最高分", if (history.isNotBlank() && history != highest)
                                "$highest（历史 $history）" else highest)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                namesLoading = true
                                namesError = null
                                showNames = "classmates"
                            }, enabled = !namesLoading) { Text("上课名单（${d.optString("skrs")}）") }
                            TextButton(onClick = {
                                namesLoading = true
                                namesError = null
                                showNames = "scores"
                            }, enabled = !namesLoading) { Text("最高分名单") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
    )

    if (showNames != null) {
        LaunchedEffect(showNames, namesTick) {
            val wantClassmates = showNames == "classmates"
            val d = detail ?: return@LaunchedEffect
            val result = runCatching {
                repository.call { client ->
                    val arr = if (wantClassmates) client.classmates(d) else client.topScorers(d)
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
            }
            result.onSuccess {
                namesError = null
                if (wantClassmates) classmates = it else topScorers = it
            }.onFailure {
                namesError = it.sdgyMessage()
                if (wantClassmates) classmates = emptyList() else topScorers = emptyList()
            }
            namesLoading = false
        }
        NameListDialog(
            title = if (showNames == "classmates") "上课名单（${classmates.size}）"
            else "最高分名单（${topScorers.size}人）",
            rows = if (showNames == "classmates") classmates else topScorers,
            emptyText = if (showNames == "classmates") "没有查到上课名单" else "没有查到最高分名单",
            error = namesError,
            onRetry = { namesLoading = true; namesTick++ },
            highlight = if (showNames == "classmates") "" else detail?.optString("zgfzscj").orEmpty(),
            loading = namesLoading,
            onClose = { showNames = null; namesLoading = false },
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NameListDialog(
    title: String,
    rows: List<JSONObject>,
    emptyText: String,
    error: String?,
    onRetry: () -> Unit,
    highlight: String,
    loading: Boolean,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("加载中…", style = MaterialTheme.typography.bodySmall)
                }
                error != null -> Column {
                    Text(
                        "名单加载失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                rows.isEmpty() -> Text(
                    emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    rows.forEach { row ->
                        val name = row.optString("xm").cleanNull()
                        val number = row.optString("xh").cleanNull()
                        val sex = row.optString("xb").cleanNull()
                        val clazz = row.optString("bjmc").cleanNull()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    listOfNotNull(
                                        sex.takeIf { it.isNotBlank() },
                                        clazz.takeIf { it.isNotBlank() },
                                        number.takeIf { it.isNotBlank() },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val score = row.optString("fs").cleanNull()
                                if (highlight.isNotBlank() && score.isNotBlank()) {
                                    Text(
                                        "$score 分",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
    )
}

// ---------- 数据解析 ----------

private fun String.cleanNull(): String =
    takeUnless { it.isBlank() || it.equals("null", true) }.orEmpty()

/**
 * 山管把「单周 1-2 节 + 双周 3-4 节」合并成一条记录，xlh 用「*」拼接每段。
 * 课程详情接口只认单个段，按卡片实际显示的节次挑出对应段，避免服务端 0001 系统异常。
 */
private fun detailXlh(raw: JSONObject, start: Int, end: Int): String {
    val parts = raw.optString("xlh").split('*').filter { it.isNotBlank() }
    if (parts.size < 2) return raw.optString("xlh")
    return parts.firstOrNull { part ->
        Regex("第(\\d+)-(\\d+)节").find(part)?.let { m ->
            m.groupValues[1].toIntOrNull() == start && m.groupValues[2].toIntOrNull() == end
        } == true
    } ?: parts.first()
}

private fun parseCourses(array: JSONArray): List<SdgySlot> =
    (0 until array.length()).mapNotNull { index ->
        val o = array.optJSONObject(index) ?: return@mapNotNull null
        val day = o.optInt("xqj")
        val start = o.optInt("ksjc").takeIf { it > 0 } ?: o.optInt("djj")
        if (day !in 1..7 || start <= 0) return@mapNotNull null
        val end = o.optInt("jsjc", start).coerceIn(start, 12)
        // 合并记录：外层 jsjc 是节次并集，xlh 中各段带自己的节次/周次规则；
        // 周次过滤在 weekMatch 进行，这里把并集拆成多个卡片避免「2 节课画成 4 节」。
        val parts = o.optString("xlh").split('*').filter { it.isNotBlank() }
        val spans = if (parts.size > 1) {
            parts.mapNotNull { part ->
                Regex("第(\\d+)-(\\d+)节").find(part)?.let { m ->
                    val s = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val e = m.groupValues[2].toIntOrNull()?.coerceIn(s, 12) ?: s
                    s to e
                }
            }.ifEmpty { listOf(start to end) }
        } else listOf(start to end)
        spans.map { (s, e) ->
            SdgySlot(
                day = day,
                start = s,
                end = e,
                name = o.optString("kcmc").cleanNull(),
                room = o.optString("skdd").cleanNull(),
                teacher = o.optString("skdx").cleanNull(),
                raw = o,
            )
        }
    }.flatten()

private fun parseSections(array: JSONArray): Map<Int, Pair<String, String>> =
    (0 until array.length()).mapNotNull { index ->
        val o = array.optJSONObject(index) ?: return@mapNotNull null
        val n = o.optInt("djj")
        if (n <= 0) null else n to (o.optString("sksj") to o.optString("xksj"))
    }.toMap()

private fun secsToJson(sections: Map<Int, Pair<String, String>>): JSONObject =
    JSONObject().apply {
        sections.forEach { (key, value) ->
            put(key.toString(), JSONArray().put(value.first).put(value.second))
        }
    }

private fun parseSections(json: JSONObject): Map<Int, Pair<String, String>> =
    json.keys().asSequence().mapNotNull { key ->
        val n = key.toIntOrNull() ?: return@mapNotNull null
        val arr = json.optJSONArray(key) ?: return@mapNotNull null
        n to (arr.optString(0) to arr.optString(1))
    }.toMap()

private fun weekMatch(o: JSONObject, week: Int): Boolean {
    val range = o.optString("qsjsz").replace("周", "")
    val parts = range.split("-")
    val lo = parts.getOrNull(0)?.toIntOrNull() ?: return false
    val hi = parts.getOrNull(1)?.toIntOrNull() ?: lo
    if (week < lo || week > hi) return false
    return when (o.optString("dsz")) {
        "1" -> week % 2 == 1
        "2" -> week % 2 == 0
        else -> true
    }
}

/** 合并课程按 xlh 段携带自己的周次；当前周只显示真正生效的那一段。 */
private fun slotInWeek(slot: SdgySlot, week: Int): Boolean {
    val parts = slot.raw.optString("xlh").split('*').filter { it.isNotBlank() }
    if (parts.size < 2) return true
    val part = parts.firstOrNull { part ->
        Regex("第(\\d+)-(\\d+)节").find(part)?.let { m ->
            m.groupValues[1].toIntOrNull() == slot.start && m.groupValues[2].toIntOrNull() == slot.end
        } == true
    } ?: return true
    // xlh 段里最后一个「N周」或「N周(单/双)」是这一节次的起止周。
    val ranges = Regex("(\\d+)(?:-(\\d+))?周(?:\\((单|双)\\))?").findAll(part).toList()
    val last = ranges.lastOrNull() ?: return true
    val lo = last.groupValues[1].toIntOrNull() ?: return true
    val hi = last.groupValues[2]?.toIntOrNull() ?: lo
    if (week < lo || week > hi) return false
    return when (last.groupValues[3]) {
        "单" -> week % 2 == 1
        "双" -> week % 2 == 0
        else -> true
    }
}

private fun currentWeek(firstMonday: String): Int = try {
    val cal = Calendar.getInstance().apply { time = WEEK_FMT.parse(firstMonday)!! }
    val days = (System.currentTimeMillis() - cal.timeInMillis) / 86_400_000L
    (days / 7 + 1).toInt().coerceAtLeast(1)
} catch (_: Exception) {
    1
}

private fun weekDates(firstMonday: String, week: Int): List<String> {
    if (firstMonday.isBlank()) return emptyList()
    return try {
        val cal = Calendar.getInstance().apply { time = WEEK_FMT.parse(firstMonday)!! }
        cal.add(Calendar.DAY_OF_YEAR, (week - 1) * 7)
        (1..7).map {
            val out = DATE_FMT.format(cal.time)
            if (it < 7) cal.add(Calendar.DAY_OF_YEAR, 1)
            out
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun weekRangeText(firstMonday: String, week: Int): String {
    val dates = weekDates(firstMonday, week)
    return if (dates.size == 7) "${dates.first()} - ${dates.last()}" else ""
}

private fun courseColor(name: String): Color {
    var hash = 0
    for (ch in name) hash = hash * 31 + ch.code
    val hue = ((hash % 360) + 360) % 360
    return Color.hsv(hue.toFloat(), 0.46f, 0.62f)
}