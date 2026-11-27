package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * activelist 响应解析：字段兜底必须照老项目 `get_active_list`，否则活动 id/标题
 * 为空时通知里是一片空白，签退也发现不了未开始的活动。
 */
class CxCheckinParseTest {

    @Test
    fun diagnosticsDescribeResultAndDataShape() {
        val body = """{"result":true,"data":{"activeList":[{"id":"1"}],"records":[]},"msg":"ok"}"""

        val text = activeListDiagnostics(body)

        assertTrue(text, text.contains("result=bool:true"))
        assertTrue(text, text.contains("data=object activeList=1 records=0"))
        assertTrue(text, text.contains("msg=ok"))
    }

    @Test
    fun diagnosticsHandleNumericResultArrayAndInvalidJson() {
        val text = activeListDiagnostics("""{"result":1,"data":[]}""")
        assertTrue(text, text.contains("result=num:1"))
        assertTrue(text, text.contains("data=array data=0"))
        assertEquals("json=invalid", activeListDiagnostics("<html>login</html>"))
    }

    @Test
    fun diagnosticsTruncateMessages() {
        val long = "错".repeat(300)
        val text = activeListDiagnostics("""{"result":true,"data":null,"error":"$long"}""")

        assertTrue(text, text.contains("error="))
        assertTrue("msg 截断到 200 字", text.length < 300)
    }

    @Test
    fun loginBodyContainsTitleOrPassportOrLoginWord() {
        assertTrue(looksLikeLoginBody("<html><title>登录</title></html>"))
        assertTrue(looksLikeLoginBody("""<script>location.href="https://passport2.chaoxing.com/login"</script>"""))
        assertTrue(looksLikeLoginBody("请先登录"))
        assertTrue("普通 JSON 不能误判", !looksLikeLoginBody("""{"result":true,"data":[]}"""))
    }

    @Test
    fun numericResultOneParsesActiveList() {
        val body = """{"result":1,"msg":null,"data":{"activeList":[
            {"id":"1","nameOne":"线性代数签到","activeType":2,"status":1}
        ]}}"""

        val actives = parseActiveList(body)

        assertEquals("服务端 result 是数字 1，必须按成功解析", 1, actives.size)
        assertEquals("1", actives.single().id)
        assertEquals(1, actives.single().status)
    }

    @Test
    fun numericResultZeroIsFailure() {
        val body = """{"result":0,"data":{"activeList":[{"id":"1"}]}}"""
        assertTrue(parseActiveList(body).isEmpty())
    }

    @Test
    fun resultTrueFalseAndStringOne() {
        assertEquals(
            1,
            parseActiveList("""{"result":true,"data":{"activeList":[{"id":"1"}]}}""").size,
        )
        assertTrue(parseActiveList("""{"result":false,"data":{"activeList":[{"id":"1"}]}}""").isEmpty())
        assertEquals(
            1,
            parseActiveList("""{"result":"1","data":{"activeList":[{"id":"2"}]}}""").size,
        )
        assertEquals(
            1,
            parseActiveList("""{"result":"TRUE","data":{"activeList":[{"id":"3"}]}}""").size,
        )
        assertTrue(parseActiveList("""{"result":"0","data":{"activeList":[{"id":"4"}]}}""").isEmpty())
    }

    @Test
    fun missingResultIsFailure() {
        assertTrue(parseActiveList("""{"data":{"activeList":[{"id":"1"}]}}""").isEmpty())
        assertTrue(parseActiveList("""{"result":null,"data":{"activeList":[{"id":"1"}]}}""").isEmpty())
    }

    @Test
    fun dataArrayWithNumericResultStillParses() {
        val actives = parseActiveList("""{"result":1,"data":[{"id":"a"},{"id":"b"}]}""")
        assertEquals(listOf("a", "b"), actives.map { it.id })
    }

    @Test
    fun nonOpenStatusIsParsedNotWidened() {
        // 解析层不负责筛开放状态；status=2 的活动仍保留，由扫描器的 status==1 过滤。
        val actives = parseActiveList(
            """{"result":1,"data":{"activeList":[
                {"id":"open","activeType":2,"status":1},
                {"id":"closed","activeType":2,"status":2}
            ]}}""",
        )

        assertEquals(listOf("open", "closed"), actives.map { it.id })
        assertEquals(2, actives.last().status)
    }

    @Test
    fun successFlagIsTolerantAcrossTypes() {
        val success = listOf<Any?>(true, 1, 1.0, 2, "1", "true", " TRUE ", " true ")
        val failure = listOf<Any?>(false, 0, 0.0, "0", "false", "", null, "yes")

        success.forEach { assertTrue("应判成功：$it", cxSuccessFlag(it)) }
        failure.forEach { assertTrue("应判失败：$it", !cxSuccessFlag(it)) }
    }

    @Test
    fun resultFalseReturnsEmpty() {
        val body = """{"result":false,"data":{"activeList":[{"id":"1","name":"签到"}]}}"""
        assertTrue(parseActiveList(body).isEmpty())
    }

    @Test
    fun invalidJsonReturnsEmpty() {
        assertTrue(parseActiveList("not json").isEmpty())
        assertTrue(parseActiveList("").isEmpty())
    }

    @Test
    fun dataCanBeRecordListOrArray() {
        assertEquals(1, parseActiveList("""{"result":true,"data":{"activeList":[{"id":"1"}]}}""").size)
        assertEquals(1, parseActiveList("""{"result":true,"data":{"records":[{"id":"2"}]}}""").size)
        assertEquals(1, parseActiveList("""{"result":true,"data":{"list":[{"id":"3"}]}}""").size)
        assertEquals(1, parseActiveList("""{"result":true,"data":[{"id":"4"}]}""").size)
    }

    @Test
    fun idFallsBackToActivePrimaryId() {
        val active = parseActiveList(
            """{"result":true,"data":{"activeList":[{"activePrimaryId":"987"}]}}""",
        ).single()
        assertEquals("987", active.id)
    }

    @Test
    fun nameFallsBackToNameOneActiveNameThenDefault() {
        val actives = parseActiveList(
            """{"result":true,"data":{"activeList":[
                {"id":"1","nameOne":"第一节签到"},
                {"id":"2","activeName":"课堂签退"},
                {"id":"3"}
            ]}}""",
        )
        assertEquals("第一节签到", actives[0].name)
        assertEquals("课堂签退", actives[1].name)
        assertEquals("签到", actives[2].name)
    }

    @Test
    fun activeTypeFallsBackToTypeAndOtherFieldsSurvive() {
        val active = parseActiveList(
            """{"result":true,"data":{"activeList":[{
                "id":"1","name":"签退","type":74,"status":1,
                "otherId":"4","startTime":"2026-11-27 10:00",
                "endTime":"2026-11-27 10:05","address":"教学楼"
            }]}}""",
        ).single()

        assertEquals(74, active.activeType)
        assertTrue(active.isCheckout)
        assertEquals(1, active.status)
        assertEquals(4, active.otherIdValue)
        assertEquals("教学楼", active.address)
        assertEquals("2026-11-27 10:05", active.endTime)
    }

    @Test
    fun activeTypePrefersActiveTypeOverType() {
        val active = parseActiveList(
            """{"result":true,"data":{"activeList":[{"id":"1","activeType":2,"type":74}]}}""",
        ).single()
        assertEquals(2, active.activeType)
    }
}