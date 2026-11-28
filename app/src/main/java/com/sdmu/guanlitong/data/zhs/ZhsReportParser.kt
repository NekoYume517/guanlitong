package com.sdmu.guanlitong.data.zhs

import org.json.JSONArray
import org.json.JSONObject

/**
 * 成绩分析响应解析（纯函数）。
 *
 * 主响应 `loadStuLearnReport` 决定页面成败；学习习惯 / 问答 / 考试 / 知识点
 * 四个附加接口各自可选，失败时对应字段留空，不拖垮整页（参考实现也是逐个 try）。
 */
internal object ZhsReportParser {

    fun parse(
        mainBody: String,
        habitBody: String? = null,
        forumBody: String? = null,
        examBody: String? = null,
        masterBody: String? = null,
    ): ZhsReport {
        val main = runCatching { JSONObject(mainBody) }.getOrNull()
            ?: throw ZhsException("成绩数据格式异常，请稍后重试", ZhsErrorKind.PARSE)
        val ssd = main.optJSONObject("ssd")
        val habit = optional(habitBody)
        val forum = optional(forumBody)
        val exam = optional(examBody)
        val master = optional(masterBody)
        val record = habit?.optJSONObject("stuStudyRecord")
        val qa = forum?.optJSONObject("qaStatisticsMap")
        val mine = qa?.optJSONObject("myInteractionDto")

        val regularDays = firstInt(
            habit?.opt("regularDays"),
            record?.opt("regularDays"),
            record?.opt("studyDay"),
            record?.opt("totalStudyDay"),
        ) ?: 0
        val totalStudyDay = firstInt(record?.opt("totalStudyDay")) ?: regularDays
        val needDays = firstInt(habit?.opt("needDay"))
            ?: (totalStudyDay - regularDays).coerceAtLeast(0)

        return ZhsReport(
            totalScore = score(ssd?.opt("totalScore")),
            usualScore = score(ssd?.opt("usualScore")),
            examScore = score(ssd?.opt("examScore")),
            meetScore = score(ssd?.opt("meetScore")),
            finalScore = score(ssd?.opt("finalScore")),
            courseScore = score(ssd?.opt("courseScore")),
            bbsScore = score(ssd?.opt("bbsScore")),
            learnProgress = number(main.opt("learnProgress")) ?: number(ssd?.opt("stuProgress")),
            avgProgress = number(main.opt("avgStudiedProcess"))
                ?: number(main.opt("avgStudyProgress")),
            habit = if (habit == null) null else ZhsHabit(
                score = score(habit.opt("habitScore")) ?: score(main.opt("learnedHabitScore")),
                regularDays = regularDays,
                needDays = needDays,
                suggestTime = firstInt(record?.opt("suggestTime")) ?: 0,
                totalStudyDay = totalStudyDay,
            ),
            qaStat = if (forum == null && mine == null && !main.has("learnedInteractiveScore")) {
                null
            } else {
                ZhsQaStat(
                    score = score(forum?.opt("qaScore")) ?: score(main.opt("learnedInteractiveScore")),
                    myAsk = firstInt(mine?.opt("questionAmount")) ?: 0,
                    myAnswer = firstInt(mine?.opt("answerAmount")) ?: 0,
                    validAsk = firstInt(mine?.opt("effectiveQuestion")),
                    validAnswer = firstInt(mine?.opt("effectiveAnswer")),
                    teacherAnswer = firstInt(mine?.opt("answerFromTeacher")) ?: 0,
                    hotQuestion = ZhsQaParser.cleanText(
                        qa?.optJSONObject("hotQuestion")?.optJSONObject("questionVo")?.optString("content"),
                    ).orEmpty(),
                    hotAnswer = ZhsQaParser.cleanText(
                        qa?.optJSONObject("hotAnswer")?.optJSONObject("answerVo")?.optString("answerContent"),
                    ).orEmpty(),
                )
            },
            exams = exams(exam),
            knowledgeMaster = number(master?.opt("knowledgeMaster")),
            courseStartTime = formatCourseTime(habit?.optString("courseStartTime").orEmpty()),
            courseEndTime = formatCourseTime(habit?.optString("courseEndTime").orEmpty()),
        )
    }

    private fun exams(raw: JSONObject?): List<ZhsExam> {
        if (raw == null) return emptyList()
        val list = raw.optJSONArray("examInfoList") ?: raw.optJSONArray("sldList") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            list.optJSONObject(index)?.let(::examOf)
        }
    }

    /**
     * 课程时间的显示归一化：结束时间偶尔只给 8 位 `YYYYMMDD`（或 14 位 `YYYYMMDDHHmmss`），
     * 统一成带分隔符的日期；已经带分隔符的串原样保留。
     */
    internal fun formatCourseTime(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.any { !it.isDigit() }) return value
        return when (value.length) {
            8 -> "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
            14 ->
                "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)} " +
                    "${value.substring(8, 10)}:${value.substring(10, 12)}:${value.substring(12, 14)}"
            else -> value
        }
    }

    internal fun examOf(raw: JSONObject): ZhsExam? {
        val name = text(raw, "examName") ?: text(raw, "name") ?: text(raw, "title")
            ?: text(raw, "chapterName") ?: return null
        return ZhsExam(
            name = name,
            score = text(raw, "score") ?: text(raw, "userScore") ?: text(raw, "examScore"),
            status = text(raw, "examStatus") ?: text(raw, "status") ?: text(raw, "state"),
            note = text(raw, "examTypeName") ?: text(raw, "examType"),
        )
    }

    private fun optional(body: String?): JSONObject? =
        body?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** 数字或数字字符串 → Double；缺失、空串、"null" 都算未知。 */
    internal fun score(value: Any?): Double? = number(value)

    internal fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().takeIf {
            it.isNotEmpty() && !it.equals("null", ignoreCase = true)
        }?.toDoubleOrNull()
        else -> null
    }

    internal fun firstInt(vararg values: Any?): Int? {
        for (value in values) {
            val parsed = number(value) ?: continue
            return parsed.toInt()
        }
        return null
    }

    internal fun text(raw: JSONObject, key: String): String? =
        raw.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

    internal fun arrayOrNull(raw: JSONObject, key: String): JSONArray? = raw.optJSONArray(key)
}