package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Test

/** 成绩接口的 form 字段与顺序对齐参考实现。 */
class ZhsReportApiTest {

    private val course = ZhsCourse(
        courseId = 1101,
        recruitId = 2201,
        name = "课程",
        teacher = "老师",
        school = "学校",
        progress = null,
        shared = true,
        smart = false,
    )

    @Test
    fun reportFormCarriesClassRank() {
        val form = ZhsReportApi.reportForm(course)
        assertEquals(listOf("courseId", "recruitId", "stuRankType"), form.keys.toList())
        assertEquals("1101", form["courseId"])
        assertEquals("2201", form["recruitId"])
        assertEquals("classRank", form["stuRankType"])
    }

    @Test
    fun tabFormMatchesChapterForm() {
        assertEquals(ZhsLearnApi.chapterForm(course), ZhsReportApi.tabForm(course))
    }

    @Test
    fun examFormKeepsEmptyFilters() {
        val form = ZhsReportApi.examForm(course)
        assertEquals(listOf("recruitId", "chapterIds", "lessonIds", "lessonType"), form.keys.toList())
        assertEquals("2201", form["recruitId"])
        assertEquals("", form["chapterIds"])
        assertEquals("", form["lessonIds"])
        assertEquals("", form["lessonType"])
    }
}