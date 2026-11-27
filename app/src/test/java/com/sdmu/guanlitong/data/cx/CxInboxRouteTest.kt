package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 收件箱详情路由：签到/作业/链接判定与签到参数解析。 */
class CxInboxRouteTest {

    @Test
    fun checkinTypeRoutesToSign() {
        assertEquals(CxInboxAction.SIGN, inboxAction("inbox_checkin", null))
        assertEquals(CxInboxAction.SIGN, inboxAction("inbox_notice", "https://x/preSign?x=1"))
        assertEquals(CxInboxAction.SIGN, inboxAction("inbox_other", "https://x/SIGN?x=1"))
    }

    @Test
    fun workAndLinkAndNone() {
        assertEquals(CxInboxAction.WORK, inboxAction("inbox_work", "https://x/work?x=1"))
        assertEquals(CxInboxAction.LINK, inboxAction("inbox_notice", "https://x/a"))
        assertEquals(CxInboxAction.NONE, inboxAction("inbox_notice", null))
    }

    @Test
    fun parsesTypicalSignUrl() {
        val target = parseSignTarget(
            "https://mobilelearn.chaoxing.com/page/active/show?id=123&courseId=456&classId=789",
        )
        assertEquals(CxSignTarget("456", "789", "123"), target)
    }

    @Test
    fun acceptsLowercaseAndActiveIdParameterNames() {
        assertEquals(
            CxSignTarget("1", "2", "3"),
            parseSignTarget("https://x/y?courseid=1&clazzid=2&activeId=3"),
        )
        assertEquals(
            CxSignTarget("1", "2", "3"),
            parseSignTarget("https://x/y?courseId=1&clazzId=2&activePrimaryId=3"),
        )
    }

    @Test
    fun decodesEscapedValues() {
        assertEquals(
            CxSignTarget("1", "2", "a b"),
            parseSignTarget("https://x/y?courseId=1&classId=2&id=a%20b"),
        )
    }

    @Test
    fun missingCourseOrClassIsNull() {
        assertNull(parseSignTarget(null))
        assertNull(parseSignTarget(""))
        assertNull(parseSignTarget("https://x/y?courseId=1"))
        assertNull(parseSignTarget("not a url"))
    }

    @Test
    fun typeLabelFallsBackToNotice() {
        assertEquals("签到", inboxTypeLabel("inbox_checkin"))
        assertEquals("通知", inboxTypeLabel("something-else"))
    }
}