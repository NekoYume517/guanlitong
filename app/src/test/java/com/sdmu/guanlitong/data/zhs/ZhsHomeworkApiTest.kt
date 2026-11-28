package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhsHomeworkApiTest {

    @Test
    fun parsesReminderListAndSortsByDeadline() {
        val body = """
            {"rt":[
              {"title":"期末小论文","courseName":"大学生心理健康","endTime":"2026-12-01T08:00:00.000Z"},
              {"title":"","courseName":"","endTime":1790000000000}
            ]}
        """.trimIndent()

        val list = ZhsHomeworkApi.parseList(body)

        assertEquals(2, list.size)
        assertEquals("未命名作业", list[0].title)
        assertEquals("课程待更新", list[0].courseName)
        assertEquals(1_790_000_000_000L, list[0].deadline)
        assertEquals("期末小论文", list[1].title)
        assertTrue((list[1].deadline ?: 0) > 1_790_000_000_000L)
    }

    @Test
    fun acceptsSecondsAndInvalidValues() {
        val body = """
            {"rt":[
              {"title":"秒级","endTime":1764556800},
              {"title":"乱码","endTime":"not-a-date"}
            ]}
        """.trimIndent()

        val list = ZhsHomeworkApi.parseList(body)

        assertEquals(1_764_556_800_000L, list[0].deadline)
        assertNull(list[1].deadline)
    }

    @Test(expected = ZhsException::class)
    fun invalidBodyThrowsParseError() {
        ZhsHomeworkApi.parseList("<!DOCTYPE html>")
    }

    @Test
    fun missingRtIsEmpty() {
        assertTrue(ZhsHomeworkApi.parseList("{\"code\":0}").isEmpty())
    }
}