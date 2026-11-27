package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 课程缓存必须同时认「账号指纹」和 TTL，换号/重登不能拿别人的课程去拉活动。 */
class CxCoursesCacheTest {

    private fun courses(name: String) =
        listOf(CxCourse("c1", "k1", "p1", name, "", "", ""))

    @Test
    fun hitsWithinTtlForSameAccount() {
        val cache = CxCoursesCache(ttlMs = 300_000)
        val list = courses("高等数学")
        cache.put("acct-a", 1_000, list)

        assertEquals(list, cache.get("acct-a", 1_000))
        assertEquals(list, cache.get("acct-a", 1_000 + 299_999))
    }

    @Test
    fun missesAfterTtl() {
        val cache = CxCoursesCache(ttlMs = 300_000)
        cache.put("acct-a", 1_000, courses("高等数学"))

        assertNull(cache.get("acct-a", 1_000 + 300_000))
    }

    @Test
    fun defaultTtlIsThirtyMinutes() {
        val cache = CxCoursesCache()
        val list = courses("高等数学")
        cache.put("acct-a", 1_000, list)

        assertEquals(CX_SIGN_COURSE_CACHE_MS, 30 * 60_000L)
        assertEquals(list, cache.get("acct-a", 1_000 + 29 * 60_000L))
        assertNull("30 分钟后必须重拉课程列表", cache.get("acct-a", 1_000 + 30 * 60_000L))
    }

    @Test
    fun missesWhenAccountChanges() {
        val cache = CxCoursesCache(ttlMs = 300_000)
        cache.put("acct-a", 1_000, courses("高等数学"))

        assertNull("换账号必须失效", cache.get("acct-b", 1_001))
    }

    @Test
    fun rePutReplacesValue() {
        val cache = CxCoursesCache(ttlMs = 300_000)
        cache.put("acct-a", 1_000, courses("高等数学"))
        cache.put("acct-a", 2_000, courses("线性代数"))

        assertEquals(courses("线性代数"), cache.get("acct-a", 2_000))
    }
}