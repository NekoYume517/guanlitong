package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** doWork 页解析：五种题型、LaTeX/图片、无题目、提交地址与参数补全。 */
class CxWorkParserTest {

    private fun detail() = CxWorkParser.parse(
        CxWorkFixtureData.workPageHtml,
        CxWorkFixtureData.DOWORK_URL,
    )

    @Test
    fun parsesSingleChoice() {
        val q = detail().questions[0]
        assertEquals("111", q.id)
        assertEquals(1, q.index)
        assertEquals(CxWorkQType.SINGLE, q.qtype)
        assertEquals("下列哪个是质数？", q.title)
        assertEquals(listOf("A", "B", "C"), q.options.map { it.key })
        assertEquals("A", q.answer)
    }

    @Test
    fun inlinesLatexInOptionText() {
        val option = detail().questions[0].options[1]
        assertEquals("B", option.key)
        assertTrue(option.text, option.text.contains("\\sqrt{4}"))
        assertTrue(option.text, option.text.contains("的值"))
    }

    @Test
    fun parsesMultiChoice() {
        val q = detail().questions[1]
        assertEquals("222", q.id)
        assertEquals(CxWorkQType.MULTI, q.qtype)
        assertEquals("AC", q.answer)
        assertEquals(3, q.options.size)
    }

    @Test
    fun parsesJudge() {
        val q = detail().questions[2]
        assertEquals(CxWorkQType.JUDGE, q.qtype)
        assertEquals("判断题", q.qtype.label)
        assertEquals("true", q.answer)
    }

    @Test
    fun parsesFillWithBlankCountAndExistingAnswers() {
        val q = detail().questions[3]
        assertEquals(CxWorkQType.FILL, q.qtype)
        assertEquals(2, q.fillCount)
        assertEquals(listOf("第一空", "第二空"), q.fillAnswers)
        assertEquals("第一空|第二空", q.answer)
    }

    @Test
    fun decodesLatexInTitle() {
        val title = detail().questions[3].title
        assertTrue(title, title.contains("\\frac{1}{2}"))
        assertFalse(title, title.contains("mspace"))
    }

    @Test
    fun parsesEssayFromTextarea() {
        val q = detail().questions[4]
        assertEquals(CxWorkQType.ESSAY, q.qtype)
        assertEquals("请简述牛顿第一定律。", q.title)
        assertEquals("惯性", q.answer)
    }

    @Test
    fun keepsImageAsAbsoluteUrl() {
        val q = detail().questions[5]
        assertEquals("看图选择", q.title)
        assertEquals(
            listOf("https://mooc1.chaoxing.com/images/fig.png"),
            q.titleImages,
        )
    }

    @Test
    fun fallsBackToAnswerTypeWhenTypenameMissing() {
        // answertype=1（多选），没有 typename 属性。
        assertEquals(CxWorkQType.MULTI, detail().questions[6].qtype)
    }

    @Test
    fun buildsSubmitParamsAndAbsoluteSubmitUrl() {
        val d = detail()
        assertEquals(
            "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/submit",
            d.submitUrl,
        )
        assertEquals("123456", d.submitParams["courseId"])
        assertEquals("404252409", d.submitParams["workId"])
        assertEquals("abc%2Bdef", d.submitParams["enc"])
        assertEquals("2", d.submitParams["tiankongsize444"])
        assertEquals(CxWorkFixtureData.DOWORK_URL, d.doworkUrl)
        assertEquals("高等数学", d.courseName)
        assertEquals("第一章 作业", d.title)
    }

    @Test
    fun keepsAbsoluteFormAction() {
        val html = """
            <html><body><form action="https://example.com/custom/submit">
              <div id="question1"><h3 class="mark_name">1. 题目</h3></div>
            </form></body></html>
        """.trimIndent()
        val d = CxWorkParser.parse(html, "https://mooc1.chaoxing.com/work/dowork")
        assertEquals("https://example.com/custom/submit", d.submitUrl)
        assertEquals(1, d.questions.size)
    }

    @Test
    fun resolvesProtocolRelativeUrl() {
        val html = """
            <html><body><form action="//mooc1-api.chaoxing.com/mooc-ans/work/submit"></form></body></html>
        """.trimIndent()
        val d = CxWorkParser.parse(html, "https://mooc1.chaoxing.com/work/dowork")
        assertEquals(
            "https://mooc1-api.chaoxing.com/mooc-ans/work/submit",
            d.submitUrl,
        )
    }

    @Test
    fun returnsEmptyQuestionsInsteadOfThrowing() {
        val d = CxWorkParser.parse(
            CxWorkFixtureData.barePageHtml,
            CxWorkFixtureData.DOWORK_URL,
        )
        assertTrue(d.questions.isEmpty())
        assertEquals("学习通", d.title)
        assertTrue(d.submitUrl.isEmpty())
    }

    @Test
    fun findsClientRedirect() {
        assertEquals(
            "https://mooc1.chaoxing.com/doWork?workId=456",
            CxWorkParser.findClientRedirect(
                CxWorkFixtureData.redirectPageHtml,
                CxWorkFixtureData.DOWORK_URL,
            ),
        )
    }
}