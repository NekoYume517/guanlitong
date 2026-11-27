package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Test

/** 刷课时长格式：MM:SS，负数按 0，超过 99 分钟按真实分钟数。 */
class CxTimeFormatTest {

    @Test
    fun zeroAndSecondsBelowAMinute() {
        assertEquals("00:00", cxFormatDuration(0))
        assertEquals("00:01", cxFormatDuration(1))
        assertEquals("00:59", cxFormatDuration(59))
    }

    @Test
    fun minuteBoundary() {
        assertEquals("01:00", cxFormatDuration(60))
        assertEquals("01:01", cxFormatDuration(61))
    }

    @Test
    fun minuteBoundaryUnderAnHour() {
        assertEquals("59:59", cxFormatDuration(3599))
    }

    @Test
    fun hourBoundaryAndBeyond() {
        // 3600 秒是 60 分钟，不是「01:00:00」；分钟位不截断。
        assertEquals("60:00", cxFormatDuration(3600))
        assertEquals("100:00", cxFormatDuration(6000))
        assertEquals("120:30", cxFormatDuration(7230))
    }

    @Test
    fun negativeTreatedAsZero() {
        assertEquals("00:00", cxFormatDuration(-1))
        assertEquals("00:00", cxFormatDuration(Int.MIN_VALUE))
    }
}