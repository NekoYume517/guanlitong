package com.sdmu.guanlitong.data.pu

import org.junit.Assert.assertEquals
import org.junit.Test

/** §7.5：个人码编解码。 */
class PuPersonalCodeTest {

    private val uid = "208791531159552"

    @Test
    fun encode_and_decode_roundtrip() {
        val code = PuPersonalCodec.encode(uid, "test", 1700000000L)
        assertEquals(
            "xyhui://user/$uid/1700000000/test",
            PuScan.decryptPersonalCode(code),
        )
    }

    /**
     * §2.3 / §7.5 写「固定 76 字符」，但 76 只对 48~55 字节明文成立：
     * 明文 = `xyhui://user/`(13) + uid(15) + `/`(1) + 秒级时间戳(10) + `/`(1) + username，
     * 即 40 + username 长度。`test`(4) → 44 字节 → PKCS5 补到 48 → Base64 64 字符；
     * `testuser`(8) → 48 字节 → PKCS5 整块补 8 → 56 字节 → Base64 76 字符。
     * 即 username 8~15 字符时才是 76 字符，这里两种都断言。
     */
    @Test
    fun length_is_76_only_for_48_to_55_byte_plaintext() {
        assertEquals(64, PuPersonalCodec.encode(uid, "test", 1700000000L).length)
        assertEquals(76, PuPersonalCodec.encode(uid, "testuser", 1700000000L).length)
    }

    @Test
    fun decode_rejects_garbage() {
        assertEquals("", PuScan.decryptPersonalCode("!!!!"))
        assertEquals("", PuScan.decryptPersonalCode("YWJjZGVmZ2g="))
        assertEquals("", PuScan.decryptPersonalCode(""))
    }
}