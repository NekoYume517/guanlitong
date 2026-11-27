package com.sdmu.guanlitong.data.jwxt

import org.json.JSONArray
import org.json.JSONObject

/** 成绩卡片的展示样式。 */
enum class GradeWidgetStyle(val key: String, val label: String) {
    Distribution("distribution", "分数段分布"),
    Courses("courses", "课程清单"),
}

/**
 * 单张成绩卡片的配置，按卡片 id 分别保存。
 *
 * [semesterKey] 为空表示「全部学期」；[courseNames] 为空表示该学期全部课程。
 * 旧数据没有这些字段时按默认值读取，不影响既有卡片。
 */
data class GradeWidgetConfig(
    val semesterKey: String = "",
    val courseNames: Set<String> = emptySet(),
    val onlyGraded: Boolean = false,
    val style: GradeWidgetStyle = GradeWidgetStyle.Distribution,
) {
    companion object {
        const val MAX_COURSES = 6
    }
}

/**
 * 卡片配置的 JSON 形式，概览（DashboardStore）和桌面（DesktopWidgetStore）共用。
 *
 * 只写非默认字段的理念不采用：字段少，全量写更方便对账；读取全部用 opt*，
 * 旧版本缺字段或出现未知样式时回退默认值。
 */
fun GradeWidgetConfig.toJson(): JSONObject = JSONObject().apply {
    put("semesterKey", semesterKey)
    put("courses", JSONArray(courseNames.toList()))
    put("onlyGraded", onlyGraded)
    put("style", style.key)
}

fun parseGradeWidgetConfig(root: JSONObject?): GradeWidgetConfig? {
    if (root == null) return null
    val courses = root.optJSONArray("courses")?.let { array ->
        buildSet {
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }.orEmpty()
    return GradeWidgetConfig(
        semesterKey = root.optString("semesterKey"),
        courseNames = courses.take(GradeWidgetConfig.MAX_COURSES).toSet(),
        onlyGraded = root.optBoolean("onlyGraded", false),
        style = GradeWidgetStyle.entries.firstOrNull { it.key == root.optString("style") }
            ?: GradeWidgetStyle.Distribution,
    )
}

/** 分数段，用于分布条形。 */
enum class ScoreBucket(val label: String, val min: Int, val max: Int) {
    S90("90+", 90, Int.MAX_VALUE),
    S80("80-89", 80, 89),
    S70("70-79", 70, 79),
    S60("60-69", 60, 69),
    S60Minus("<60", Int.MIN_VALUE, 59),
}

/** 按分数降序后的名次；同分按课程名稳定排序。 */
data class GradeRanking(val rank: Int, val grade: GradeItem)

data class SemesterAverage(val semesterKey: String, val semester: String, val average: Double)

/** 一张成绩卡片需要的全部派生数据。 */
data class GradeBoard(
    val semester: String,
    val semesterKey: String,
    val courseCount: Int,
    val averageScore: Double?,
    val averagePoint: Double?,
    val totalCredits: Double,
    val buckets: Map<ScoreBucket, Int>,
    val ranking: List<GradeRanking>,
    val semesterAverages: List<SemesterAverage>,
    val updatedAtMs: Long = 0,
)

/** 分数段分桶；非数字成绩（合格/通过）不计入分布。 */
fun scoreBucketOf(score: String): ScoreBucket? {
    val numeric = score.trim().toDoubleOrNull() ?: return null
    return when {
        numeric >= 90.0 -> ScoreBucket.S90
        numeric >= 80.0 -> ScoreBucket.S80
        numeric >= 70.0 -> ScoreBucket.S70
        numeric >= 60.0 -> ScoreBucket.S60
        else -> ScoreBucket.S60Minus
    }
}

fun scoreBucketCounts(grades: List<GradeItem>): Map<ScoreBucket, Int> =
    ScoreBucket.entries.associateWith { bucket ->
        grades.count { scoreBucketOf(it.score) == bucket }
    }

/** 按分数从高到低排名；没有数字分数的排最后。 */
fun rankGrades(grades: List<GradeItem>, limit: Int = Int.MAX_VALUE): List<GradeRanking> =
    grades.sortedWith(
        compareByDescending<GradeItem> { it.numericScore }
            .thenByDescending { it.point }
            .thenBy { it.name },
    ).take(limit.coerceAtLeast(0)).mapIndexed { index, item -> GradeRanking(index + 1, item) }

/** 各学期数字成绩的平均分，按学期键升序，供迷你折线。 */
fun semesterAverages(grades: List<GradeItem>): List<SemesterAverage> =
    grades.groupBy { it.semesterKey }
        .mapNotNull { (key, items) ->
            val scores = items.mapNotNull { it.numericScore }
            if (scores.isEmpty()) return@mapNotNull null
            SemesterAverage(
                semesterKey = key,
                semester = items.first().semester.ifBlank { key },
                average = scores.average(),
            )
        }
        .sortedBy { it.semesterKey }

/**
 * 套用卡片配置，得到真实渲染数据。
 *
 * 指定了不存在的课程名时静默忽略；若全都不存在，则回退为不筛选课程。
 */
fun gradeBoard(
    grades: List<GradeItem>,
    config: GradeWidgetConfig = GradeWidgetConfig(),
    rankingLimit: Int = GradeWidgetConfig.MAX_COURSES,
    updatedAtMs: Long = 0,
): GradeBoard? {
    if (grades.isEmpty()) return null
    val semesterFiltered = if (config.semesterKey.isBlank()) {
        grades
    } else {
        grades.filter { it.semesterKey == config.semesterKey }
    }
    if (semesterFiltered.isEmpty()) return null

    val knownNames = config.courseNames.filter { name -> semesterFiltered.any { it.name == name } }.toSet()
    val courseFiltered = if (knownNames.isEmpty()) semesterFiltered else semesterFiltered.filter { it.name in knownNames }
    val selected = if (config.onlyGraded) {
        courseFiltered.filter { it.score.isNotBlank() && it.score.trim() != "待出分" }
    } else {
        courseFiltered
    }
    if (selected.isEmpty()) return null

    val latestKey = if (config.semesterKey.isNotBlank()) {
        config.semesterKey
    } else {
        selected.maxOf { it.semesterKey }
    }
    val headline = selected.filter { it.semesterKey == latestKey }.ifEmpty { selected }
    val scores = headline.mapNotNull { it.numericScore }
    val graded = headline.filter { it.point != null }
    val creditSum = graded.sumOf { it.credit.coerceAtLeast(0.0) }
    val averagePoint = if (creditSum > 0.0) {
        graded.sumOf { (it.point ?: 0.0) * it.credit.coerceAtLeast(0.0) } / creditSum
    } else {
        null
    }
    return GradeBoard(
        semester = headline.first().semester.ifBlank { "最近学期" },
        semesterKey = latestKey,
        courseCount = headline.size,
        averageScore = scores.takeIf { it.isNotEmpty() }?.average(),
        averagePoint = averagePoint,
        totalCredits = headline.sumOf { it.credit.coerceAtLeast(0.0) },
        buckets = scoreBucketCounts(selected),
        ranking = rankGrades(selected, rankingLimit),
        semesterAverages = semesterAverages(selected),
        updatedAtMs = updatedAtMs,
    )
}