package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 风控响应识别：`result:0 + errorMsg` 要解析出「解除时间」并上抛受限，
 * 普通空列表/普通失败不能误判成受限。解析不出时间就退避 30 分钟。
 */
class CxSignRestrictionTest {

    private val now = CxDateTime.parseMs("2026-11-27 12:00:00", 0L)!!
    private val until = CxDateTime.parseMs("2026-11-28 14:28:03", now)!!

    @Test
    fun parsesRestrictionWithUntilTime() {
        val body = """{"result":0,"errorMsg":"您已被限制访问，解除时间 2026-11-28 14:28:03"}"""

        val restriction = parseSignRestriction(body, now)!!

        assertEquals("解除时间按北京时间解析", until, restriction.untilMs)
        assertTrue(restriction.message.contains("限制访问"))
        assertTrue("横幅带解除时间", restrictionBannerText(restriction).startsWith("学习通限制访问，解除时间 "))
    }

    @Test
    fun normalResponseIsNotRestriction() {
        assertNull(parseSignRestriction("""{"result":1,"data":[]}""", now))
        assertNull(parseSignRestriction("""{"result":0,"errorMsg":"活动不存在"}""", now))
        assertNull(parseSignRestriction("""{"status":false,"msg":"未登录"}""", now))
        assertNull(parseSignRestriction("<html>系统繁忙</html>", now))
    }

    @Test
    fun missingUntilFallsBackToHalfHour() {
        val restriction = parseSignRestriction(
            """{"result":0,"errorMsg":"您已被限制访问"}""",
            now,
        )!!

        assertEquals(now + CX_SIGN_RESTRICTION_FALLBACK_MS, restriction.untilMs)
    }

    @Test
    fun pastUntilStillBacksOffHalfHour() {
        val restriction = parseSignRestriction(
            """{"result":0,"errorMsg":"您已被限制访问，解除时间 2026-11-27 11:00:00"}""",
            now,
        )!!

        assertEquals("已经过去的时间不能当成立即重试", now + CX_SIGN_RESTRICTION_FALLBACK_MS, restriction.untilMs)
    }

    @Test
    fun plainTextBodyWithKeywordIsDetected() {
        val restriction = parseSignRestriction("您已被限制访问，解除时间 2026-11-28 14:28:03", now)!!

        assertEquals(until, restriction.untilMs)
    }

    @Test
    fun exceptionCarriesRestriction() {
        val restriction = CxSignRestriction(until, "您已被限制访问")
        val thrown = CxSignRestrictedException(restriction)

        assertEquals(restriction, thrown.restriction)
        assertEquals("您已被限制访问", thrown.message)
    }
}