package com.sdmu.guanlitong.widget

import com.sdmu.guanlitong.data.CxPage
import com.sdmu.guanlitong.data.FeatureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopQuickActionsTest {

    @Test
    fun `三档尺寸分别放下二四八`() {
        assertEquals(2, DesktopQuickActions.forSize(DesktopSize.Small).size)
        assertEquals(4, DesktopQuickActions.forSize(DesktopSize.Medium).size)
        assertEquals(8, DesktopQuickActions.forSize(DesktopSize.Large).size)
    }

    @Test
    fun `新增的学习通与第二课堂入口排在最前`() {
        val ids = DesktopQuickActions.all().map { it.featureId }
        assertEquals(
            listOf("cx.sign", "cx.notices", "cx.courses", "cx.checkin", "pu.activities"),
            ids.take(5),
        )
        assertTrue("pu.credit" in ids)
        assertTrue("jwxt.timetable" in ids)
    }

    @Test
    fun `学习通签到和收件箱复用现有深链参数`() {
        assertEquals("sign", DesktopQuickActions.cxPageOf(FeatureAction.Cx(CxPage.Sign)))
        assertEquals("inbox", DesktopQuickActions.cxPageOf(FeatureAction.Cx(CxPage.Inbox)))
        assertNull(DesktopQuickActions.cxPageOf(FeatureAction.Cx(CxPage.Courses)))
        assertNull(DesktopQuickActions.cxPageOf(FeatureAction.Jwxt(com.sdmu.guanlitong.data.JwxtPage.Grades)))
    }

    @Test
    fun `小号入口都能按顺序找到标题`() {
        assertEquals(
            listOf("签到", "收件箱"),
            DesktopQuickActions.forSize(DesktopSize.Small).map { it.title },
        )
    }
}