package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 问答解析：两种外层结构、HTML 清洗、时间归一。 */
class ZhsQaParserTest {

    @Test
    fun parsesNestedQuestionInfoList() {
        val body = """
            {"rt":{"pageIndex":0,"questionInfoList":[
              {"questionId":"q1","content":"<div>怎么理解&amp;这一章？</div>",
               "answerNum":3,"createTime":1764000000000,"userName":"小明"},
              {"questionId":2,"questionContent":"第二个问题","answerAmount":"1"}
            ]}}
        """.trimIndent()

        val questions = ZhsQaParser.questions(body)

        assertEquals(2, questions.size)
        assertEquals("q1", questions[0].id)
        assertEquals("怎么理解&这一章？", questions[0].content)
        assertEquals(3, questions[0].answerCount)
        assertEquals("小明", questions[0].asker)
        assertTrue(questions[0].time.isNotBlank())
        assertEquals("2", questions[1].id)
        assertEquals(1, questions[1].answerCount)
    }

    @Test
    fun parsesPageListUnderResult() {
        val body = """{"result":{"pageList":[{"qid":"9","title":"标题","answerCount":5}]}}"""
        val questions = ZhsQaParser.questions(body)
        assertEquals("9", questions.single().id)
        assertEquals("标题", questions.single().content)
        assertEquals(5, questions.single().answerCount)
    }

    @Test
    fun emptyListIsNotAnError() {
        assertTrue(ZhsQaParser.questions("{\"rt\":{\"questionInfoList\":[]}}").isEmpty())
    }

    @Test
    fun errorMessageWithoutListIsThrown() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsQaParser.questions("{\"msg\":\"没有权限\"}")
        }
        assertEquals("没有权限", error.message)
    }

    @Test
    fun invalidJsonThrowsParseError() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsQaParser.questions("<html>login</html>")
        }
        assertEquals(ZhsErrorKind.PARSE, error.kind)
    }

    @Test
    fun parsesAnswersAndTeacherFlag() {
        val body = """
            {"rt":{"questionContent":"<p>问题正文</p>","answerInfoList":[
              {"answerId":"a1","answerContent":"<p>回答&nbsp;一</p>","answerName":"老师甲",
               "createTime":"1764000000000","answerFromTeacher":1},
              {"answerContent":"回答二","userName":"同学乙"}
            ]}}
        """.trimIndent()

        val page = ZhsQaParser.answers(body)

        assertEquals("问题正文", page.questionContent)
        assertEquals(2, page.answers.size)
        assertEquals("回答 一", page.answers[0].content)
        assertEquals("老师甲", page.answers[0].author)
        assertTrue(page.answers[0].teacher)
        assertEquals("回答二", page.answers[1].content)
        assertFalse(page.answers[1].teacher)
    }

    @Test
    fun answersMissingListIsEmpty() {
        assertTrue(ZhsQaParser.answers("{\"rt\":{}}").answers.isEmpty())
        assertTrue(ZhsQaParser.answers("{\"rt\":{\"answerInfoList\":[]}}").answers.isEmpty())
    }

    @Test
    fun cleanTextStripsTagsEntitiesAndWhitespace() {
        assertEquals("a b", ZhsQaParser.cleanText("  <b>a</b>\n   b  "))
        assertEquals("A & B < C", ZhsQaParser.cleanText("A &amp; B &lt; C"))
        assertEquals("表情 😀", ZhsQaParser.cleanText("表情 &#128512;"))
        assertNull(ZhsQaParser.cleanText("<br/>   "))
        assertNull(ZhsQaParser.cleanText(null))
    }

    @Test
    fun formatsMillisTime() {
        assertEquals("", ZhsQaParser.formatTime(null))
        assertEquals("", ZhsQaParser.formatTime("null"))
        // 毫秒与秒级时间戳都归一成同一种展示格式（具体时刻取决于默认时区）。
        assertTrue(
            ZhsQaParser.formatTime("1764000000000")
                .matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")),
        )
        assertTrue(
            ZhsQaParser.formatTime("1764000000")
                .matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")),
        )
        assertEquals("2026-01-02", ZhsQaParser.formatTime("2026-01-02"))
    }

    @Test
    fun parsesAnswerInfosFromReferenceShape() {
        val body = """
            {"rt":{"questionContent":"问题正文","answerInfos":[
              {"answerId":"a9","answerContent":"<p>回答&nbsp;甲</p>",
               "userMap":{"username":"张三","schoolName":"某大学"},"createTimeString":"2026-11-27 12:34"},
              {"answerContent":"回答乙","userMap":{"username":"李四"},"createTime":"1764000000000"}
            ]}}
        """.trimIndent()

        val page = ZhsQaParser.answers(body)

        assertEquals(2, page.answers.size)
        assertEquals("回答 甲", page.answers[0].content)
        assertEquals("张三", page.answers[0].author)
        assertEquals("2026-11-27 12:34", page.answers[0].time)
        assertEquals("李四", page.answers[1].author)
        assertTrue(
            page.answers[1].time.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")),
        )
    }

    @Test
    fun parsesLiveUserMapShape() {
        // 线上实际字段：回答人是 userMap.userName（大写 N），老师标记是 userMap.isTeacher。
        val body = """
            {"rt":{"answerInfos":[
              {"id":4371155253,"answerContent":"实干走好新时代长征路。",
               "createTime":1795807987279,"createTimeString":"1分钟前",
               "userMap":{"avatar":"x.png","userName":"何泉锋","schoolName":"","isTeacher":"false"}},
              {"id":4371131701,"answerContent":"老师回答","createTimeString":"11分钟前",
               "userMap":{"userName":"某老师","isTeacher":"true"}}
            ]}}
        """.trimIndent()

        val page = ZhsQaParser.answers(body)

        assertEquals(2, page.answers.size)
        assertEquals("何泉锋", page.answers[0].author)
        assertEquals("1分钟前", page.answers[0].time)
        assertFalse(page.answers[0].teacher)
        assertEquals("某老师", page.answers[1].author)
        assertTrue(page.answers[1].teacher)
    }

    @Test
    fun questionAskerComesFromUserDto() {
        val body = """{"rt":{"questionInfoList":[{"questionId":7,"questionContent":"怎么刷课","userDto":{"username":"王五"}}]}}"""

        val questions = ZhsQaParser.questions(body)

        assertEquals("王五", questions.single().asker)
    }
}