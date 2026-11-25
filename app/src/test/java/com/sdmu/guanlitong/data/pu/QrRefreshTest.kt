package com.sdmu.guanlitong.data.pu

import org.junit.Assert.assertEquals
import org.junit.Test

/** §6.4 / §7.5：个人码刷新间隔钳制（5..600，其余一律 30 秒）。 */
class QrRefreshTest {

    @Test
    fun inside_range_kept() {
        assertEquals(5, PuActions.clampRefresh(5))
        assertEquals(30, PuActions.clampRefresh(30))
        assertEquals(600, PuActions.clampRefresh(600))
    }

    @Test
    fun below_range_falls_back_to_30() {
        assertEquals(30, PuActions.clampRefresh(0))
        assertEquals(30, PuActions.clampRefresh(3))
        assertEquals(30, PuActions.clampRefresh(4))
        assertEquals(30, PuActions.clampRefresh(-10))
    }

    @Test
    fun above_range_falls_back_to_30() {
        assertEquals(30, PuActions.clampRefresh(601))
        assertEquals(30, PuActions.clampRefresh(Int.MAX_VALUE))
    }
}