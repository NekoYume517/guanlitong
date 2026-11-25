package com.sdmu.guanlitong.data.jwxt

import org.json.JSONArray
import org.json.JSONObject

/**
 * 教务接口的公共解析。
 *
 * 官方接口和抢课助手（sdmu-website-2）后端返回的是同一套教务字段，
 * 所以成绩、考试、选课、课表的解析集中在这里，两边共用。
 */

// ── 成绩 / 考试 ────────────────────────────────────────────

internal fun parseGradeItems(root: JSONObject): List<GradeItem> {
    val items = root.optJSONArray("items") ?: JSONArray()
    return (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val name = item.str("kcmc", "课程名称")
        if (name.isBlank()) return@mapNotNull null
        val xnm = item.str("xnm")
        val xqm = item.str("xqm")
        val xnmLabel = item.str("xnmmc").ifBlank { xnm }
        val xqmLabel = item.str("xqmmc").ifBlank {
            when (xqm) {
                "3" -> "第1学期"
                "12" -> "第2学期"
                "16" -> "第3学期"
                else -> xqm
            }
        }
        val semester = listOf(xnmLabel, xqmLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { item.str("学期", "学年") }
        GradeItem(
            name = name,
            score = item.str("cj", "成绩", "bfzcj").ifBlank { "--" },
            credit = item.str("xf", "学分").toDoubleOrNull() ?: 0.0,
            point = item.str("jd", "绩点").toDoubleOrNull(),
            nature = item.str("kcxzmc", "课程性质"),
            type = item.str("kclbmc", "课程类别"),
            teacher = item.str("jsxm", "任课教师"),
            semester = semester,
            semesterKey = "$xnm-$xqm-$semester",
        )
    }.sortedWith(compareByDescending<GradeItem> { it.semesterKey })
}

internal fun parseExamItems(root: JSONObject): List<ExamItem> {
    val items = root.optJSONArray("items") ?: JSONArray()
    return (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val name = item.str("kcmc", "课程名称")
        if (name.isBlank()) return@mapNotNull null
        val raw = item.str("kssj", "考试时间")
        val parsed = parseExamTime(raw)
        ExamItem(
            name = name,
            timeText = raw.ifBlank { "--" },
            date = parsed.first,
            start = parsed.second,
            end = parsed.third,
            location = item.str("cdmc", "ksdd", "考试地点").ifBlank { "--" },
            type = item.str("khfs", "ksxs", "考试形式").ifBlank { "--" },
            teacher = teacherName(item.str("jsxx", "jsxm")),
            campus = item.str("cdxqmc", "xqmc"),
        )
    }.sortedBy { it.date + it.start }
}

// ── 选课 ────────────────────────────────────────────────────

internal fun parseSelectionCourses(list: JSONArray): List<SelectionCourse> =
    (0 until list.length()).mapNotNull { index ->
        val item = list.optJSONObject(index) ?: return@mapNotNull null
        val name = item.str("kcmc")
        if (name.isBlank()) return@mapNotNull null
        SelectionCourse(
            kchId = item.str("kch_id"),
            code = item.str("kch"),
            name = name,
            credit = item.str("xf"),
            cxbj = item.str("cxbj").ifBlank { "0" },
            fxbj = item.str("fxbj").ifBlank { "0" },
        )
    }

internal fun parseTeachingClasses(array: JSONArray): List<TeachingClass> =
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val jxbId = item.str("jxb_id")
        if (jxbId.isBlank()) return@mapNotNull null
        TeachingClass(
            jxbId = jxbId,
            doJxbId = item.str("do_jxb_id"),
            name = item.str("kcgsmc").ifBlank { item.str("jxbmc") },
            time = item.str("sksj").ifBlank { "--" },
            place = item.str("jxdd").ifBlank { item.str("xqumc") }.ifBlank { "--" },
            teacher = teacherName(item.str("jsxx")),
            selected = item.str("yxzrs").toIntOrNull(),
            capacity = item.str("jxbrl").toIntOrNull(),
            credit = item.str("xf"),
            type = item.str("kclbmc"),
            campus = item.str("xqumc"),
        )
    }

internal fun parseChosenRows(array: JSONArray): List<ChosenCourse> =
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val name = item.str("kcmc")
        if (name.isBlank()) return@mapNotNull null
        ChosenCourse(
            kchId = item.str("kch_id", "kch"),
            jxbId = item.str("jxb_id"),
            doJxbId = item.str("do_jxb_id", "jxbid"),
            name = name,
            time = item.str("sksj").ifBlank { "--" },
            place = item.str("jxdd", "cdmc").ifBlank { "--" },
            teacher = teacherName(item.str("jsxx", "jsxm")),
            credit = item.str("xf"),
            type = item.str("kclbmc", "kcxzmc"),
        )
    }

/** 教师字段有 "工号/姓名/职称;..." 与 "姓名" 两种写法，只取姓名。 */
internal fun teacherName(raw: String): String {
    val first = raw.substringBefore(';').trim()
    if (first.isBlank()) return "--"
    return first.split('/').let { parts ->
        parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: first
    }
}

// ── 课表（kbcx 原始数据） ───────────────────────────────────

internal fun parseKbcxCourses(
    root: JSONObject,
    /** 抢课助手会额外返回压缩好的 zcd_desc，优先使用。 */
    useProvidedWeekDesc: Boolean = false,
): List<Course> {
    val array = root.optJSONArray("kbList") ?: return emptyList()
    val courses = mutableListOf<Course>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val name = item.str("kcmc")
        if (name.isBlank()) continue
        val day = item.str("xqj").toIntOrNull() ?: continue
        if (day !in 1..7) continue

        // 真实数据里 jcor 常是当天的时间块（如 "1-8"），
        // 真正的上课节次在 jcs / jc（如 "3-4"）；只有它们缺失时才退回 jcor。
        val rawPeriod = item.str("jcs")
            .ifBlank { item.str("jc") }
            .ifBlank { item.str("jcor") }
        val periodRange = parsePeriodRange(rawPeriod)
        val rawWeeks = item.str("zcd").ifBlank { item.str("qsjsz") }
        val weeks = ZcdParser.parse(rawWeeks)
        // 官方教务的 zcd 可能带“周 / 单双”，这里统一压缩；
        // 抢课助手的 zcd 已是纯逗号列表，并附带压缩好的 zcd_desc。
        val providedDesc = item.str("zcd_desc")
        val weeksDesc = if (useProvidedWeekDesc && providedDesc.isNotBlank()) {
            providedDesc
        } else {
            ZcdParser.describe(weeks)
        }

        courses += Course(
            name = name,
            teacher = teacherName(item.str("xm")),
            className = item.str("jxbmc"),
            room = item.str("cdmc"),
            campus = item.str("xqmc"),
            dayOfWeek = day,
            startPeriod = periodRange.first,
            endPeriod = periodRange.last,
            weeks = weeks.toSet(),
            weeksDesc = weeksDesc,
            rawWeeks = rawWeeks,
        )
    }
    return courses.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.name }))
}

internal fun parseKbcxHeaderDates(root: JSONObject): Map<Int, String> {
    val array = root.optJSONArray("rqazcList") ?: return emptyMap()
    val dates = mutableMapOf<Int, String>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val day = item.str("xqj").toIntOrNull() ?: continue
        val date = parseWeekStart(item.str("rq")) ?: continue
        dates[day] = "${date.monthValue}.${date.dayOfMonth}"
    }
    return dates
}

internal fun parseKbcxJcTimes(array: JSONArray): Map<Int, JcTime> {
    val times = linkedMapOf<Int, JcTime>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val period = Regex("""\d+""").find(item.str("jcmc"))
            ?.value?.toIntOrNull() ?: continue
        times[period] = JcTime(
            period = period,
            start = item.str("qssj").take(5),
            end = item.str("jssj").take(5),
            section = item.str("rsdmc"),
        )
    }
    return times
}

/** 抢课助手 `/api/timetable/jc_times` 的 data：{"1": {"start","end","period"}}。 */
internal fun parseGrabJcTimes(data: JSONObject): Map<Int, JcTime> {
    val times = linkedMapOf<Int, JcTime>()
    data.keys().forEach { key ->
        val period = key.toIntOrNull() ?: return@forEach
        val item = data.optJSONObject(key) ?: return@forEach
        times[period] = JcTime(
            period = period,
            start = item.str("start").take(5),
            end = item.str("end").take(5),
            section = item.str("period"),
        )
    }
    return times
}

// ── 小工具 ──────────────────────────────────────────────────

internal fun Map<String, String>.def(key: String, default: String = ""): String =
    this[key]?.takeIf { it.isNotBlank() } ?: default

internal fun JSONObject.str(vararg keys: String): String {
    for (key in keys) {
        val value = optString(key)
        if (value.isNotBlank() && value != "null") return value
    }
    for (actual in keys()) {
        if (keys.any { it.equals(actual, ignoreCase = true) }) {
            val value = optString(actual)
            if (value.isNotBlank() && value != "null") return value
        }
    }
    return ""
}

/** "2026-07-09(10:30-12:10)" → ("2026-07-09", "10:30", "12:10") */
internal fun parseExamTime(raw: String): Triple<String, String, String> {
    val match = Regex("""(\d{4}-\d{2}-\d{2})(?:\((\d{1,2}[:：]\d{2})-(\d{1,2}[:：]\d{2})\))?""")
        .find(raw)
    if (match != null) {
        return Triple(
            match.groupValues[1],
            match.groupValues[2].replace('：', ':'),
            match.groupValues[3].replace('：', ':'),
        )
    }
    val fallback = Regex("""(\d{4})[年-](\d{1,2})[月-](\d{1,2})""").find(raw)
    if (fallback != null) {
        val (y, m, d) = fallback.destructured
        return Triple("%s-%02d-%02d".format(y, m.toInt(), d.toInt()), "", "")
    }
    return Triple("", "", "")
}