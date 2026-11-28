package com.sdmu.guanlitong.data.zhs

import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 智慧树的登录与接口加密，逐项对照 Python 参考实现（D:\code\zhihuishu\zhs_server.py）：
 *
 * - 登录：`loginEncrypt` = Base64(encodeURI(JSON))，明文结构
 *   `{"account":..,"password":..,"validate":..}`（顺序固定）；
 * - 课程接口：服务器下发 AES 密钥（`c/has` 的 `rt.sl` 用公钥「解密」得到 `cKey`），
 *   业务参数用 AES/CBC/PKCS7 加密后 Base64，IV 固定；
 * - 公钥交换：`{"module":13}` 用 RSA PKCS#1 v1.5 加密后 Base64，与 python-rsa、
 *   JSEncrypt 的密文一致。
 *
 * 全部是纯 JVM 实现，设备上不依赖 WebView，单测里可以直接跑。
 */
internal object ZhsCrypto {

    /** CryptoJS.enc.Utf8.parse("1g3qqdh4jvbskb9x")。 */
    private const val AES_IV = "1g3qqdh4jvbskb9x"

    /**
     * passport / base2 前端内置的 1024 位公钥（PEM 去头尾后的 DER Base64）。
     * 请求参数用它的公开指数加密；交换密钥的响应由服务器用对应私钥指数生成，
     * 客户端再按公开指数还原（参考实现 `rsa_pub_decrypt` 即 pow(c, e, n)）。
     */
    internal const val RSA_PUBLIC_KEY_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCgfZmpLpPEpEFRKBe+ZjWJUjPe+7qg7pGqcfN3j2eg" +
            "J8H2mrKwaEqZEnPnpi2O3hN8HRyaFozDOp8gwZiYfiIZjWy0Jr/FNAiiKYh5bq0GsEn+ieMmRyJg/+i" +
            "1rqizhvCXvFdrdGhFTw5EBwTpsGdwe1utdlrvIJUAFWj9Yh4qbQIDAQAB"

    private val ENCODER: Base64.Encoder = Base64.getEncoder()

    /**
     * 与 JS `encodeURI` 保持一致：不转义字母数字和
     * `;,/?:@&=+$-_.!~*'()#`，其余按 UTF-8 字节转义。
     */
    private const val URI_SAFE = ";,/?:@&=+$-_.!~*'()#"

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun encodeUri(input: String): String {
        val sb = StringBuilder(input.length * 3)
        for (b in input.toByteArray(Charsets.UTF_8)) {
            val code = b.toInt() and 0xFF
            if (isUriSafe(code)) {
                sb.append(code.toChar())
            } else {
                sb.append('%')
                sb.append(HEX[code shr 4])
                sb.append(HEX[code and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun isUriSafe(code: Int): Boolean =
        code in 0x30..0x39 || // 0-9
            code in 0x41..0x5A || // A-Z
            code in 0x61..0x7A || // a-z
            URI_SAFE.indexOf(code.toChar()) >= 0

    /** `{"account":"…","password":"…","validate":"…"}`，字段顺序与参考实现一致。 */
    fun loginPayload(account: String, password: String, validate: String): String =
        buildString {
            append('{')
            append("\"account\":").append(jsonString(account)).append(',')
            append("\"password\":").append(jsonString(password)).append(',')
            append("\"validate\":").append(jsonString(validate))
            append('}')
        }

    fun loginEncrypt(account: String, password: String, validate: String): String =
        ENCODER.encodeToString(encodeUri(loginPayload(account, password, validate)).toByteArray(Charsets.UTF_8))

    /** CryptoJS aesUtil.encrypt 的等价实现：AES-128-CBC + PKCS7，输出带填充的 Base64。 */
    fun aesEncrypt(json: String, key: String): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8).copyOf(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8)),
        )
        return ENCODER.encodeToString(cipher.doFinal(json.toByteArray(Charsets.UTF_8)))
    }

    /** `/c/has` 的请求参数：RSA 加密 `{"module":13}` 后 Base64。 */
    fun rsaEncryptForExchange(module: Int = 13): String {
        val key = publicKey()
        val msg = "{\"module\":$module}".toByteArray(Charsets.UTF_8)
        return ENCODER.encodeToString(
            rsaEncryptBytes(msg, key.modulus, key.publicExponent)
        )
    }

    /** 把 `rt.sl` 用公钥指数「解密」，得到服务器下发的 `{"cKey":"…"}`。 */
    fun rsaDecryptKey(sl: String): String? {
        val key = publicKey()
        val plain = rsaPubDecryptBytes(Base64.getDecoder().decode(sl), key.modulus, key.publicExponent)
            ?: return null
        return runCatching { JSONObject(String(plain, Charsets.UTF_8)).optString("cKey") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun publicKey(): RSAPublicKey = KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(RSA_PUBLIC_KEY_B64))) as RSAPublicKey

    /**
     * RSA PKCS#1 v1.5 加密（EM = 00 02 PS 00 M）。与 python-rsa / JSEncrypt 的输出格式一致，
     * 但由我们自己拼填充，Android 与 JVM 单测行为完全相同。
     */
    internal fun rsaEncryptBytes(
        msg: ByteArray,
        modulus: BigInteger,
        exponent: BigInteger,
        random: Random = SecureRandom(),
    ): ByteArray {
        val k = (modulus.bitLength() + 7) / 8
        require(k >= 11 && msg.size <= k - 11) { "RSA 明文过长" }
        val ps = ByteArray(k - 3 - msg.size)
        for (i in ps.indices) {
            var byte: Int
            do {
                byte = random.nextInt(256)
            } while (byte == 0)
            ps[i] = byte.toByte()
        }
        val em = ByteArray(k)
        em[0] = 0
        em[1] = 2
        System.arraycopy(ps, 0, em, 2, ps.size)
        em[2 + ps.size] = 0
        System.arraycopy(msg, 0, em, 3 + ps.size, msg.size)
        val cipher = BigInteger(1, em).modPow(exponent, modulus)
        return toFixedLength(cipher, k)
    }

    /**
     * 用公开指数还原服务器签名式的密文，并去掉 PKCS#1 填充。
     *
     * 智慧树 `c/has` 的 `rt.sl` 用的是 type 1（签名）填充：
     * `00 01 FF…FF 00 <明文>`，而不是加密常用的 type 2（`00 02 随机非零 00`）。
     * 与 Python 参考实现一致：`00` 开头后从 index 2 起找第一个 0x00，
     * 取其后全部字节；两种块类型都接受。找不到合法分隔时返回 null
     * （接口会当成密钥交换失败）。
     */
    internal fun rsaPubDecryptBytes(
        cipher: ByteArray,
        modulus: BigInteger,
        exponent: BigInteger,
    ): ByteArray? {
        val k = (modulus.bitLength() + 7) / 8
        if (cipher.size > k) return null
        val raw = toFixedLength(BigInteger(1, cipher).modPow(exponent, modulus), k)
        if (raw.size < 4 || raw[0] != 0.toByte()) return null
        if (raw[1] != 1.toByte() && raw[1] != 2.toByte()) return null
        var index = -1
        for (i in 2 until raw.size) {
            if (raw[i] == 0.toByte()) {
                index = i
                break
            }
        }
        if (index <= 1) return null
        return raw.copyOfRange(index + 1, raw.size)
    }

    private fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == length -> raw
            raw.size > length -> raw.copyOfRange(raw.size - length, raw.size)
            else -> ByteArray(length - raw.size) + raw
        }
    }

    /** 最小 JSON 字符串转义；账号密码里的引号、反斜杠不能破坏 `secretStr` 结构。 */
    internal fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    /** 仅供单测使用：确认公钥常量的 DER 能被 KeyFactory 解析。 */
    internal fun publicKeyModulus(): BigInteger = publicKey().modulus
}