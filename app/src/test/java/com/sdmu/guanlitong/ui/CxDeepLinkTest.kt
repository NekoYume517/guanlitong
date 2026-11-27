package com.sdmu.guanlitong.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 学习通深链识别规则：签到页、收件箱、手势签到，以及不认识 / 空 intent。 */
class CxDeepLinkTest {

    @Test
    fun signPageRoute() {
        assertEquals(CxRoute.Sign, CxDeepLink.routeOf(page = "sign", activeId = null))
    }

    @Test
    fun inboxPageRoute() {
        assertEquals(CxRoute.Inbox, CxDeepLink.routeOf(page = "inbox", activeId = null))
    }

    @Test
    fun gestureWinsOverPageParam() {
        val route = CxDeepLink.routeOf(
            page = "sign",
            activeId = "a1",
            courseId = "c1",
            classId = "k1",
        )

        assertEquals(CxRoute.Gesture("a1", "c1", "k1"), route)
    }

    @Test
    fun gestureWithoutNamesFallsBackToEmptyStrings() {
        val route = CxDeepLink.routeOf(page = null, activeId = "a1") as CxRoute.Gesture

        assertEquals("", route.courseName)
        assertEquals("", route.activeName)
        assertEquals("", route.courseId)
        assertEquals("", route.classId)
    }

    @Test
    fun blankActiveIdDoesNotCountAsGesture() {
        assertEquals(
            CxRoute.Sign,
            CxDeepLink.routeOf(page = "sign", activeId = "  "),
        )
    }

    @Test
    fun unknownOrMissingPageIsIgnored() {
        assertNull(CxDeepLink.routeOf(page = "courses", activeId = null))
        assertNull(CxDeepLink.routeOf(page = null, activeId = null))
        assertNull(CxDeepLink.routeOf(intent = null))
    }
}