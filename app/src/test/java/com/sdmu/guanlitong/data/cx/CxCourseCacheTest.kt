package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CxCourseCacheTest {

    private fun session(cookie: String) =
        CxSession(phone = "13800000000", cookies = mapOf("UID" to cookie))

    private fun course(courseId: String = "c1", clazzId: String = "k1") =
        CxCourse(courseId, clazzId, "p1", "测试课程", "", "", "")

    private fun groups(name: String) = listOf(
        CxChapterContent(name, listOf(CxChildContent("第一章", emptyList()))),
    )

    @Test
    fun keySeparatesSessionAndCourse() {
        val a = CxCourseCache.keyOf(session("a"), course())
        val b = CxCourseCache.keyOf(session("b"), course())
        val c = CxCourseCache.keyOf(session("a"), course(courseId = "c2"))
        val d = CxCourseCache.keyOf(session("a"), course(clazzId = "k2"))
        val same = CxCourseCache.keyOf(session("a"), course())

        assertEquals(a, same)
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
    }

    @Test
    fun lruEvictsLeastRecentlyUsed() {
        val cache = CxCourseCache(maxEntries = 2)
        cache.put("a", groups("a"))
        cache.put("b", groups("b"))
        assertEquals(groups("a"), cache.get("a")) // 访问 a，b 变成最久未用
        cache.put("c", groups("c"))

        assertNull(cache.get("b"))
        assertEquals(2, cache.size())
    }

    @Test
    fun invalidateRemovesEntry() {
        val cache = CxCourseCache(maxEntries = 2)
        cache.put("a", groups("a"))
        cache.invalidate("a")
        assertNull(cache.get("a"))
    }
}