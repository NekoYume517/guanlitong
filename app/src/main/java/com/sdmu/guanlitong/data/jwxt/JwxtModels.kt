package com.sdmu.guanlitong.data.jwxt

/** 教务系统的基础学期参数（来自课表首页预渲染字段）。 */
data class SemesterMeta(
    val xnm: String,
    val xqm: String,
    val xqhId: String,
    val sfbczc: String,
    val xnmOptions: List<SelectOption>,
    val xqmOptions: List<SelectOption>,
)

data class SelectOption(val value: String, val label: String)

/** 周次列表中的一项。 */
data class WeekInfo(val zs: String, val zcrq: String)

data class JcTime(
    val period: Int,
    val start: String,
    val end: String,
    val section: String,
)

data class Course(
    val name: String,
    val teacher: String,
    val className: String,
    val room: String,
    val campus: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: Set<Int>,
    val weeksDesc: String,
    val rawWeeks: String,
) {
    val periodSpan: Int get() = (endPeriod - startPeriod + 1).coerceAtLeast(1)
}

data class Timetable(
    val xnm: String,
    val xqm: String,
    val weeks: List<WeekInfo>,
    val currentWeek: Int,
    val courses: List<Course>,
    val jcTimes: Map<Int, JcTime>,
    val headerDates: Map<Int, String>,
    val studentName: String,
    val studentId: String,
) {
    fun coursesInWeek(week: Int): List<Course> =
        courses.filter { it.weeks.isEmpty() || week in it.weeks }
}

/** 教务系统会话失效（Cookie 缺失、被重定向到登录页、返回登录 HTML）。 */
class JwxtSessionExpiredException(message: String = "登录状态已失效，请重新登录") : Exception(message)

/** 其他可展示的业务错误。 */
class JwxtException(message: String) : Exception(message)

/**
 * 周次描述解析，移植自 sdmu-website-2 的 KbcxAPI.parse_zcd_weeks。
 *
 * 单/双周标记只作用于紧邻它的那一段，例如：
 *   "1-3周,5-7周(单),8-16周" → 1,2,3,5,7,8..16
 */
object ZcdParser {

    private val segRange = Regex("""(\d+)\s*(?:-\s*(\d+))?\s*周?""")

    fun parse(raw: String): List<Int> {
        if (raw.isBlank()) return emptyList()

        val text = raw
            .replace('，', ',')
            .replace('、', ',')
            .replace('；', ',')
            .replace('（', '(')
            .replace('）', ')')
            .replace("单周", "(单)")
            .replace("双周", "(双)")

        val weeks = sortedSetOf<Int>()
        text.split(',').forEach { segmentRaw ->
            val segment = segmentRaw.trim()
            if (segment.isEmpty()) return@forEach

            val odd = segment.contains("(单)") || segment.endsWith("单")
            val even = segment.contains("(双)") || segment.endsWith("双")
            val clean = segment
                .replace(Regex("""\([^)]*\)"""), "")
                .replace("单", "")
                .replace("双", "")

            segRange.findAll(clean).forEach { match ->
                val a = match.groupValues[1].toIntOrNull() ?: return@forEach
                val b = match.groupValues[2].toIntOrNull() ?: a
                val start = minOf(a, b)
                val end = maxOf(a, b)
                for (w in start..end) {
                    if (odd && w % 2 == 0) continue
                    if (even && w % 2 == 1) continue
                    weeks.add(w)
                }
            }
        }
        return weeks.toList()
    }

    /** 把周次列表压缩成 "1-4,6-8,10" 形式；连续单双周保留标注。 */
    fun describe(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.distinct().sorted()

        // 1,3,5,7 这类单双周如果拉成 1-7 会误导，补上（单）/（双）。
        if (sorted.size > 2 && sorted.zipWithNext().all { (a, b) -> b - a == 2 }) {
            val parity = if (sorted.first() % 2 == 0) "双" else "单"
            return "${sorted.first()}-${sorted.last()}($parity)"
        }

        val parts = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            parts += if (j == i) "${sorted[i]}" else "${sorted[i]}-${sorted[j]}"
            i = j + 1
        }
        return parts.joinToString(",")
    }
}

/** "1-2" / "3" / "1-2节" → 节次区间。 */
fun parsePeriodRange(jcor: String): IntRange {
    val numbers = Regex("""\d+""").findAll(jcor)
        .mapNotNull { it.value.toIntOrNull() }
        .toList()
    if (numbers.isEmpty()) return 1..1
    val start = numbers.min().coerceAtLeast(1)
    val end = numbers.max().coerceAtLeast(1)
    return start..end
}