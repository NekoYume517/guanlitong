package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** 学习通时间字段解析：毫秒/常见日期格式/无年份补当前年，统一按北京时间。 */
class CxDateTimeTest {

    private fun msOf(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(CxDateTime.ZONE).toInstant().toEpochMilli()

    @Test
    fun parsesStandardFormats() {
        assertEquals(msOf(2026, 12, 20, 23, 59), CxDateTime.parseMs("2026-12-20 23:59"))
        assertEquals(msOf(2026, 12, 20, 23, 59), CxDateTime.parseMs("2026/12/20 23:59"))
        assertEquals(msOf(2026, 12, 20, 23, 59), CxDateTime.parseMs("2026年12月20日 23:59"))
        // 只有日期时按当天 00:00 处理（老项目同口径），不猜一个「当天结束」。
        assertEquals(msOf(2026, 12, 20, 0, 0), CxDateTime.parseMs("2026-12-20"))
        assertEquals(msOf(2026, 12, 20, 11, 0), CxDateTime.parseMs("2026-12-20 11:00:00.0"))
    }

    @Test
    fun parsesEpochMillis() {
        val ms = 1_766_209_200_000L
        assertEquals(ms, CxDateTime.parseMs(ms.toString()))
        assertEquals("2025-12-20 13:40", CxDateTime.format(ms))
    }

    @Test
    fun epochSecondsAreNotMistakenForMillis() {
        // 10 位是秒级时间戳，不能直接当毫秒（会被格式化成 1970 年）。
        assertNull(CxDateTime.parseMs("1766209200"))
    }

    @Test
    fun monthDayUsesCurrentYear() {
        val now = msOf(2026, 6, 1)
        assertEquals(msOf(2026, 12, 20, 9, 30), CxDateTime.parseMs("12-20 09:30", now))
    }

    @Test
    fun blankAndGarbageReturnNull() {
        assertNull(CxDateTime.parseMs(null))
        assertNull(CxDateTime.parseMs(""))
        assertNull(CxDateTime.parseMs("不限"))
        assertNull(CxDateTime.parseMs("13-45 99:99", msOf(2026, 1, 1)))
    }
}