package com.sdmu.guanlitong.data.sdgy

import java.net.InetAddress
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一卡通财务系统（zf.cwc.sdmu.edu.cn）纯原生客户端。
 *
 * 逆向要点（2026-10-22 实测确认）：
 *  - 签名：Sign = md5(参数值1|参数值2|...|"ok15we1@oid8x5afd@")
 *  - 学号→卡号：POST /h5/QueryAccInfoH.aspx  IDNo=学号&IDType=4  → AccNum
 *  - 余额：POST /h5/QueryAccWallet.aspx     AccNum=<卡号>        → Rows[].WalletRows[].MonDBCurr
 *  - 付款码：POST /h5/GetQRCode.aspx        AccNum=<卡号>        → QRCode="<176hex>,<分>"
 *  - 轮询：POST /h5/GetQRCodeInfo.aspx      QRCode=<完整QRCode>  → Recflag/MonDeal/DealerName/DealTime
 *  全部为表单 POST，无需 cookie/登录态，学号与卡号即可直连。
 */
object SdgyPayApi {

    const val BASE = "https://zf.cwc.sdmu.edu.cn/h5"
    private const val KEY = "ok15we1@oid8x5afd@"

    private val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    /** 信任所有证书（校方站点证书主机名不匹配，与官方 App 行为一致） */
    private val TRUST_ALL_FACTORY: javax.net.ssl.SSLSocketFactory by lazy {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                emptyArray()
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom())
        ctx.socketFactory
    }

    private fun now(): String = fmt.format(Date())

    /**
     * 取服务器时间（用于 Time 字段，避免手机时钟偏差导致"签名验证失败"）。
     * 服务端对 Time 有容差但手机时钟可能偏差较大；失败则回退本地时间。
     * 结果缓存 30 秒，避免每次请求都多打一次。
     */
    @Volatile private var srvOffsetMs: Long = 0
    @Volatile private var srvSyncAt: Long = 0

    private fun syncServerTime() {
        val t0 = System.currentTimeMillis()
        try {
            val conn = (java.net.URL(BASE + "/index.html").openConnection()
                    as java.net.HttpURLConnection)
            if (conn is javax.net.ssl.HttpsURLConnection) {
                conn.sslSocketFactory = TRUST_ALL_FACTORY
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            val rtt = (System.currentTimeMillis() - t0) / 2
            conn.inputStream.use { it.readBytes() }
            val dateStr = conn.getHeaderField("Date")
            if (!dateStr.isNullOrBlank()) {
                val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("GMT")
                val serverMs = sdf.parse(dateStr)?.time
                if (serverMs != null) {
                    srvOffsetMs = serverMs - System.currentTimeMillis() + rtt
                    srvSyncAt = System.currentTimeMillis()
                }
            }
            conn.disconnect()
        } catch (_: Exception) { /* 回退本地时间 */ }
    }

    /** 当前时间（优先校准到服务器） */
    private fun nowCalibrated(): String {
        if (System.currentTimeMillis() - srvSyncAt > 30_000L) syncServerTime()
        return fmt.format(Date(System.currentTimeMillis() + srvOffsetMs))
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /** Sign = md5(a|b|...|KEY) */
    private fun sign(vararg parts: String): String =
        md5((parts.toList() + KEY).joinToString("|"))

    // ---------- 最小 JSON 解析（分包无 JSON 库依赖） ----------

    /** 取顶层字符串/数字字段：{"Code":"1","AccNum":"613993"} */
    fun field(json: String, name: String): String? {
        val needle = "\"$name\""
        var i = json.indexOf(needle)
        if (i < 0) return null
        i = json.indexOf(':', i + needle.length)
        if (i < 0) return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        return if (json[i] == '"') {
            val end = json.indexOf('"', i + 1)
            if (end < 0) null else json.substring(i + 1, end)
        } else {
            var end = i
            while (end < json.length && json[end] !in ",}\n\r") end++
            json.substring(i, end).trim()
        }
    }

    /** 取所有名为 name 的字段值（用于 WalletRows 里多个 MonDBCurr） */
    fun allFields(json: String, name: String): List<String> {
        val out = mutableListOf<String>()
        val needle = "\"$name\""
        var from = 0
        while (true) {
            val i = json.indexOf(needle, from)
            if (i < 0) break
            var j = json.indexOf(':', i + needle.length)
            if (j < 0) break
            j++
            while (j < json.length && json[j].isWhitespace()) j++
            if (j < json.length) {
                if (json[j] == '"') {
                    val end = json.indexOf('"', j + 1)
                    if (end > 0) { out.add(json.substring(j + 1, end)); from = end + 1 }
                    else break
                } else {
                    var end = j
                    while (end < json.length && json[end] !in ",}\n\r") end++
                    out.add(json.substring(j, end).trim()); from = end
                }
            } else break
        }
        return out
    }

    // ---------- HTTP ----------

    /**
     * 建连。注意：不能用「IP 直连 + Host 头」——HttpsURLConnection 的 SNI/证书校验
     * 都基于 URL 的 host，改 IP 会破坏 TLS。改用 JVM 地址族偏好（preferIpv4）。
     */
    private fun openConn(path: String): java.net.HttpURLConnection =
        java.net.URL(BASE + path).openConnection() as java.net.HttpURLConnection

    /** 优先 IPv4：部分校园网/移动网到 zf.cwc 的 IPv6 路由不通 → 连接超时（表现为"无响应"） */
    fun preferIpv4() {
        runCatching {
            System.setProperty("java.net.preferIPv4Stack", "true")
            System.setProperty("java.net.preferIPv6Addresses", "false")
            // 已知 IPv6 不通时直接排除，不依赖系统路由探测
            System.setProperty("java.net.preferIPv4Addresses", "true")
        }
    }

    private fun post(path: String, params: Map<String, String>, timeoutMs: Int = 20000): String? {
        val conn = openConn(path)
        return try {
            // zf.cwc 的证书主机名不匹配（官方 App/宿主 WebView 均 trust-all）
            if (conn is javax.net.ssl.HttpsURLConnection) {
                conn.sslSocketFactory = TRUST_ALL_FACTORY
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            val body = params.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                        java.net.URLEncoder.encode(it.value, "UTF-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            lastHttp = "$path HTTP$code len=${text?.length ?: 0} " +
                    (text?.take(70)?.replace("\n", " ") ?: "")
            android.util.Log.d("PayApi", lastHttp)
            text
        } catch (e: Exception) {
            lastHttp = "$path ${e.javaClass.simpleName}: ${e.message?.take(70)}"
            android.util.Log.w("PayApi", lastHttp)
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 最近一次 HTTP 结果（诊断用，显示在失败提示里） */
    @Volatile
    var lastHttp: String = ""

    // ---------- 业务 ----------

    /** 钱包余额（元）：主钱包 + 其他钱包，返回 Pair(主钱包, 全部钱包列表文本) */
    data class Wallet(val main: String, val others: List<Pair<String, String>>)

    /**
     * 学号 → (卡号, 姓名)。QueryAccInfoH 同时返回 AccNum 与 AccName，
     * 一次请求拿到两者，不额外增加网络开销。失败返回 (null, null)。
     */
    fun cardInfo(studentId: String): Pair<String?, String?> {
        // A：明文 IDNo（实测最稳）
        run {
            val t = nowCalibrated()
            val json = post("/QueryAccInfoH.aspx", mapOf(
                "IDNo" to studentId, "IDType" to "4",
                "Time" to t, "Sign" to sign(studentId, "4", t), "ContentType" to "json"))
            if (json != null && field(json, "Code") == "1") {
                val acc = field(json, "AccNum")
                if (!acc.isNullOrBlank())
                    return acc to normName(field(json, "AccName"))
            }
        }
        // B：三层 Base64（H5 的 URL 参数同构）
        val b64Id = b64x3(studentId)
        val b64Type = b64x3("4")
        run {
            val t = nowCalibrated()
            val json = post("/QueryAccInfoH.aspx", mapOf(
                "IDNo" to b64Id, "IDType" to b64Type,
                "Time" to t, "Sign" to sign(b64Id, b64Type, t), "ContentType" to "json"))
            if (json != null && field(json, "Code") == "1") {
                val acc = field(json, "AccNum")
                if (!acc.isNullOrBlank())
                    return acc to normName(field(json, "AccName"))
            }
        }
        return null to null
    }

    /** 姓名归一：接口缺失时返回 null 而不是字符串 "null" */
    private fun normName(v: String?): String? =
        v?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }

    /**
     * 学号 → 卡号。失败返回 null 并写入 lastDebug。
     *
     * 依次尝试（不同批次/不同账号在服务端表现不一致，实测 QueryAccInfoH 明文可用）：
     *  A. QueryAccInfoH  IDNo=原样  IDType=4
     *  B. QueryAccInfoH  IDNo=base64x3(学号)  IDType=base64x3(4)   ← 与 H5 的 URL 参数同构
     *  C. LogIn          IDNo=base64x3(学号)  IDType=base64x3(4)   ← H5 首次进页面走的接口
     */
    @Volatile
    var lastDebug: String = ""

    fun cardNo(studentId: String): String? {
        val log = StringBuilder()
        val tried = mutableListOf<String>()

        // A
        run {
            val t = nowCalibrated()
            val json = post("/QueryAccInfoH.aspx", mapOf(
                "IDNo" to studentId, "IDType" to "4",
                "Time" to t, "Sign" to sign(studentId, "4", t), "ContentType" to "json"))
            if (json == null) log.append("A=NO_RESP [${lastHttp}] ")
            val code = json?.let { field(it, "Code") }
            val msg = json?.let { field(it, "Msg") }
            val acc = json?.let { field(it, "AccNum") }
            log.append("A=${code}/${msg} [${lastHttp}] ")
            tried += "A:$code"
            if (code == "1" && !acc.isNullOrBlank()) { lastDebug = log.toString(); return acc }
        }

        // B
        val b64Id = b64x3(studentId)
        val b64Type = b64x3("4")
        run {
            val t = nowCalibrated()
            val json = post("/QueryAccInfoH.aspx", mapOf(
                "IDNo" to b64Id, "IDType" to b64Type,
                "Time" to t, "Sign" to sign(b64Id, b64Type, t), "ContentType" to "json"))
            if (json == null) log.append("B=NO_RESP [${lastHttp}] ")
            val code = json?.let { field(it, "Code") }
            val msg = json?.let { field(it, "Msg") }
            val acc = json?.let { field(it, "AccNum") }
            log.append("B=${code}/${msg} [${lastHttp}] ")
            tried += "B:$code"
            if (code == "1" && !acc.isNullOrBlank()) { lastDebug = log.toString(); return acc }
        }

        // C
        run {
            val t = nowCalibrated()
            val json = post("/LogIn.aspx", mapOf(
                "IDNo" to b64Id, "IDType" to b64Type,
                "Time" to t, "Sign" to sign(b64Id, b64Type, t), "ContentType" to "json"))
            if (json == null) log.append("C=NO_RESP [${lastHttp}] ")
            val code = json?.let { field(it, "Code") }
            val msg = json?.let { field(it, "Msg") }
            val acc = json?.let { field(it, "AccNum") }
            log.append("C=${code}/${msg} [${lastHttp}] ")
            if (code == "1" && !acc.isNullOrBlank()) { lastDebug = log.toString(); return acc }
        }

        lastDebug = "学号=$studentId  结果: $log"
        return null
    }

    /** 与 H5 的 URL 参数同构：三层 Base64 */
    private fun b64x3(v: String): String {
        var s = v
        repeat(3) {
            s = android.util.Base64.encodeToString(
                s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }
        return s
    }

    /** 查余额 */
    fun wallet(accNum: String): Wallet? {
        val t = nowCalibrated()
        val json = post("/QueryAccWallet.aspx", mapOf(
            "AccNum" to accNum, "Time" to t,
            "Sign" to sign(accNum, t), "ContentType" to "json",
        )) ?: return null
        if (field(json, "Code") != "1") return null
        val names = allFields(json, "WalletName")
        val moneys = allFields(json, "MonDBCurr")
        val list = names.mapIndexed { i, n -> n to (moneys.getOrNull(i) ?: "0.00") }
        val main = list.firstOrNull { it.first.contains("主") }?.second
            ?: list.firstOrNull()?.second ?: "0.00"
        return Wallet(main, list)
    }

    /** 取付款码原文（形如 "<176hex>,<分>"），失败 null */
    fun qrCode(accNum: String): String? {
        val t = nowCalibrated()
        val json = post("/GetQRCode.aspx", mapOf(
            "AccNum" to accNum, "Time" to t,
            "Sign" to sign(accNum, t), "ContentType" to "json",
        )) ?: return null
        if (field(json, "Code") != "1") return null
        return field(json, "QRCode")?.takeIf { it.isNotBlank() }
    }

    /** 轮询结果：Recflag=0 未支付；有 MonDeal>0 即已支付 */
    data class PayState(
        val recflag: String,      // "0"=未消费
        val amount: Double,       // MonDeal 消费金额
        val dealer: String,       // DealerName 商户
        val dealTime: String,     // DealTime 交易时间
        val accName: String,
    ) {
        val paid: Boolean get() = recflag != "0" || amount > 0.0 || dealTime.isNotBlank()
    }

    /** 轮询是否已付款（用完整 QRCode 原文签名，注意不是卡号） */
    fun poll(qr: String): PayState? {
        val t = nowCalibrated()
        val json = post("/GetQRCodeInfo.aspx", mapOf(
            "QRCode" to qr, "Time" to t,
            "Sign" to sign(qr, t), "ContentType" to "json",
        ), timeoutMs = 8000) ?: return null
        if (field(json, "Code") != "1") return null
        return PayState(
            recflag = field(json, "Recflag") ?: "0",
            amount = field(json, "MonDeal")?.toDoubleOrNull() ?: 0.0,
            dealer = field(json, "DealerName").orEmpty(),
            dealTime = field(json, "DealTime").orEmpty(),
            accName = field(json, "AccName").orEmpty(),
        )
    }

    /** 二维码里的预设金额（分 → 元展示） */
    fun amountText(qr: String): String {
        val cents = qr.substringAfterLast(',', "").toLongOrNull() ?: return ""
        return if (cents <= 0) "不限" else String.format(Locale.US, "%.2f", cents / 100.0)
    }
}
