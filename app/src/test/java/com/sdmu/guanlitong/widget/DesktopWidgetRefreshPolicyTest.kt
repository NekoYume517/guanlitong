package com.sdmu.guanlitong.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWidgetRefreshPolicyTest {

    private val now = 1_000_000_000L
    private val ttl = DesktopWidgetRefreshPolicy.TIMETABLE_TTL_MS

    @Test
    fun `缓存未过期时不刷新`() {
        assertFalse(DesktopWidgetRefreshPolicy.shouldRefresh(now - ttl / 2, 0, now))
    }

    @Test
    fun `缓存过期且没有失败记录时刷新`() {
        assertTrue(DesktopWidgetRefreshPolicy.shouldRefresh(now - ttl - 1, 0, now))
    }

    @Test
    fun `过期后失败还在冷却期则不重试`() {
        val failure = now - 60_000L
        assertFalse(DesktopWidgetRefreshPolicy.shouldRefresh(now - ttl - 1, failure, now))
    }

    @Test
    fun `冷却结束后允许再次尝试`() {
        val failure = now - DesktopWidgetRefreshPolicy.STALE_RETRY_GAP_MS - 1
        assertTrue(DesktopWidgetRefreshPolicy.shouldRefresh(now - ttl - 1, failure, now))
    }

    @Test
    fun `无缓存时允许冷启动拉一次`() {
        assertTrue(DesktopWidgetRefreshPolicy.shouldRefresh(null, 0, now))
    }

    @Test
    fun `无缓存且刚失败过则不重复拉取`() {
        val failure = now - DesktopWidgetRefreshPolicy.NO_CACHE_RETRY_GAP_MS / 2
        assertFalse(DesktopWidgetRefreshPolicy.shouldRefresh(null, failure, now))
    }
}