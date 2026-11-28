package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程列表解析：字段可能缺失、类型可能不一致，缺关键标识的条目要丢弃。
 */
class ZhsCourseParserTest {

    @Test
    fun parsesSharedCoursesWithFallbacks() {
        val body = """
            {"result":{"courseOpenDtos":[
              {"courseId":101,"recruitId":201,"courseName":"大学英语","teacherName":"张老师",
               "schoolName":"管理学院","progress":42.6},
              {"courseId":"102","recruitId":"202","courseName":"高数","studyRate":"18"},
              {"courseId":103,"recruitId":203,"courseName":"管理学","progress":"130"}
            ]}}
        """.trimIndent()

        val courses = ZhsCourseParser.parseShared(body)
        assertEquals(3, courses.size)

        assertEquals(101L, courses[0].courseId)
        assertEquals(201L, courses[0].recruitId)
        assertEquals("大学英语", courses[0].name)
        assertEquals("张老师", courses[0].teacher)
        assertEquals("管理学院", courses[0].school)
        assertEquals(43, courses[0].progress)
        assertTrue(courses[0].shared)

        assertEquals("授课教师待更新", courses[1].teacher)
        assertEquals("智慧树课程", courses[1].school)
        assertEquals(18, courses[1].progress)

        assertEquals(100, courses[2].progress)
    }

    @Test
    fun dropsCoursesWithoutIds() {
        val body = """
            {"result":{"courseOpenDtos":[
              {"courseName":"没有编号的课"},
              {"courseId":0,"recruitId":9,"courseName":"零编号"},
              {"courseId":-1,"recruitId":9,"courseName":"负数编号"},
              {"courseId":104,"recruitId":204,"courseName":"正常课程","progress":"-5"}
            ]}}
        """.trimIndent()

        val courses = ZhsCourseParser.parseShared(body)
        assertEquals(1, courses.size)
        assertEquals("正常课程", courses[0].name)
        assertEquals(0, courses[0].progress)
    }

    @Test
    fun parsesFlippedCoursesAndSmartFlag() {
        val body = """
            {"rt":{"pageList":[
              {"courseId":301,"recruitId":401,"courseName":"创业基础","teacherName":"李老师",
               "schoolName":"创新创业学院","progress":88,"courseType":7},
              {"courseId":302,"recruitId":402,"title":"程序设计","mapCourse":"7"},
              {"courseId":303,"recruitId":403,"courseName":"线性代数","progress":"abc"}
            ]}}
        """.trimIndent()

        val courses = ZhsCourseParser.parseFlipped(body)
        assertEquals(3, courses.size)
        assertTrue(courses[0].smart)
        assertEquals(false, courses[0].shared)
        assertEquals("程序设计", courses[1].name)
        assertTrue(courses[1].smart)
        assertNull(courses[2].progress)
        assertFalse(courses[2].smart)
    }

    @Test
    fun progressNullWhenMissingOrNonNumeric() {
        val body = """
            {"rt":{"pageList":[{"courseId":1,"recruitId":2,"courseName":"无进度"}]}}
        """.trimIndent()
        assertNull(ZhsCourseParser.parseFlipped(body).single().progress)
    }

    @Test
    fun missingEnvelopeThrowsParseError() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsCourseParser.parseShared("{\"msg\":\"没有数据\"}")
        }
        assertEquals(ZhsErrorKind.PARSE, error.kind)
        assertEquals("没有数据", error.message)
    }

    @Test
    fun malformedJsonThrowsParseError() {
        assertThrows(ZhsException::class.java) { ZhsCourseParser.parseFlipped("<html>502</html>") }
    }

    @Test
    fun envelopeWithoutMessageMeansNoCourses() {
        assertEquals(0, ZhsCourseParser.parseShared("{\"result\":null}").size)
        assertEquals(0, ZhsCourseParser.parseFlipped("{\"rt\":{}}").size)
    }

    @Test
    fun keepsCourseSecretForDetailApis() {
        val body = """
            {"result":{"courseOpenDtos":[
              {"courseId":101,"recruitId":201,"courseName":"大学英语","secret":"MTIzNDU2"}
            ]}}
        """.trimIndent()
        val course = ZhsCourseParser.parseShared(body).single()
        assertEquals("MTIzNDU2", course.secret)
        assertEquals("", ZhsCourseParser.parseShared(
            "{\"result\":{\"courseOpenDtos\":[{\"courseId\":101,\"recruitId\":201}]}}",
        ).single().secret)
    }

    @Test
    fun emptyArrayIsEmptyList() {
        assertEquals(0, ZhsCourseParser.parseShared("{\"result\":{\"courseOpenDtos\":[]}}").size)
        assertEquals(0, ZhsCourseParser.parseFlipped("{\"rt\":{\"pageList\":[]}}").size)
    }
}