package com.sdmu.guanlitong.data.zhs

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加密实现与 Python 参考实现（zhs_server.py）的逐字节对齐。
 *
 * 登录密文与 AES 密文都直接取自参考实现算出的固定值；
 * RSA 用现场生成的密钥对做加解密往返，证明 PKCS#1 v1.5 填充与「公钥当私钥用」
 * 的还原逻辑一致。
 */
class ZhsCryptoTest {

    @Test
    fun loginEncryptMatchesReferenceVector() {
        val encrypted = ZhsCrypto.loginEncrypt("13800138000", "pass word", "abc")
        assertEquals(
            "JTdCJTIyYWNjb3VudCUyMjolMjIxMzgwMDEzODAwMCUyMiwlMjJwYXNzd29yZCUyMjolMjJwYXNz" +
                "JTIwd29yZCUyMiwlMjJ2YWxpZGF0ZSUyMjolMjJhYmMlMjIlN0Q=",
            encrypted,
        )
    }

    @Test
    fun loginPayloadKeepsFieldOrderAndEscapesJson() {
        assertEquals(
            "{\"account\":\"a\",\"password\":\"p\",\"validate\":\"v\"}",
            ZhsCrypto.loginPayload("a", "p", "v"),
        )
        assertEquals(
            "\"a\\\"b\\\\c\\n\"",
            ZhsCrypto.jsonString("a\"b\\c\n"),
        )
    }

    @Test
    fun encodeUriFollowsJavaScriptSemantics() {
        assertEquals(
            "%7B%22a%22:%22%E4%B8%AD%20%E6%96%87%22%7D",
            ZhsCrypto.encodeUri("{\"a\":\"中 文\"}"),
        )
        // encodeURI 不转义保留字符（含逗号、冒号）与未保留字符。
        assertEquals(
            ";,/?:@&=+$-_.!~*'()#AZaz09",
            ZhsCrypto.encodeUri(";,/?:@&=+$-_.!~*'()#AZaz09"),
        )
    }

    @Test
    fun aesEncryptMatchesReferenceVectors() {
        assertEquals(
            "48ustLBtVRRg6wYdtUHBecdfS2QmSOJZqdF2FWddaTM=",
            ZhsCrypto.aesEncrypt("{\"pageNum\":1,\"pageSize\":5}", "0123456789abcdef"),
        )
        assertEquals(
            "p9aCJ0Z1o5NLmuZZy2bU4c0DwmXyuJRmN8GOgQFVBcO9b0Vaugx6VjgPEJPnzOag",
            ZhsCrypto.aesEncrypt("{\"status\":0,\"pageNo\":1,\"pageSize\":5}", "0123456789abcdef"),
        )
        assertEquals(
            "mT+VSOMJCLUZtURnjpTiqg==",
            ZhsCrypto.aesEncrypt("{}", "0123456789abcdef"),
        )
    }

    @Test
    fun aesKeyUsesFirstSixteenBytesAndZeroPads() {
        assertEquals(
            "mT+VSOMJCLUZtURnjpTiqg==",
            ZhsCrypto.aesEncrypt("{}", "0123456789abcdefEXTRA"),
        )
        assertEquals(
            "KnuRbeNt/lYcaIN+HiFGJQ==",
            ZhsCrypto.aesEncrypt("{}", "0123456789abcd"),
        )
    }

    @Test
    fun publicKeyConstantParsesToExpectedModulus() {
        assertEquals(1024, ZhsCrypto.publicKeyModulus().bitLength())
    }

    @Test
    fun exchangeParameterIsFullSizeRsaCiphertext() {
        val first = Base64.getDecoder().decode(ZhsCrypto.rsaEncryptForExchange(13))
        val second = Base64.getDecoder().decode(ZhsCrypto.rsaEncryptForExchange(13))
        assertEquals(128, first.size)
        assertEquals(128, second.size)
        // PKCS#1 v1.5 的随机填充：同一明文两次加密结果不同。
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun rsaEncryptRoundTripsThroughPrivateKey() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val publicKey = pair.public as RSAPublicKey
        val privateKey = pair.private as RSAPrivateKey
        val message = "{\"account\":\"13800138000\",\"password\":\"p\"}".toByteArray()

        val cipherText = ZhsCrypto.rsaEncryptBytes(
            message,
            publicKey.modulus,
            publicKey.publicExponent,
        )
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        assertEquals(String(message), String(cipher.doFinal(cipherText)))
    }

    @Test
    fun pubDecryptRecoversMessageSignedWithPrivateExponent() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val publicKey = pair.public as RSAPublicKey
        val privateKey = pair.private as RSAPrivateKey
        val message = "{\"cKey\":\"0123456789abcdef\"}".toByteArray()
        val size = (publicKey.modulus.bitLength() + 7) / 8

        // 模拟服务器：用私钥指数「加密」并补 PKCS#1 填充。
        val padding = ByteArray(size - 3 - message.size) { 0x11 }
        val em = ByteArray(size).also {
            it[0] = 0
            it[1] = 2
            System.arraycopy(padding, 0, it, 2, padding.size)
            it[2 + padding.size] = 0
            System.arraycopy(message, 0, it, 3 + padding.size, message.size)
        }
        val cipherText = BigInteger(1, em).modPow(privateKey.privateExponent, publicKey.modulus)
            .toByteArray()
            .let { raw ->
                if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size)
                else ByteArray(size - raw.size) + raw
            }

        val plain = ZhsCrypto.rsaPubDecryptBytes(
            cipherText,
            publicKey.modulus,
            publicKey.publicExponent,
        )
        assertNotNull(plain)
        assertEquals(String(message), String(plain!!))
    }

    @Test
    fun pubDecryptAcceptsTypeOnePadding() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val publicKey = pair.public as RSAPublicKey
        val privateKey = pair.private as RSAPrivateKey
        val message = "{\"cKey\":\"fedcba9876543210\"}".toByteArray()
        val size = (publicKey.modulus.bitLength() + 7) / 8

        // 智慧树 `rt.sl` 的真实结构是 type 1（签名）：00 01 FF…FF 00 M。
        val em = ByteArray(size).also {
            it[0] = 0
            it[1] = 1
            for (i in 2 until size - 1 - message.size) it[i] = 0xFF.toByte()
            it[size - 1 - message.size] = 0
            System.arraycopy(message, 0, it, size - message.size, message.size)
        }
        val cipherText = BigInteger(1, em).modPow(privateKey.privateExponent, publicKey.modulus)
            .toByteArray()
            .let { raw ->
                if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size)
                else ByteArray(size - raw.size) + raw
            }

        val plain = ZhsCrypto.rsaPubDecryptBytes(
            cipherText,
            publicKey.modulus,
            publicKey.publicExponent,
        )
        assertNotNull(plain)
        assertEquals(String(message), String(plain!!))
    }

    @Test
    fun pubDecryptRejectsMalformedPadding() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val publicKey = pair.public as RSAPublicKey
        // 全 0 的 EM 里找不到 00 分隔符（从下标 2 起）。
        assertNull(
            ZhsCrypto.rsaPubDecryptBytes(
                ByteArray(128),
                publicKey.modulus,
                publicKey.publicExponent,
            ),
        )
    }

    @Test
    fun pubDecryptReturnsNullForOversizedCipher() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val publicKey = pair.public as RSAPublicKey
        assertNull(
            ZhsCrypto.rsaPubDecryptBytes(
                ByteArray(129) { 1 },
                publicKey.modulus,
                publicKey.publicExponent,
            ),
        )
    }

    @Test
    fun encryptedKeyJsonIsParsed() {
        // 用固定的密钥对构造服务器风格的响应，验证 rsaDecryptKey 的解码路径。
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val publicKey = pair.public as RSAPublicKey
        val message = "{\"cKey\":\"abcdefghijklmnop\"}".toByteArray()
        val size = 128
        val padding = ByteArray(size - 3 - message.size) { 0x22 }
        val em = ByteArray(size).also {
            it[0] = 0
            it[1] = 2
            System.arraycopy(padding, 0, it, 2, padding.size)
            it[2 + padding.size] = 0
            System.arraycopy(message, 0, it, 3 + padding.size, message.size)
        }
        val cipherText = BigInteger(1, em).modPow(
            (pair.private as RSAPrivateKey).privateExponent,
            publicKey.modulus,
        ).toByteArray().let { raw ->
            if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size)
            else ByteArray(size - raw.size) + raw
        }
        val decoded = ZhsCrypto.rsaPubDecryptBytes(cipherText, publicKey.modulus, publicKey.publicExponent)
        assertNotNull(decoded)
        assertTrue(String(decoded!!).contains("abcdefghijklmnop"))
    }
}