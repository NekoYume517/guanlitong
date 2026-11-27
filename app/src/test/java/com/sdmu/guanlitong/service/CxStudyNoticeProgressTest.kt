package com.sdmu.guanlitong.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 刷课通知进度刻度与节流判定。 */
class CxStudyNoticeProgressTest {

    // ---- 刻度 ----

    @Test
    fun scaleUsesTaskCountWhenTotalKnown() {
        assertEquals(
            CxStudyNoticeProgress.Scale(3, 12),
            CxStudyNoticeProgress.scale(currentIndex = 3, total = 12, taskPercent = 40),
        )
    }

    @Test
    fun scaleClampsIndexIntoTaskCount() {
        assertEquals(
            CxStudyNoticeProgress.Scale(12, 12),
            CxStudyNoticeProgress.scale(currentIndex = 99, total = 12, taskPercent = 0),
        )
        assertEquals(
            CxStudyNoticeProgress.Scale(0, 12),
            CxStudyNoticeProgress.scale(currentIndex = -2, total = 12, taskPercent = 0),
        )
    }

    @Test
    fun scaleFallsBackToPercentWhenTotalUnknown() {
        assertEquals(
            CxStudyNoticeProgress.Scale(45, CxStudyNoticeProgress.PERCENT_MAX),
            CxStudyNoticeProgress.scale(currentIndex = 3, total = 0, taskPercent = 45),
        )
        assertEquals(
            CxStudyNoticeProgress.Scale(100, CxStudyNoticeProgress.PERCENT_MAX),
            CxStudyNoticeProgress.scale(currentIndex = 0, total = -1, taskPercent = 250),
        )
        assertEquals(
            CxStudyNoticeProgress.Scale(0, CxStudyNoticeProgress.PERCENT_MAX),
            CxStudyNoticeProgress.scale(currentIndex = 0, total = 0, taskPercent = -5),
        )
    }

    // ---- 节流 ----

    @Test
    fun firstPostIsAlwaysAllowed() {
        assertTrue(
            CxStudyNoticeProgress.shouldPost(
                final = false,
                changed = true,
                lastPostAtMillis = null,
                nowMillis = 1_000L,
            )
        )
    }

    @Test
    fun unchangedContentIsSkipped() {
        assertFalse(
            CxStudyNoticeProgress.shouldPost(
                final = false,
                changed = false,
                lastPostAtMillis = 0L,
                nowMillis = 60_000L,
            )
        )
    }

    @Test
    fun changedContentIsSkippedInsideInterval() {
        assertFalse(
            CxStudyNoticeProgress.shouldPost(
                final = false,
                changed = true,
                lastPostAtMillis = 10_000L,
                nowMillis = 19_999L,
            )
        )
    }

    @Test
    fun changedContentPostsAtIntervalBoundary() {
        val last = 10_000L
        assertTrue(
            CxStudyNoticeProgress.shouldPost(
                final = false,
                changed = true,
                lastPostAtMillis = last,
                nowMillis = last + CxStudyNoticeProgress.MIN_INTERVAL_MS,
            )
        )
    }

    @Test
    fun finalBypassesThrottle() {
        assertTrue(
            CxStudyNoticeProgress.shouldPost(
                final = true,
                changed = false,
                lastPostAtMillis = 10_000L,
                nowMillis = 10_001L,
            )
        )
    }
}