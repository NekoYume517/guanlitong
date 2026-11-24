package com.sdmu.guanlitong.data.sdgy

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 山管 App 的 SecretUtil 同款加密：
 * AES/ECB/PKCS5Padding 传业务数据，RSA/ECB/PKCS1Padding 传握手密钥（1024 位，117 字节分块）。
 */
internal object SdgyCrypto {

    private val random = SecureRandom()

    fun newAesKey(): String {
        val key = ByteArray(16)
        random.nextBytes(key)
        return encode(key)
    }

    fun encode(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    fun decode(text: String): ByteArray = Base64.decode(text, Base64.DEFAULT)

    fun aesEncrypt(plain: String, keyB64: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(decode(keyB64), "AES"))
        return encode(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun aesDecrypt(encoded: String, keyB64: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decode(keyB64), "AES"))
        return String(cipher.doFinal(decode(encoded)), Charsets.UTF_8)
    }

    fun rsaEncryptBlocks(plain: ByteArray, publicKeyB64: String): String {
        val key: PublicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(decode(publicKeyB64)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < plain.size) {
            val size = minOf(117, plain.size - offset)
            out.write(cipher.doFinal(plain, offset, size))
            offset += size
        }
        return encode(out.toByteArray())
    }
}