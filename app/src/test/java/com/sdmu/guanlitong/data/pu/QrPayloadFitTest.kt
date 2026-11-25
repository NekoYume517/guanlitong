package com.sdmu.guanlitong.data.pu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §6.4 / §7.5：签到码 payload 降级，保证 UTF-8 ≤ 120 字节。 */
class QrPayloadFitTest {

    private val code = "1700000000123"
    private val id = "208791531159552"

    private fun full(title: String) = JSONObject()
        .put("code", code)
        .put("id", id)
        .put("title", title)
        .put("type", 1)

    private fun slim() = JSONObject()
        .put("code", code)
        .put("id", id)
        .put("type", 1)

    @Test
    fun short_title_stays_full() {
        val fit = PuActions.fitSignPayload(full("短"), slim())
        assertEquals("full", fit.level)
        assertTrue(fit.json.contains("\"title\":\"短\""))
    }

    @Test
    fun long_title_is_cut_to_fit() {
        val longTitle = "山东管理学院暑期社会实践签到活动".repeat(10)
        val fit = PuActions.fitSignPayload(full(longTitle), slim())
        assertEquals("cut", fit.level)
        assertTrue(PuActions.utf8Bytes(fit.json) <= 120)
        assertTrue(fit.json.contains("…"))
        assertEquals("full", if (fit.level == "full") "full" else "cut")
    }

    @Test
    fun every_level_fits_when_title_is_huge() {
        val huge = "长".repeat(400)
        val fit = PuActions.fitSignPayload(full(huge), slim())
        assertFalse(fit.level == "full")
        assertTrue(PuActions.utf8Bytes(fit.json) <= 120)
    }

    @Test
    fun slim_is_used_when_even_empty_title_does_not_fit() {
        // 用 1 字节上限模拟极端限制：full 与 cut 都放不下，必须回落到 slim
        val fit = PuActions.fitSignPayload(full("短"), slim(), maxBytes = 1)
        assertEquals("slim", fit.level)
        assertTrue(fit.json.contains("\"code\":\"$code\""))
        assertFalse(fit.json.contains("title"))
    }

    @Test
    fun slim_itself_is_small() {
        assertTrue(PuActions.utf8Bytes(slim().toString()) <= 120)
    }
}