package com.sdmu.guanlitong.ui.zhs

import org.junit.Assert.assertEquals
import org.junit.Test

class ZhsFormatTest {

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("0:00", formatSeconds(0))
        assertEquals("0:05", formatSeconds(5))
        assertEquals("1:05", formatSeconds(65))
        assertEquals("30:30", formatSeconds(1830))
    }

    @Test
    fun formatsHours() {
        assertEquals("1:00:00", formatSeconds(3600))
        assertEquals("1:02:05", formatSeconds(3725))
    }

    @Test
    fun clampsNegativeInput() {
        assertEquals("0:00", formatSeconds(-5))
    }

    @Test
    fun formatsScores() {
        assertEquals("—", formatScore(null))
        assertEquals("86", formatScore(86.0))
        assertEquals("86.5", formatScore(86.5))
        assertEquals("0", formatScore(0.0))
    }
}