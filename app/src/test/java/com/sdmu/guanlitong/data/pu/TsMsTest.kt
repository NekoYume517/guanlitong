package com.sdmu.guanlitong.data.pu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** §7.5：时间戳归一（10 位秒 / 13 位毫秒 / 19 字符日期串）。 */
class TsMsTest {

    @Test
    fun ten_digits_seconds_becomes_millis() {
        assertEquals(1_700_000_000_000L, PuActions.tsMs("1700000000"))
    }

    @Test
    fun thirteen_digits_millis_unchanged() {
        assertEquals(1_700_000_000_000L, PuActions.tsMs("1700000000000"))
    }

    @Test
    fun nineteen_char_datetime_parses() {
        val text = "2024-11-15 12:34:56"
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse(text)!!.time
        assertEquals(expected, PuActions.tsMs(text))
        assertTrue(PuActions.tsMs(text) > 0L)
    }

    @Test
    fun longer_datetime_uses_first_19_chars() {
        assertEquals(
            PuActions.tsMs("2024-11-15 12:34:56"),
            PuActions.tsMs("2024-11-15 12:34:56.123"),
        )
    }

    @Test
    fun invalid_values_are_zero() {
        assertEquals(0L, PuActions.tsMs(""))
        assertEquals(0L, PuActions.tsMs("   "))
        assertEquals(0L, PuActions.tsMs(null))
        assertEquals(0L, PuActions.tsMs("abc"))
        assertEquals(0L, PuActions.tsMs("2024-11-15"))
    }

    @Test
    fun trims_whitespace() {
        assertEquals(1_700_000_000_000L, PuActions.tsMs(" 1700000000 "))
    }
}