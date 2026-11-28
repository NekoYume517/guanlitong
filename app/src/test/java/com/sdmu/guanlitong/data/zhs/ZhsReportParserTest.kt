package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩解析：主数据决定成败，附加接口缺失时对应字段留空。
 */
class ZhsReportParserTest {

    private val main = """
        {"ssd":{"totalScore":"86.5","usualScore":"20","examScore":"50","meetScore":"6",
                "finalScore":"10","courseScore":"86","bbsScore":"0"},
         "learnProgress":0.45,"avgStudiedProcess":0.62,
         "learnedHabitScore":"3","learnedInteractiveScore":"2"}
    """.trimIndent()

    private val habit = """
        {"habitScore":"3","regularDays":5,"needDay":2,
         "courseStartTime":"2026-09-01","courseEndTime":"2026-12-30",
         "stuStudyRecord":{"suggestTime":30,"totalStudyDay":7,"regularDays":5}}
    """.trimIndent()

    private val forum = """
        {"qaScore":"2","qaStatisticsMap":{
          "myInteractionDto":{"questionAmount":3,"answerAmount":4,
                              "effectiveQuestion":"2","effectiveAnswer":"1","answerFromTeacher":2},
          "hotQuestion":{"questionVo":{"content":"<p>问题&amp;内容</p>"}},
          "hotAnswer":{"answerVo":{"answerContent":"答案 <b>要点</b>"}}
        }}
    """.trimIndent()

    private val exam = """
        {"examInfoList":[{"examName":"期中考试","score":"88","examStatus":"已完成","examTypeName":"考试"}]}
    """.trimIndent()

    private val master = """{"knowledgeMaster":0.73}"""

    @Test
    fun parsesFullReport() {
        val report = ZhsReportParser.parse(main, habit, forum, exam, master)

        assertEquals(86.5, report.totalScore!!, 0.0001)
        assertEquals(20.0, report.usualScore!!, 0.0001)
        assertEquals(0.0, report.bbsScore!!, 0.0001)
        assertEquals(0.45, report.learnProgress!!, 0.0001)
        assertEquals(0.62, report.avgProgress!!, 0.0001)

        val parsedHabit = report.habit!!
        assertEquals(3.0, parsedHabit.score!!, 0.0001)
        assertEquals(5, parsedHabit.regularDays)
        assertEquals(2, parsedHabit.needDays)
        assertEquals(30, parsedHabit.suggestTime)
        assertEquals(7, parsedHabit.totalStudyDay)
        assertEquals("2026-09-01", report.courseStartTime)
        assertEquals("2026-12-30", report.courseEndTime)

        val qa = report.qaStat!!
        assertEquals(2.0, qa.score!!, 0.0001)
        assertEquals(3, qa.myAsk)
        assertEquals(4, qa.myAnswer)
        assertEquals(2, qa.validAsk)
        assertEquals(1, qa.validAnswer)
        assertEquals(2, qa.teacherAnswer)
        assertEquals("问题&内容", qa.hotQuestion)
        assertEquals("答案 要点", qa.hotAnswer)

        assertEquals(1, report.exams.size)
        assertEquals("期中考试", report.exams[0].name)
        assertEquals("88", report.exams[0].score)
        assertEquals("已完成", report.exams[0].status)
        assertEquals(0.73, report.knowledgeMaster!!, 0.0001)
    }

    @Test
    fun optionalBodiesMayBeMissing() {
        val report = ZhsReportParser.parse(main)

        assertNull(report.habit)
        assertNull(report.knowledgeMaster)
        assertTrue(report.exams.isEmpty())
        assertEquals(86.5, report.totalScore!!, 0.0001)
    }

    @Test
    fun brokenOptionalBodiesAreIgnored() {
        val report = ZhsReportParser.parse(main, "<html>", "not json", "", "{}")
        assertNull(report.habit)
        assertTrue(report.exams.isEmpty())
        assertNull(report.knowledgeMaster)
        assertEquals(86.5, report.totalScore!!, 0.0001)
    }

    @Test
    fun missingScoresAreNullNotZero() {
        val report = ZhsReportParser.parse("{\"ssd\":{}}")
        assertNull(report.totalScore)
        assertNull(report.usualScore)
        assertNull(report.learnProgress)
        assertNull(report.habit)
    }

    @Test
    fun habitDaysFallBackToStudyRecord() {
        val report = ZhsReportParser.parse(
            main,
            """{"stuStudyRecord":{"regularDays":4,"totalStudyDay":9,"suggestTime":20}}""",
        )
        val parsed = report.habit!!
        assertEquals(4, parsed.regularDays)
        assertEquals(9, parsed.totalStudyDay)
        assertEquals(5, parsed.needDays)
        assertEquals(20, parsed.suggestTime)
    }

    @Test
    fun examFallsBackToAlternativeFields() {
        val report = ZhsReportParser.parse(
            main,
            examBody = """{"sldList":[{"name":"小测","userScore":"90"}]}""",
        )
        assertEquals("小测", report.exams.single().name)
        assertEquals("90", report.exams.single().score)
    }

    @Test
    fun invalidMainBodyThrowsParseError() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsReportParser.parse("<html>login</html>")
        }
        assertEquals(ZhsErrorKind.PARSE, error.kind)
    }

    @Test
    fun progressPercentNormalizesBothScales() {
        assertEquals(45, progressPercent(0.45))
        assertEquals(45, progressPercent(45.0))
        assertEquals(100, progressPercent(1.0))
        assertEquals(100, progressPercent(180.0))
        assertEquals(0, progressPercent(0.0))
        assertNull(progressPercent(null))
    }

    @Test
    fun courseTimeDisplayNormalization() {
        assertEquals("2026-11-27", ZhsReportParser.formatCourseTime("20261127"))
        assertEquals("2026-11-27 08:15:30", ZhsReportParser.formatCourseTime("20261127081530"))
        assertEquals("2026-08-15 00:00:00", ZhsReportParser.formatCourseTime("2026-08-15 00:00:00"))
        assertEquals("", ZhsReportParser.formatCourseTime("  "))
    }
}