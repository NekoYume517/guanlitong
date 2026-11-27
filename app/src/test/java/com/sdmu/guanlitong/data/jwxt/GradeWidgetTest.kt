package com.sdmu.guanlitong.data.jwxt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeWidgetTest {

    private val grades = listOf(
        grade("高等数学", "92", 4.0, 3.8, "2025-1", "2025-2026 第一学期"),
        grade("大学英语", "85", 3.0, 3.2, "2025-1", "2025-2026 第一学期"),
        grade("数据结构", "58", 3.0, 0.8, "2025-1", "2025-2026 第一学期"),
        grade("线性代数", "78", 2.0, 2.8, "2024-2", "2024-2025 第二学期"),
        grade("体育", "合格", 1.0, null, "2024-2", "2024-2025 第二学期"),
    )

    // ── 分桶与排名 ─────────────────────────────────────

    @Test
    fun `分数段在边界上正确归桶`() {
        assertEquals(ScoreBucket.S90, scoreBucketOf("90"))
        assertEquals(ScoreBucket.S80, scoreBucketOf("89.9"))
        assertEquals(ScoreBucket.S70, scoreBucketOf("70"))
        assertEquals(ScoreBucket.S60, scoreBucketOf("60"))
        assertEquals(ScoreBucket.S60Minus, scoreBucketOf("59"))
        assertNull(scoreBucketOf("合格"))
    }

    @Test
    fun `分数段计数忽略非数字成绩`() {
        val buckets = scoreBucketCounts(grades)
        assertEquals(1, buckets[ScoreBucket.S90])
        assertEquals(1, buckets[ScoreBucket.S80])
        assertEquals(1, buckets[ScoreBucket.S60Minus])
        assertEquals(1, buckets[ScoreBucket.S70])
    }

    @Test
    fun `排名按分数降序并限制条数`() {
        val ranking = rankGrades(grades, limit = 3)
        assertEquals(listOf("高等数学", "大学英语", "线性代数"), ranking.map { it.grade.name })
        assertEquals(listOf(1, 2, 3), ranking.map { it.rank })
        assertEquals("体育", rankGrades(grades).last().grade.name)
    }

    @Test
    fun `各学期平均分按学期升序`() {
        val averages = semesterAverages(grades)
        assertEquals(listOf("2024-2", "2025-1"), averages.map { it.semesterKey })
        assertEquals(78.0, averages.first().average, 0.001)
        assertEquals((92.0 + 85.0 + 58.0) / 3, averages.last().average, 0.001)
    }

    // ── 配置生效 ───────────────────────────────────────

    @Test
    fun `默认配置取最近学期`() {
        val board = gradeBoard(grades)!!
        assertEquals("2025-1", board.semesterKey)
        assertEquals(3, board.courseCount)
        assertEquals((92.0 + 85.0 + 58.0) / 3, board.averageScore!!, 0.001)
    }

    @Test
    fun `指定学期只统计该学期`() {
        val board = gradeBoard(grades, GradeWidgetConfig(semesterKey = "2024-2"))!!
        assertEquals("2024-2", board.semesterKey)
        assertEquals(2, board.courseCount)
        assertEquals(78.0, board.averageScore!!, 0.001)
    }

    @Test
    fun `指定课程只保留命中课程`() {
        val board = gradeBoard(grades, GradeWidgetConfig(courseNames = setOf("高等数学")))!!
        assertEquals(1, board.courseCount)
        assertEquals("高等数学", board.ranking.single().grade.name)
    }

    @Test
    fun `选择不存在的课程静默忽略并回退全部`() {
        val board = gradeBoard(grades, GradeWidgetConfig(courseNames = setOf("不存在的课")))!!
        assertEquals(3, board.courseCount)
    }

    @Test
    fun `只看已出分过滤非数字成绩`() {
        val board = gradeBoard(
            grades,
            GradeWidgetConfig(semesterKey = "2025-1", onlyGraded = true),
        )!!
        assertEquals(3, board.courseCount)
    }

    @Test
    fun `只看已出分不丢掉合格类成绩`() {
        val board = gradeBoard(
            grades,
            GradeWidgetConfig(semesterKey = "2024-2", onlyGraded = true),
        )!!
        assertEquals(2, board.courseCount)
        assertEquals(1, board.buckets[ScoreBucket.S70])
    }

    @Test
    fun `空成绩返回 null`() {
        assertNull(gradeBoard(emptyList()))
        assertNull(gradeBoard(grades, GradeWidgetConfig(semesterKey = "不存在")))
    }

    @Test
    fun `课程样式时排名条数按上限截取`() {
        val board = gradeBoard(grades, GradeWidgetConfig(style = GradeWidgetStyle.Courses), rankingLimit = 2)!!
        assertEquals(2, board.ranking.size)
    }

    // ── 配置存储 ───────────────────────────────────────

    @Test
    fun `配置 JSON 往返`() {
        val config = GradeWidgetConfig(
            semesterKey = "2025-1",
            courseNames = setOf("高等数学", "大学英语"),
            onlyGraded = true,
            style = GradeWidgetStyle.Courses,
        )
        assertEquals(config, parseGradeWidgetConfig(config.toJson()))
    }

    @Test
    fun `旧数据缺字段时读取默认配置`() {
        val legacy = JSONObject().put("semesterKey", "2025-1")
        val config = parseGradeWidgetConfig(legacy)!!
        assertEquals("2025-1", config.semesterKey)
        assertTrue(config.courseNames.isEmpty())
        assertEquals(GradeWidgetStyle.Distribution, config.style)
        assertNull(parseGradeWidgetConfig(null))
    }

    @Test
    fun `未知样式回退分布样式`() {
        val config = parseGradeWidgetConfig(JSONObject().put("style", "unknown"))
        assertEquals(GradeWidgetStyle.Distribution, config!!.style)
    }

    @Test
    fun `课程选择超过上限时截断`() {
        val many = (1..10).map { "课程$it" }
        val json = JSONObject().put("courses", org.json.JSONArray(many))
        val config = parseGradeWidgetConfig(json)!!
        assertEquals(GradeWidgetConfig.MAX_COURSES, config.courseNames.size)
        assertNotNull(config)
    }

    private fun grade(
        name: String,
        score: String,
        credit: Double,
        point: Double?,
        key: String,
        semester: String,
    ) = GradeItem(
        name = name,
        score = score,
        credit = credit,
        point = point,
        nature = "必修",
        type = "考试",
        teacher = "老师",
        semester = semester,
        semesterKey = key,
    )
}