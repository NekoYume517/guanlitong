package com.sdmu.guanlitong.data.pu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §7.5：扫码分类。 */
class PuScanTest {

    @Test
    fun plain_user_code_is_user() {
        val r = PuScan.classify("xyhui://user/208791531159552/1700000000/test")
        assertTrue(r is PuScanResult.User)
        r as PuScanResult.User
        assertEquals("208791531159552", r.uid)
        assertEquals("1700000000", r.ts)
        assertEquals("test", r.username)
    }

    @Test
    fun encrypted_user_code_is_user() {
        val code = PuPersonalCodec.encode("208791531159552", "test", 1700000000L)
        assertTrue(PuScan.classify(code) is PuScanResult.User)
    }

    @Test
    fun json_sign_code_is_activity() {
        val r = PuScan.classify("""{"code":"1700000000123","id":"456","type":1}""")
        assertTrue(r is PuScanResult.Activity)
        r as PuScanResult.Activity
        assertEquals("1700000000123", r.mid)
        assertEquals(456L, r.id)
    }

    @Test
    fun bare_digits_is_activity() {
        val r = PuScan.classify("1700000000123")
        assertTrue(r is PuScanResult.Activity)
        r as PuScanResult.Activity
        assertEquals("1700000000123", r.mid)
        assertEquals(0L, r.id)
    }

    @Test
    fun unknown_text_is_text() {
        assertTrue(PuScan.classify("hello world") is PuScanResult.Text)
        assertTrue(PuScan.classify("") is PuScanResult.Text)
    }

    @Test
    fun invalid_base64_is_text() {
        assertTrue(PuScan.classify("!!!!not-base64!!!!") is PuScanResult.Text)
        // 合法 Base64，但 DES 解不出 xyhui://user/ 前缀
        assertTrue(PuScan.classify("YWJjZGVmZ2g=") is PuScanResult.Text)
    }

    @Test
    fun json_without_ids_is_text() {
        assertTrue(PuScan.classify("""{"type":1}""") is PuScanResult.Text)
    }

    @Test
    fun activity_id_key_alias_is_accepted() {
        val r = PuScan.classify("""{"mid":"1700000000123","activityId":"789"}""")
        assertTrue(r is PuScanResult.Activity)
        r as PuScanResult.Activity
        assertEquals("1700000000123", r.mid)
        assertEquals(789L, r.id)
    }
}