package com.sdmu.guanlitong.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class WidgetMetricsTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    // ── 圆环 / 液面占比 ─────────────────────────────────

    @Test
    fun `剩余时间占比按真实时间窗计算`() {
        assertEquals(0.5f, remainingRatio(1_000L, 5_000L, 3_000L), 0.001f)
    }

    @Test
    fun `剩余时间占比 clamp 到 0 和 1`() {
        assertEquals(1f, remainingRatio(1_000L, 5_000L, 0L), 0.001f)
        assertEquals(0f, remainingRatio(1_000L, 5_000L, 9_000L), 0.001f)
    }

    @Test
    fun `缺开始或结束时间时占比为 0`() {
        assertEquals(0f, remainingRatio(0L, 5_000L, 3_000L), 0.001f)
        assertEquals(0f, remainingRatio(1_000L, 0L, 3_000L), 0.001f)
        assertEquals(0f, remainingRatio(5_000L, 1_000L, 3_000L), 0.001f)
    }

    @Test
    fun `学时完成度 clamp 并处理零目标`() {
        assertEquals(0.4f, completionRatio(40.0, 100.0), 0.001f)
        assertEquals(1f, completionRatio(140.0, 100.0), 0.001f)
        assertEquals(0f, completionRatio(40.0, 0.0), 0.001f)
    }

    // ── 更新时间 ───────────────────────────────────────

    @Test
    fun `更新时间今天只显示时分`() {
        val now = ms(2026, 11, 27, 18, 30)
        assertEquals("09:05", freshnessLabel(ms(2026, 11, 27, 9, 5), now, zone))
    }

    @Test
    fun `跨天更新时间带上月日`() {
        val now = ms(2026, 11, 27, 18, 30)
        assertEquals("11月26日 23:59", freshnessLabel(ms(2026, 11, 26, 23, 59), now, zone))
    }

    @Test
    fun `没有时间不显示`() {
        assertNull(freshnessLabel(0L, 1_000L, zone))
    }

    @Test
    fun `缓存超过一小时才标注更新时间`() {
        val now = ms(2026, 11, 27, 18, 30)
        assertNull(staleUpdateLabel(now - 30 * 60_000L, now, zone = zone))
        assertEquals("17:30 更新", staleUpdateLabel(now - 60 * 60_000L, now, zone = zone))
    }

    // ── 刷课时长 ───────────────────────────────────────

    @Test
    fun `时长格式化覆盖分秒和小时`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:35", formatDuration(35))
        assertEquals("12:05", formatDuration(725))
        assertEquals("1:01:01", formatDuration(3661))
    }

    private fun ms(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.time.ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
}