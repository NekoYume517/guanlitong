package com.sdmu.guanlitong.data.pu

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 个人二维码（§2.3）。
 *
 * 明文 = `xyhui://user/{uid}/{服务器秒级时间戳}/{username}`，
 * 用 `DES/ECB/PKCS5Padding` + 8 字节 key `TG123!@#` 加密，Base64（无换行）输出。
 *
 * 实现说明：
 * - 密钥常量官方是 `TG123!@#qazDES*&^956367encode7788TR`，但 DES 只用前 8 字节，
 *   所以这里直接写前 8 字节，与上游密文一致；
 * - 用 `java.util.Base64`（minSdk 26 可用），不用 `android.util.Base64`，
 *   这样 JVM 单元测试不需要 Robolectric 也能跑；
 * - `Base64.getEncoder()` 不折行，等价于上游的 `NO_WRAP`。
 */
object PuPersonalCodec {

    const val DES_KEY = "TG123!@#"

    private const val PLAIN_PREFIX = "xyhui://user/"

    /** 明文串；长度取决于 username：明文 48~55 字节时密文恰好 76 字符。 */
    fun plain(uid: String, username: String, tsSeconds: Long): String =
        "$PLAIN_PREFIX$uid/$tsSeconds/$username"

    fun encode(uid: String, username: String, tsSeconds: Long): String {
        val plain = plain(uid, username, tsSeconds)
        val key = SecretKeySpec(DES_KEY.take(8).toByteArray(Charsets.UTF_8), "DES")
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    /** 解密并校验前缀；任何非法输入返回 `""`（不抛异常）。 */
    internal fun decode(b64: String): String {
        val s = b64.trim().replace(Regex("\\s+"), "")
        if (s.length < 8 || s.length % 4 != 0) return ""
        if (!s.matches(Regex("[A-Za-z0-9+/=]+"))) return ""
        return runCatching {
            val raw = Base64.getDecoder().decode(s)
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            val key = SecretKeySpec(DES_KEY.take(8).toByteArray(Charsets.UTF_8), "DES")
            cipher.init(Cipher.DECRYPT_MODE, key)
            val plain = String(cipher.doFinal(raw), Charsets.UTF_8)
            if (plain.startsWith(PLAIN_PREFIX)) plain else ""
        }.getOrDefault("")
    }
}