package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 报文构造与参考实现逐字节对齐（不发请求）。
 */
class ZhsLearnApiTest {

    private val course = ZhsCourse(
        courseId = 1101,
        recruitId = 2201,
        name = "课程",
        teacher = "老师",
        school = "学校",
        progress = null,
        shared = true,
        smart = false,
        secret = "MTIzNDU2Nzg5MA==",
    )

    @Test
    fun progressPayloadMatchesReferenceJson() {
        val payload = ZhsLearnApi.progressPayload(
            lessonIds = listOf(101, 102),
            recruitId = 2201,
            videoIds = listOf(1001),
            date = 1_700_000_000_000L,
        )
        assertEquals(
            "{\"lessonIds\":[101,102],\"recruitId\":2201," +
                "\"lessonVideoIds\":[1001],\"dateFormate\":1700000000000}",
            payload,
        )
    }

    @Test
    fun progressPayloadHandlesEmptyLists() {
        assertEquals(
            "{\"lessonIds\":[],\"recruitId\":1,\"lessonVideoIds\":[],\"dateFormate\":2}",
            ZhsLearnApi.progressPayload(emptyList(), 1, emptyList(), 2),
        )
    }

    @Test
    fun chapterFormKeepsReferenceFieldOrder() {
        val form = ZhsLearnApi.chapterForm(course)
        assertEquals(listOf("courseId", "recruitId", "type", "courseTypeEs"), form.keys.toList())
        assertEquals("1101", form["courseId"])
        assertEquals("2201", form["recruitId"])
        assertEquals("l", form["type"])
        assertEquals("1", form["courseTypeEs"])
    }

    @Test
    fun indexUrlCarriesCourseIdentifiers() {
        assertEquals(
            "https://stuonline.zhihuishu.com/stuonline/stuLearnReportNew/index?recruitId=2201&courseId=1101",
            ZhsLearnApi.indexUrl(course),
        )
    }
}