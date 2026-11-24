package com.sdmu.guanlitong.data.sdgy

import android.content.Context
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.jwxt.Course
import com.sdmu.guanlitong.data.jwxt.JcTime
import com.sdmu.guanlitong.data.jwxt.Timetable
import com.sdmu.guanlitong.data.jwxt.WeekInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 山管课表能给教务课表补充的统计信息。 */
data class SdgyCourseSupplement(
    val failureRate: String,
    val average: String,
    val highest: String,
    val studentCount: String,
    val genderRatio: String,
    /** 姓名 → 学号。 */
    val classmates: List<Pair<String, String>>,
)

/**
 * 山管课表的缓存与详情查询。
 *
 * 统一课表页以教务系统数据为主，这里只负责两件事：
 * 1. 读取/刷新「山管app → 我的课表」的缓存；
 * 2. 按课程名、教师、星期与节次匹配到山管课程，拉取挂科率、平均分和上课名单。
 */
object SdgyTimetableSource {

    private const val PREFS = "sdgy_timetable"

    fun cachedCourses(context: Context): JSONArray? = runCatching {
        val raw = prefs(context).getString("courses", null) ?: return null
        JSONArray(raw).takeIf { it.length() > 0 }
    }.getOrNull()

    /**
     * 没有缓存时向山管服务器拉一次全学期课表；失败返回 null，由调用方静默跳过补充。
     * 缓存格式与 [com.sdmu.guanlitong.ui.sdgy.SdgyTimetableScreen] 保持一致。
     */
    suspend fun ensureCourses(context: Context, repository: SdgyRepository): JSONArray? {
        cachedCourses(context)?.let { return it }
        return runCatching {
            repository.call { client ->
                val term = client.termInfo()
                val courses = client.scheduleOfTerm(
                    term.optString("xn"),
                    term.optString("xq"),
                )
                val sections = client.courseSections()
                prefs(context).edit()
                    .putString("term", term.toString())
                    .putString("courses", courses.toString())
                    .putString("sections", sectionsToJson(sections).toString())
                    .apply()
                courses
            }
        }.getOrNull()
    }

    /**
     * 拉取一门课的补充统计。匹配不到、未登录或网络失败都返回 null，
     * 让教务课表本身照常展示，不因为山管不可用而报错。
     */
    suspend fun loadSupplement(
        context: Context,
        repository: SdgyRepository,
        courseName: String,
        teacher: String,
        day: Int,
        start: Int,
        end: Int,
        week: Int,
    ): SdgyCourseSupplement? {
        val rows = ensureCourses(context, repository) ?: return null
        val row = matchRow(rows, courseName, teacher, day, start, end, week) ?: return null
        val detail = runCatching {
            repository.call { client ->
                client.courseDetail(
                    pickXlh(row, start, end),
                    row.optString("djz").ifBlank { week.toString() },
                    day,
                )
            }
        }.getOrNull() ?: return null
        if (detail.optString("kcmc").isBlank()) return null

        val classmates = runCatching {
            repository.call { client -> client.classmates(detail) }
        }.getOrDefault(JSONArray())

        return SdgyCourseSupplement(
            failureRate = text(detail, "gkl").ifBlank { "--" },
            average = text(detail, "pjf").ifBlank { "--" },
            highest = text(detail, "lszgf").ifBlank { "--" },
            studentCount = text(detail, "skrs").ifBlank { "--" },
            genderRatio = listOf(text(detail, "nansbl"), text(detail, "nvsbl"))
                .filter { it.isNotBlank() }
                .joinToString(" : ")
                .ifBlank { "--" },
            classmates = (0 until classmates.length()).mapNotNull { index ->
                val o = classmates.optJSONObject(index) ?: return@mapNotNull null
                val name = text(o, "xm")
                if (name.isBlank()) null else name to text(o, "xh")
            },
        )
    }

    /**
     * 未登录教务系统时的兜底：把山管全学期课表转换成统一课表页使用的模型。
     * 教务数据能拿到时永远优先教务，这里只在完全没有教务数据时使用。
     */
    fun toJwxtTimetable(context: Context): Timetable? {
        val term = runCatching {
            val raw = prefs(context).getString("term", null) ?: return null
            JSONObject(raw)
        }.getOrNull() ?: return null
        val rows = cachedCourses(context) ?: return null
        val firstMonday = term.optString("ksrq").orEmpty()
        val weekDates = weekStarts(firstMonday) ?: return null

        val courses = mutableListOf<Course>()
        (0 until rows.length()).forEach { index ->
            val o = rows.optJSONObject(index) ?: return@forEach
            val day = o.optInt("xqj")
            val baseStart = o.optInt("ksjc").takeIf { it > 0 } ?: return@forEach
            if (day !in 1..7) return@forEach
            val baseEnd = o.optInt("jsjc", baseStart)
            val parts = o.optString("xlh").split('*').filter { it.isNotBlank() }
            val spans = if (parts.size > 1) {
                parts.mapNotNull { part ->
                    Regex("第(\\d+)-(\\d+)节").find(part)?.let { m ->
                        val s = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                        val e = m.groupValues[2].toIntOrNull() ?: s
                        Triple(s, e, weeksOf(part))
                    }
                }.ifEmpty { listOf(Triple(baseStart, baseEnd, weeksOfRow(o))) }
            } else {
                listOf(Triple(baseStart, baseEnd, weeksOfRow(o)))
            }
            spans.forEach { (start, end, weeks) ->
                if (start <= 0 || weeks.isEmpty()) return@forEach
                courses += Course(
                    name = o.optString("kcmc"),
                    teacher = o.optString("skdx"),
                    className = "",
                    room = o.optString("skdd"),
                    campus = "",
                    dayOfWeek = day,
                    startPeriod = start,
                    endPeriod = end.coerceAtMost(14),
                    weeks = weeks,
                    // 真实文案等归并周次之后再生成。
                    weeksDesc = "",
                    rawWeeks = "",
                )
            }
        }
        // 同一门课按周实例返回多次，每次只带当周的 weeks；
        // 按课程 + 星期 + 节次归并，周次取并集，避免出现一摞重叠的窄卡片。
        val merged = LinkedHashMap<List<Any>, Course>()
        courses.forEach { course ->
            val key: List<Any> = listOf(
                course.name, course.teacher, course.room,
                course.dayOfWeek, course.startPeriod, course.endPeriod,
            )
            val existing = merged[key]
            merged[key] = if (existing == null) course else existing.copy(
                weeks = existing.weeks + course.weeks,
            )
        }
        val uniqueCourses = merged.values.map { course ->
            course.copy(weeksDesc = weeksDescOf(course.weeks))
        }
        if (uniqueCourses.isEmpty()) return null

        val current = currentWeek(firstMonday)
        val maxWeek = maxOf(
            16,
            current,
            uniqueCourses.maxOfOrNull { it.weeks.maxOrNull() ?: 0 } ?: 0,
        )
        val weeks = (1..maxWeek).map { number ->
            WeekInfo(zs = number.toString(), zcrq = weekDates.getOrNull(number - 1) ?: firstMonday)
        }
        val account = AccountStore(context).accountOf("sdgy")
        // 山管用「2026-2027 / 1、2」表示学年学期，教务页面用「2026 / 3、12」，这里换算。
        val xn = term.optString("xn").trim()
        val xqm = when (term.optString("xq").trim()) {
            "1" -> "3"
            "2" -> "12"
            else -> term.optString("xq").trim()
        }
        return Timetable(
            xnm = xn.substringBefore("-").trim().ifBlank { xn },
            xqm = xqm,
            weeks = weeks,
            currentWeek = current,
            courses = uniqueCourses,
            jcTimes = cachedSections(context),
            headerDates = emptyMap(),
            // 昵称和学号都留着会显示成「学号 · 学号」，昵称为空时让它空着。
            studentName = account.nickname,
            studentId = account.username,
        )
    }

    private fun cachedSections(context: Context): Map<Int, JcTime> = runCatching {
        val raw = prefs(context).getString("sections", null) ?: return emptyMap()
        val o = JSONObject(raw)
        val out = mutableMapOf<Int, JcTime>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val period = key.toIntOrNull() ?: continue
            val pair = o.optJSONArray(key) ?: continue
            val start = pair.optString(0)
            val end = pair.optString(1)
            if (start.isNotBlank() && end.isNotBlank()) {
                out[period] = JcTime(period, start, end, "")
            }
        }
        out
    }.getOrDefault(emptyMap())

    private fun weekStarts(firstMonday: String): List<String>? = runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance().apply { time = fmt.parse(firstMonday)!! }
        Array(30) {
            val text = fmt.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, 7)
            text
        }.toList()
    }.getOrNull()

    private fun currentWeek(firstMonday: String): Int = try {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val start = fmt.parse(firstMonday)!!
        val days = (System.currentTimeMillis() - start.time) / 86_400_000L
        (days / 7).toInt() + 1
    } catch (_: Exception) {
        1
    }

    private fun weeksOfRow(o: JSONObject): Set<Int> {
        val start = o.optString("qsjsz").substringBefore("-").toIntOrNull() ?: return emptySet()
        val end = o.optString("qsjsz").substringAfter("-", o.optString("qsjsz"))
            .toIntOrNull() ?: start
        return when (o.optString("dsz")) {
            "1" -> (start..end).filter { it % 2 == 1 }.toSet()
            "2" -> (start..end).filter { it % 2 == 0 }.toSet()
            else -> (start..end).toSet()
        }
    }

    /** 从 xlh 段里解析这一节次自己的周次规则。 */
    private fun weeksOf(part: String): Set<Int> {
        val weeks = sortedSetOf<Int>()
        Regex("(\\d+)(?:-(\\d+))?周(?:\\((单|双)\\))?").findAll(part).forEach { m ->
            val lo = m.groupValues[1].toIntOrNull() ?: return@forEach
            val hi = m.groupValues[2].toIntOrNull() ?: lo
            val mark = m.groupValues[3]
            (lo..hi).forEach { week ->
                when (mark) {
                    "单" -> if (week % 2 == 1) weeks.add(week)
                    "双" -> if (week % 2 == 0) weeks.add(week)
                    else -> weeks.add(week)
                }
            }
        }
        return weeks
    }

    /** 单双周的课在合并后加上（单）/（双），其余用连续区间描述。 */
    private fun weeksDescOf(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.sorted()
        val step = 2
        val contiguous = sorted.size > 2 &&
            sorted.last() - sorted.first() == (sorted.size - 1) * step
        return when {
            contiguous && sorted.all { it % 2 == 1 } -> "${sorted.first()}-${sorted.last()}(单)"
            contiguous && sorted.all { it % 2 == 0 } -> "${sorted.first()}-${sorted.last()}(双)"
            else -> weeksText(weeks)
        }
    }

    private fun weeksText(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.sorted()
        val ranges = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (w in sorted.drop(1)) {
            if (w == prev + 1) {
                prev = w
                continue
            }
            ranges += if (start == prev) "$start" else "$start-$prev"
            start = w
            prev = w
        }
        ranges += if (start == prev) "$start" else "$start-$prev"
        return ranges.joinToString(",")
    }

    private fun matchRow(
        rows: JSONArray,
        courseName: String,
        teacher: String,
        day: Int,
        start: Int,
        end: Int,
        week: Int,
    ): JSONObject? {
        val target = norm(courseName)
        if (target.isBlank()) return null
        val candidates = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { row ->
                row.optInt("xqj") == day && norm(row.optString("kcmc")) == target
            }
        if (candidates.isEmpty()) return null

        // 山管按周返回实例，djz 就是这一条对应的周次；同名课程优先取本周那条。
        val sameWeek = candidates.filter { it.optInt("djz") == week }
        val timeMatched = (sameWeek.ifEmpty { candidates }).filter { row ->
            val rowStart = row.optInt("ksjc").takeIf { it > 0 } ?: row.optInt("djj")
            val rowEnd = row.optInt("jsjc", rowStart)
            rowStart <= start && rowEnd >= end
        }
        val pool = timeMatched.ifEmpty { sameWeek.ifEmpty { candidates } }
        val teacherKey = norm(teacher).take(3)
        return pool.maxByOrNull { row ->
            if (teacherKey.isNotBlank() && norm(row.optString("skdx")).contains(teacherKey)) 1 else 0
        }
    }

    /** 合并记录的 xlh 有多段时，取与卡片节次对应的那一段。 */
    private fun pickXlh(row: JSONObject, start: Int, end: Int): String {
        val xlh = row.optString("xlh")
        val parts = xlh.split('*').filter { it.isNotBlank() }
        if (parts.size < 2) return xlh
        return parts.firstOrNull { part ->
            Regex("第(\\d+)-(\\d+)节").find(part)?.let { m ->
                m.groupValues[1].toIntOrNull() == start && m.groupValues[2].toIntOrNull() == end
            } == true
        } ?: parts.first()
    }

    private fun sectionsToJson(sections: JSONArray): JSONObject {
        val out = JSONObject()
        (0 until sections.length()).forEach { index ->
            val o = sections.optJSONObject(index) ?: return@forEach
            val period = o.optInt("djj")
            if (period <= 0) return@forEach
            out.put(
                period.toString(),
                JSONArray().put(o.optString("sksj")).put(o.optString("xksj")),
            )
        }
        return out
    }

    /** 服务端会把不存在的字段返回成字面量 "null"。 */
    private fun text(o: JSONObject, key: String): String {
        val value = o.opt(key) ?: return ""
        if (value == JSONObject.NULL) return ""
        val text = value.toString().trim()
        return text.takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
    }

    private fun norm(value: String): String =
        value.replace(" ", "").replace("　", "")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
