package com.sdmu.guanlitong.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 刷课唤醒锁的获取/释放边界：幂等、异常兜底、超时上限。 */
class BrushWakeLockTest {

    /** 平台侧假实现：记录调用次数，可切换成功/失败/抛异常。 */
    private class Platform(timeoutMs: Long = 42_000L, tag: String = "test:wake") {
        var acquires = 0
        var releases = 0
        var lastTag = ""
        var lastTimeout = 0L
        var result: () -> Boolean = { true }

        val lock = BrushWakeLock(
            tag = tag,
            timeoutMs = timeoutMs,
            acquirePlatform = { t, timeout ->
                acquires++
                lastTag = t
                lastTimeout = timeout
                result()
            },
            releasePlatform = { releases++ },
        )
    }

    @Test
    fun acquireIsIdempotent() {
        val platform = Platform()
        assertTrue(platform.lock.acquire())
        assertTrue(platform.lock.acquire())
        assertTrue(platform.lock.isHeld)
        assertEquals(1, platform.acquires)
        assertEquals(0, platform.releases)
    }

    @Test
    fun releaseWithoutAcquireDoesNothing() {
        val platform = Platform()
        platform.lock.release()
        assertFalse(platform.lock.isHeld)
        assertEquals(0, platform.releases)
    }

    @Test
    fun releaseIsIdempotent() {
        val platform = Platform()
        platform.lock.acquire()
        platform.lock.release()
        platform.lock.release()
        assertFalse(platform.lock.isHeld)
        assertEquals(1, platform.releases)
    }

    @Test
    fun acquireFailureKeepsLockReleasedAndCanRetry() {
        val platform = Platform()
        platform.result = { false }
        assertFalse(platform.lock.acquire())
        assertFalse(platform.lock.isHeld)

        platform.result = { true }
        assertTrue(platform.lock.acquire())
        assertEquals(2, platform.acquires)
    }

    @Test
    fun platformExceptionIsContained() {
        val platform = Platform()
        platform.result = { error("no wake lock") }
        assertFalse(platform.lock.acquire())
        assertFalse(platform.lock.isHeld)
        // 失败后 release 不能反过来调用平台释放。
        platform.lock.release()
        assertEquals(0, platform.releases)
    }

    @Test
    fun acquireAfterReleaseTakesLockAgain() {
        val platform = Platform()
        platform.lock.acquire()
        platform.lock.release()
        assertTrue(platform.lock.acquire())
        assertTrue(platform.lock.isHeld)
        assertEquals(2, platform.acquires)
        assertEquals(1, platform.releases)
    }

    @Test
    fun tagAndTimeoutAreForwarded() {
        val platform = Platform(timeoutMs = 7_000L, tag = "guanlitong:test")
        platform.lock.acquire()
        assertEquals("guanlitong:test", platform.lastTag)
        assertEquals(7_000L, platform.lastTimeout)
    }

    @Test
    fun defaultTimeoutIsBoundedToOneDay() {
        var forwarded = 0L
        val lock = BrushWakeLock(
            tag = "t",
            acquirePlatform = { _, timeout ->
                forwarded = timeout
                true
            },
            releasePlatform = {},
        )
        lock.acquire()
        assertEquals(BrushWakeLock.TIMEOUT_MS, forwarded)
        assertTrue(BrushWakeLock.TIMEOUT_MS <= 24L * 60 * 60 * 1000)
        assertTrue(BrushWakeLock.TIMEOUT_MS > 0)
    }
}