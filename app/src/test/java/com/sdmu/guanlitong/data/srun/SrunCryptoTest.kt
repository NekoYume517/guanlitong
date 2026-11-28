package com.sdmu.guanlitong.data.srun

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 加密与编码和 Go 版（srun-go.go）逐字节对齐。
 *
 * 期望值都是拿 Go 原版的 `getBase64 / getXencode / md5Hex / sha1Hex` 和
 * `json.Marshal` / `url.Values.Encode` 算出来的固定值，不是照着 Kotlin 的输出回填的。
 * 夹具全部虚构。
 */
class SrunCryptoTest {

    private val challenge = "test-challenge-123"

    private val info =
        """{"username":"demo-user","password":"demo-Passw0rd!","ip":"10.0.0.8","acid":"1","enc_ver":"srun_bx1"}"""

    /** Go 版对上面这串 info + challenge 算出的 `getBase64(getXencode(...))`。 */
    private val encoded =
        "mQ+TUfcHoSUrsWXC7krO3sXnIKr2BcxJz/eCd3hWln8XEqMJ5sd7gT5SZ5wcgJNYZSB7aeFMSF1oXK7rQ77LXhVd7EjXUyr7c9AC20w4" +
            "+DJ6WFYpCFe/oMbKqSlugq1vsEPk6gCYRQM="

    @Test
    fun customBase64MatchesGo() {
        assertEquals("", SrunCrypto.base64(ByteArray(0)))
        assertEquals("ZaRk", SrunCrypto.base64("abc".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("ZaRk1L==", SrunCrypto.base64("abcd".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun xencodeMatchesGoVectors() {
        fun enc(msg: String, key: String) = SrunCrypto.base64(SrunCrypto.xencode(msg, key))

        assertEquals("", enc("", challenge))
        assertEquals("KXukA8BZau8qQN9G", enc("hello", challenge))
        // UTF-8 按字节进加密，和 Go 对字符串逐字节取值一致。
        assertEquals("3rfxGivZdfpnSbjhbaHCES==", enc("校园网", challenge))
        assertEquals("md64RMKbVtP=", enc("200", "abc"))
        // 密钥不足 4 字节时补 0；密钥为空时整串密钥都是 0。
        assertEquals("EhXG7s8KIw4jW8dIXP8Bw+==", enc("hello world", "ab"))
        assertEquals("WYxmoMwcfe/=", enc("abcd", "abc"))
        assertEquals(
            "9VHSU65SpbBEYp/NBtvcZVQ5c0J1lmocCPXheX8loKTrIbR8e0tjw/daqEPoL7EptGvdX1GCNDBMzxqbIb2gfL==",
            enc("""{"username":"","password":"","ip":"","acid":"","enc_ver":""}""", ""),
        )
        // 超过一个分组，走完整的多轮循环。
        assertEquals(
            "SLERgaHYEXIeXE5lO0pmAMtBGEzU1rdT0NfhejnL3Omp3oegLF6r5OAL+1jLnCYGM+aY+kam1fZlgEfy",
            enc("The quick brown fox jumps over the lazy dog. 0123456789", "0123456789abcdef"),
        )
    }

    @Test
    fun infoJsonKeepsKeyOrder() {
        assertEquals(info, SrunCrypto.infoJson("demo-user", "demo-Passw0rd!", "10.0.0.8", "1"))
    }

    @Test
    fun infoJsonEscapesLikeGoMarshal() {
        // 中文原样输出，空格不转义。
        assertEquals(
            "{\"username\":\"校 园 网\",\"password\":\"密 码\",\"ip\":\"10.0.0.8\"," +
                "\"acid\":\"1\",\"enc_ver\":\"srun_bx1\"}",
            SrunCrypto.infoJson("校 园 网", "密 码", "10.0.0.8", "1"),
        )
    }

    @Test
    fun infoJsonEscapesHtmlAndControlCharacters() {
        // Go 的 json.Marshal 默认会转义 < > &，控制字符写成 \u00XX，DEL 原样保留。
        assertEquals(
            "{\"username\":\"u\\u003cx\\u003e\\u0026y\\\"z\\\\w\",\"password\":\"p\\u003cw\\u003e\\u0026d" +
                "\\\"q\\\\e\\t\\f\\b\\n\\r   \\u0001\\u001f\u007f\",\"ip\":\"10.0.0.8\"," +
                "\"acid\":\"1\",\"enc_ver\":\"srun_bx1\"}",
            SrunCrypto.infoJson(
                "u<x>&y\"z\\w",
                "p<w>&d\"q\\e\t\u000C\b\n\r   \u0001\u001F\u007F",
                "10.0.0.8",
                "1",
            ),
        )
        // 行分隔符同样转义（这条规则没有 Go 黄金值，按 json.Marshal 的文档行为对照）。
        assertEquals(
            "{\"username\":\"\\u2028\",\"password\":\"\\u2029\",\"ip\":\"y\"," +
                "\"acid\":\"1\",\"enc_ver\":\"srun_bx1\"}",
            SrunCrypto.infoJson("\u2028", "\u2029", "y", "1"),
        )
    }

    @Test
    fun encodedInfoMatchesGoForTrickyPassword() {
        // Go：getBase64(getXencode(json.Marshal(payload{...}), challenge))
        assertEquals(
            "{SRBX1}hOOZgO3mUxCpOwUVjLYXilatD0ud1zZsOjzPyjSsWXoffqQ8azTRA0QLgqVhi2he5lpgVDG5GPBL1" +
                "2KX22COP5Vp48FAfZRJwIVIU7HU3EegzCFVFU1qb34K5e4rJDUz33UVB9l5XNW+D1ONuRlwO6pymgWtk7vP6ih" +
                "WntyO7IzUcEsFmcugLqdBpmhBUN8ltfu1hK/p/B6G6rCx7yRX1duN0VZ=",
            SrunCrypto.encodeInfo(
                "u<x>&y\"z\\w",
                "p<w>&d\"q\\e\t\u000C\b\n\r   \u0001\u001F\u007F",
                "10.0.0.8",
                "1",
                challenge,
            ),
        )
    }

    @Test
    fun hashesAndChksumMatchGo() {
        assertEquals("e8b21cd30d0e10e96dea1d6999d7880f", SrunCrypto.md5Hex("demo-Passw0rd!"))
        assertEquals("452ddca1e4d5d84ff2d011ad2c8f137d8ad0d678", SrunCrypto.sha1Hex("test-challenge-123demo-user"))

        assertEquals("{SRBX1}$encoded", SrunCrypto.encodeInfo("demo-user", "demo-Passw0rd!", "10.0.0.8", "1", challenge))
        assertEquals(
            "f35869e3b3ba39eb656d843215cc359cbc3c9db3",
            SrunCrypto.chksum(
                challenge, "demo-user", "e8b21cd30d0e10e96dea1d6999d7880f",
                "1", "10.0.0.8", "200", "1", "{SRBX1}$encoded",
            ),
        )
    }

    @Test
    fun queryEncodingFollowsGoValuesEncode() {
        // 键名字典序（`_` 排最前），空值也要出现。
        assertEquals(
            "_=1700000000003&callback=jQuery11240645308969735664_1700000000002&ip=&username=demo-user",
            SrunCrypto.encodeQuery(
                listOf(
                    "callback" to "jQuery11240645308969735664_1700000000002",
                    "username" to "demo-user",
                    "ip" to "",
                    "_" to "1700000000003",
                ),
            ),
        )
        // 空格转 +，只放行 A-Za-z0-9-_.~，其余按 UTF-8 大写百分号编码。
        assertEquals(
            "_=x&a=%E6%B5%8B+%E8%AF%95~%2A%21%28%29%27%5B%5D%7B%7D%22%3A%2F%3F%2C%23%26%3D%2B%24%3B%25%40" +
                "%60%7C%5C%3C%3E&b=%E6%A0%A1%E5%9B%AD%E7%BD%91",
            SrunCrypto.encodeQuery(
                listOf(
                    "a" to "测 试~*!()'[]{}\":/?,#&=+$;%@`|\\<>",
                    "b" to "校园网",
                    "_" to "x",
                ),
            ),
        )
        assertEquals("", SrunCrypto.encodeQuery(emptyList()))
    }

    @Test
    fun jsonpCallbackIsPrefixPlusMillis() {
        assertEquals("jQuery11240645308969735664_1700000000000", SrunCrypto.jsonpCallback(1700000000000L))
    }
}
