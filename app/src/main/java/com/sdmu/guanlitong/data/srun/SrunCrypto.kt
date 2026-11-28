package com.sdmu.guanlitong.data.srun

import java.security.MessageDigest

/**
 * 校园网（深澜 srun）认证里所有纯算法。
 *
 * 逐行对照 Go 版 `srun-kit/go/srun-go.go` 的 `getByte / getBase64 / sencode /
 * lencode / getXencode / md5Hex / sha1Hex` 移植：
 * - 自定义 base64 字母表是门户约定的，不能用标准 Base64；
 * - `hmd5` 就是 `md5(明文密码)`，不是新版 `HMAC-MD5(challenge, password)`；
 * - `info` 是紧凑 JSON，键序 username/password/ip/acid/enc_ver，少一个、多一个
 *   或换序都会被 AC 判成 `auth_info_error`。
 *
 * 这里不碰网络与 Android，全部可在 JVM 单测里跑。
 */
object SrunCrypto {

    /** 门户约定的 base64 字母表（与标准表不同，不能换）。 */
    const val ALPHABET = "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"

    const val ENC_VERSION = "srun_bx1"

    /** 门户要求 14 个参数里的 os/name 原样写 windows，不是口误。 */
    const val DEFAULT_OS = "windows+10"
    const val DEFAULT_NAME = "windows"

    private fun getByte(bytes: ByteArray, index: Int): Int =
        if (index >= bytes.size) 0 else bytes[index].toInt() and 0xFF

    /** 自定义 base64；输入按字节处理，与 Go 里对字符串逐字节取值一致。 */
    fun base64(input: ByteArray): String {
        val length = input.size
        if (length == 0) return ""
        val out = StringBuilder()
        val imax = length - length % 3
        var i = 0
        while (i < imax) {
            val b10 = (getByte(input, i) shl 16) or
                (getByte(input, i + 1) shl 8) or
                getByte(input, i + 2)
            out.append(ALPHABET[(b10 shr 18) and 63])
            out.append(ALPHABET[(b10 shr 12) and 63])
            out.append(ALPHABET[(b10 shr 6) and 63])
            out.append(ALPHABET[b10 and 63])
            i += 3
        }
        when (length % 3) {
            1 -> {
                val b10 = getByte(input, imax) shl 16
                out.append(ALPHABET[(b10 shr 18) and 63])
                out.append(ALPHABET[(b10 shr 12) and 63])
                out.append("==")
            }

            2 -> {
                val b10 = (getByte(input, imax) shl 16) or (getByte(input, imax + 1) shl 8)
                out.append(ALPHABET[(b10 shr 18) and 63])
                out.append(ALPHABET[(b10 shr 12) and 63])
                out.append(ALPHABET[(b10 shr 6) and 63])
                out.append("=")
            }
        }
        return out.toString()
    }

    /** 把字符串按 UTF-8 字节打包成 32 位小端字；[withLength] 时末尾补原始字节数。 */
    fun sencode(msg: String, withLength: Boolean): LongArray {
        val bytes = msg.toByteArray(Charsets.UTF_8)
        val words = ArrayList<Long>(bytes.size / 4 + 2)
        var i = 0
        while (i < bytes.size) {
            words += (
                getByte(bytes, i).toLong() or
                    (getByte(bytes, i + 1).toLong() shl 8) or
                    (getByte(bytes, i + 2).toLong() shl 16) or
                    (getByte(bytes, i + 3).toLong() shl 24)
                )
            i += 4
        }
        if (withLength) words += bytes.size.toLong()
        return words.toLongArray()
    }

    /** 把 32 位字按小端拆回字节。 */
    fun lencode(words: LongArray): ByteArray {
        val out = ByteArray(words.size * 4)
        words.forEachIndexed { index, word ->
            out[index * 4] = (word and 0xFF).toByte()
            out[index * 4 + 1] = ((word shr 8) and 0xFF).toByte()
            out[index * 4 + 2] = ((word shr 16) and 0xFF).toByte()
            out[index * 4 + 3] = ((word shr 24) and 0xFF).toByte()
        }
        return out
    }

    /**
     * TEA 变种加密（XXTEA 的深澜定制版），结果按原始字节返回，
     * 因为后面 [base64] 是按字节编码的。空字符串返回空字节。
     */
    fun xencode(msg: String, key: String): ByteArray {
        if (msg.isEmpty()) return ByteArray(0)
        val pwd = sencode(msg, withLength = true)
        val keyWords = sencode(key, withLength = false)
        val pwdk = if (keyWords.size >= 4) keyWords else keyWords.copyOf(4)

        val n = pwd.size - 1
        var z = pwd[n]
        var y = pwd[0]
        val c = 0x86014019L or 0x183639A0L
        var d = 0L
        var q = Math.floor(6 + 52.0 / (n + 1)).toInt().toLong()
        while (q > 0) {
            d = (d + c) and 0xFFFFFFFFL
            val e = (d shr 2) and 3
            var p = 0
            while (p < n) {
                y = pwd[p + 1]
                var m = (z shr 5) xor (y shl 2)
                m += ((y shr 3) xor (z shl 4)) xor (d xor y)
                m += pwdk[((p and 3) xor e.toInt())] xor z
                pwd[p] = (pwd[p] + m) and 0xFFFFFFFFL
                z = pwd[p]
                p++
            }
            y = pwd[0]
            var m = (z shr 5) xor (y shl 2)
            m += ((y shr 3) xor (z shl 4)) xor (d xor y)
            m += pwdk[((n and 3) xor e.toInt())] xor z
            pwd[n] = (pwd[n] + m) and 0xFFFFFFFFL
            z = pwd[n]
            q--
        }
        return lencode(pwd)
    }

    fun md5Hex(text: String): String = digestHex("MD5", text)

    fun sha1Hex(text: String): String = digestHex("SHA-1", text)

    private fun digestHex(algorithm: String, text: String): String {
        val bytes = MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val b = byte.toInt() and 0xFF
            out.append(LOWER_HEX[(b shr 4) and 0xF]).append(LOWER_HEX[b and 0xF])
        }
        return out.toString()
    }

    private fun hex4(value: Int): String = buildString(4) {
        append(LOWER_HEX[(value shr 12) and 0xF])
        append(LOWER_HEX[(value shr 8) and 0xF])
        append(LOWER_HEX[(value shr 4) and 0xF])
        append(LOWER_HEX[value and 0xF])
    }

    /**
     * 发给 AC 的 info 明文。
     *
     * 键序与字段是协议的一部分，另外 Go 的 `json.Marshal` 默认会转义
     * `<`/`>`/`&` 和 U+2028/U+2029，这里照做，密码里带这些字符时结果才一致。
     */
    fun infoJson(
        username: String,
        password: String,
        ip: String,
        acId: String,
        encVersion: String = ENC_VERSION,
    ): String = buildString {
        append("{\"username\":").append(jsonString(username))
        append(",\"password\":").append(jsonString(password))
        append(",\"ip\":").append(jsonString(ip))
        append(",\"acid\":").append(jsonString(acId))
        append(",\"enc_ver\":").append(jsonString(encVersion))
        append('}')
    }

    /** `{SRBX1}` + base64(xencode(info, challenge))。 */
    fun encodeInfo(
        username: String,
        password: String,
        ip: String,
        acId: String,
        challenge: String,
        encVersion: String = ENC_VERSION,
    ): String = "{SRBX1}" + base64(
        xencode(infoJson(username, password, ip, acId, encVersion), challenge),
    )

    /** chksum = sha1(token + user + token + hmd5 + … + info)，token 每段都夹一次。 */
    fun chksum(
        token: String,
        username: String,
        hmd5: String,
        acId: String,
        ip: String,
        n: String,
        type: String,
        info: String,
    ): String = sha1Hex(
        token + username + token + hmd5 + token + acId + token + ip +
            token + n + token + type + token + info,
    )

    /** 门户回调名：`jQuery11240645308969735664_<毫秒时间戳>`。 */
    fun jsonpCallback(nowMs: Long): String = "jQuery11240645308969735664_$nowMs"

    /**
     * 按 Go `url.Values.Encode()` 的规则拼接查询串：键名字典序、空格转 `+`、
     * 除 `A-Za-z0-9-_.~` 外一律 UTF-8 百分号编码（大写十六进制）。
     */
    fun encodeQuery(params: List<Pair<String, String>>): String =
        params.sortedBy { it.first }
            .joinToString("&") { (key, value) -> queryEscape(key) + "=" + queryEscape(value) }

    fun queryEscape(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        bytes.forEach { raw ->
            val b = raw.toInt() and 0xFF
            when {
                b == ' '.code -> out.append('+')
                isUnreserved(b) -> out.append(b.toChar())
                else -> out.append('%').append(HEX[(b shr 4) and 0xF]).append(HEX[b and 0xF])
            }
        }
        return out.toString()
    }

    private fun isUnreserved(byte: Int): Boolean {
        val c = byte.toChar()
        return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c == '-' || c == '_' || c == '.' || c == '~'
    }

    /** 百分号编码用大写（Go 的 QueryEscape）；摘要和 `\u` 转义用小写（Go 的 hex.EncodeToString）。 */
    private const val HEX = "0123456789ABCDEF"
    private const val LOWER_HEX = "0123456789abcdef"

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(hex4(ch.code))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }
}