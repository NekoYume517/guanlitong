package com.sdmu.guanlitong.data.pu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 上游 `Api.java` 的 BASE（§6.4）。 */
internal const val PU_BASE = "https://apis.pocketuni.net"

/** 连接 / 读取超时（§6.4，毫秒）。 */
internal const val PU_CONNECT_MS = 15000
internal const val PU_READ_MS = 25000

/** `collectMine` 一次拉取的页大小与最大页数（防止上游 `pageInfo` 异常导致死循环）。 */
private const val COLLECT_LIMIT = 100
private const val COLLECT_MAX_PAGES = 100

/**
 * 上游 `Api.java` 的移植。
 *
 * 关键约定（§2.4）：
 * - 登录失效是 **HTTP 200 + body `code:401`**，必须先解析 body 再判状态码；
 * - 业务错误是 `code != 0`，文案格式沿用上游 `"<label>接口异常: HTTP <status> code=<code> msg=<msg>"`；
 * - `pageInfo.count` 是记录数、`pageInfo.total` 是页数；
 * - 所有网络调用都在 [Dispatchers.IO] 上执行。
 *
 * 冻结签名里 [api] 没有 label 参数，所以 label 由 [labelOf] 按路径推导。
 */
class PuApi(
    private val token: String,
    private val goId: String,
) {

    suspend fun api(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val label = labelOf(path)
            val url = URL(if (path.startsWith("http://") || path.startsWith("https://")) path else PU_BASE + path)
            val conn = (url.openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = method.uppercase()
                conn.connectTimeout = PU_CONNECT_MS
                conn.readTimeout = PU_READ_MS
                conn.doInput = true
                conn.useCaches = false
                conn.setRequestProperty("Authorization", "Bearer $token:$goId")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", PuSign.userAgent())
                PuSign.xSign()?.takeIf { it.isNotBlank() }?.let {
                    conn.setRequestProperty("X-Sign", it)
                }
                if (body != null) {
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }

                val status = conn.responseCode
                val text = readAll(if (status >= 400) conn.errorStream else conn.inputStream)
                val json = if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()

                if (status == 401 || (json != null && json.optInt("code", 0) == 401)) {
                    throw PuException("登录已失效，请重新登录", 401)
                }
                if (json == null) {
                    throw PuException("${label}接口返回非 JSON: HTTP $status", 502)
                }
                if (status >= 400) {
                    val code = json.optInt("code", 0)
                    val msg = json.optString("message", json.optString("msg", ""))
                    throw PuException("${label}请求失败: HTTP $status code=$code msg=$msg", 502)
                }
                val code = json.optInt("code", 0)
                if (code != 0) {
                    val msg = json.optString("message", json.optString("msg", ""))
                    throw PuException("${label}接口异常: HTTP $status code=$code msg=$msg", 502)
                }
                return@withContext json.optJSONObject("data") ?: JSONObject()
            } catch (e: PuException) {
                throw e
            } catch (e: IOException) {
                throw PuException("网络错误: ${e.message ?: e.javaClass.simpleName}", 502)
            } finally {
                conn.disconnect()
            }
        }

    /** POST 列表接口 → [PuPage]。`body` 为 null 时按空 POST 处理。 */
    suspend fun fetch(path: String, body: JSONObject?): PuPage {
        val data = api("POST", path, body)
        return pageOf(data.optJSONArray("list"), data.optJSONObject("pageInfo"))
    }

    /** `/apis/activity/myList/new`：`type` 0 / 3 / 4 是唯一真实过滤器（§2.6）。 */
    suspend fun minePage(type: Int, page: Int, limit: Int): PuPage {
        val body = JSONObject()
            .put("page", page.coerceAtLeast(1))
            .put("limit", limit.coerceIn(1, 100))
            .put("type", type)
        return fetch("/apis/activity/myList/new", body)
    }

    /**
     * 把一个分类下的记录全部拉完（学时统计用）。
     *
     * 终止条件同上游：空页 / `已取条数 >= count` / `page >= totalPages`；
     * 另外加 100 页硬上限，防止上游 `pageInfo` 异常。
     */
    suspend fun collectMine(type: Int): PuPage {
        val all = ArrayList<PuActivity>()
        var page = 1
        var count = 0
        var totalPages = 1
        while (page <= COLLECT_MAX_PAGES) {
            val paged = minePage(type, page, COLLECT_LIMIT)
            all.addAll(paged.list)
            count = paged.count
            totalPages = paged.totalPages
            if (paged.list.isEmpty()) break
            if (count > 0 && all.size >= count) break
            if (page >= totalPages) break
            page++
        }
        return PuPage(
            list = all,
            count = maxOf(count, all.size),
            totalPages = maxOf(1, totalPages),
        )
    }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        return runCatching { stream.bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
    }
}

/** 路径 → 中文 label，用于错误文案；顺序敏感，长路径在前。 */
private fun labelOf(path: String): String {
    val p = path.substringBefore('?')
    return when {
        p.endsWith("/uc/user/login") -> "登录"
        p.endsWith("/uc/user/infocurr") -> "用户信息"
        p.endsWith("/apis/system/time") -> "时间"
        p.endsWith("/apis/activity/qrCode/time") -> "刷新"
        p.endsWith("/apis/activity/signIn/activity/code") -> "活动码签到"
        p.endsWith("/apis/activity/signInGps") -> "签到"
        p.endsWith("/apis/activity/signOutGps") -> "签退"
        p.endsWith("/apis/activity/sign/result") -> "签到状态"
        p.endsWith("/apis/activity/signIn") -> "扫码签到"
        p.endsWith("/apis/activity/canSignList") -> "可签到"
        p.endsWith("/apis/activity/myList/new") -> "我的活动"
        p.endsWith("/apis/activity/list") -> "列表"
        p.endsWith("/apis/activity/info") -> "详情"
        p.endsWith("/apis/activity/join") -> "报名"
        p.endsWith("/apis/activity/cancel") -> "取消报名"
        p.endsWith("/apis/personal/credit") -> "学时"
        p.startsWith("$PU_BASE/apis/other/index/") || p.startsWith("/apis/other/index/") -> "用户主页"
        p.contains("/apis/relations/follow/") -> "关注"
        p.contains("/apis/relations/unfollow/") -> "取消关注"
        p.contains("/apis/relations/") -> "好友列表"
        else -> "请求"
    }
}

/**
 * 脱敏：前 3 位 + `***` + 后 4 位；长度小于 8 时全部星号（§6.3）。
 *
 * 与上游 `Api.mask` 的差异：上游 `s.length() < 8` 时原样返回，冻结契约要求全星号，
 * 这里按契约实现（更保守，不会把短账号泄漏到 UI）。
 */
fun mask(value: String): String {
    val s = value.trim()
    if (s.isEmpty()) return ""
    if (s.length < 8) return "*".repeat(s.length)
    return s.take(3) + "***" + s.takeLast(4)
}