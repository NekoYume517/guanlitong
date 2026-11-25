package com.sdmu.guanlitong.data.jwxt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId

/**
 * 原生教务系统客户端（课表）。
 *
 * 登录仍然走 WebView：调用方把 `CookieManager.getCookie(...)` 读到的 Cookie 传进来，
 * 这里直接带着它请求教务系统 kbcx 接口。接口路径与参数参考 sdmu-website-2 的
 * `app/kbcx_api.py`，这样原生侧和网页侧看到的是同一份登录态、同一套数据。
 */
class JwxtClient(
    serviceUrl: String,
    private val cookie: String,
) {

    val baseUrl: String = baseOf(serviceUrl)

    private val indexUrl: String =
        "$baseUrl/kbcx/xskbcxZccx_cxXskbcxIndex.html?gnmkdm=N2154&layout=default"

    private val weekListUrl: String = "$baseUrl/kbcx/xskbcxZccx_cxZcByXnxq.html"
    private val timetableWebUrl: String = "$baseUrl/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"
    private val timetableMobileUrl: String = "$baseUrl/kbcx/xskbcxMobile_cxXsKb.html"
    private val jcTimesWebUrl: String = "$baseUrl/kbcx/xskbcx_cxRjc.html?gnmkdm=N2151"
    private val jcTimesMobileUrl: String = "$baseUrl/jzgl/skxxMobile_cxRsdjc.html"

    suspend fun fetch(): Timetable = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw JwxtException("尚未配置教务系统地址")
        if (cookie.isBlank()) throw JwxtSessionExpiredException("尚未登录教务系统，请先在网页中登录")

        val indexHtml = request(indexUrl, "GET", null)
        if (!indexHtml.contains("xskbcxZccx", ignoreCase = true) &&
            !indexHtml.contains("xskbcx_cxXsgrkb", ignoreCase = true)
        ) {
            if (indexHtml.contains("login_slogin", ignoreCase = true)) {
                throw JwxtSessionExpiredException()
            }
            throw JwxtException("教务系统没有返回课表页面，请稍后重试")
        }

        val meta = parseMeta(indexHtml)
        if (meta.sfbczc == "0") {
            throw JwxtException("账号未完成缴费注册，暂时无法查询课表")
        }

        val weeks = runCatching { fetchWeeks(meta.xnm, meta.xqm) }.getOrDefault(emptyList())
        val root = fetchTimetable(meta.xnm, meta.xqm)
        val courses = parseKbcxCourses(root)

        val xqhId = meta.xqhId.ifBlank {
            root.optJSONArray("kbList")
                ?.optJSONObject(0)
                ?.optStringAny("xqh_id")
                .orEmpty()
        }
        val jcTimes = if (xqhId.isBlank()) {
            emptyMap()
        } else {
            runCatching { fetchJcTimes(meta.xnm, meta.xqm, xqhId) }.getOrDefault(emptyMap())
        }

        val student = root.optJSONObject("xsxx")
        Timetable(
            xnm = meta.xnm,
            xqm = meta.xqm,
            weeks = weeks,
            currentWeek = currentWeek(weeks),
            courses = courses,
            jcTimes = jcTimes,
            headerDates = parseKbcxHeaderDates(root),
            studentName = student?.optStringAny("XM", "xm").orEmpty(),
            studentId = student?.optStringAny("XH", "xh").orEmpty(),
        )
    }

    // ── 接口 ────────────────────────────────────────────────

    private fun fetchWeeks(xnm: String, xqm: String): List<WeekInfo> {
        val json = postJson(weekListUrl, mapOf("xnm" to xnm, "xqm" to xqm))
        val array = asArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val zs = item.optStringAny("zs")
            if (zs.isBlank()) null else WeekInfo(zs = zs, zcrq = item.optStringAny("zcrq"))
        }
    }

    private fun fetchTimetable(xnm: String, xqm: String): JSONObject {
        val webFields = linkedMapOf(
            "xnm" to xnm,
            "xqm" to xqm,
            "kblx" to "xskb",
            "doType" to "kdkb",
        )
        val webResult = runCatching { postJson(timetableWebUrl, webFields) as? JSONObject }.getOrNull()
        if (webResult != null && hasCourses(webResult)) return webResult

        val mobileFields = linkedMapOf(
            "xnm" to xnm,
            "xqm" to xqm,
            "kblx" to "2",
            "doType" to "app",
        )
        val mobileResult = runCatching { postJson(timetableMobileUrl, mobileFields) as? JSONObject }.getOrNull()
        if (mobileResult != null && hasCourses(mobileResult)) return mobileResult

        // 两路都没有课：用网页版原始结果（可能是真的没课），否则用 Mobile 结果
        if (webResult != null) return webResult
        return mobileResult ?: throw JwxtException("教务系统没有返回课表数据")
    }

    private fun hasCourses(json: JSONObject): Boolean =
        (json.optJSONArray("kbList")?.length() ?: 0) > 0 ||
            (json.optJSONArray("sjkList")?.length() ?: 0) > 0

    private fun fetchJcTimes(xnm: String, xqm: String, xqhId: String): Map<Int, JcTime> {
        val fields = linkedMapOf("xnm" to xnm, "xqm" to xqm, "xqh_id" to xqhId)
        val result = runCatching {
            asArray(postJson(jcTimesWebUrl, fields))
        }.getOrNull()?.takeIf { it.length() > 0 }
            ?: asArray(postJson(jcTimesMobileUrl, fields))
        return parseKbcxJcTimes(result)
    }

    // ── 首页参数解析（移植自 KbcxAPI.get_semester_meta）────────

    private fun parseMeta(html: String): SemesterMeta {
        var xnm = hidden(html, "xnm_hide").orEmpty()
            .ifBlank { hidden(html, "xnm").orEmpty() }
        var xqm = hidden(html, "xqm_hide").orEmpty()
            .ifBlank { hidden(html, "xqm").orEmpty() }
        val xqhId = hidden(html, "xqh_id").orEmpty()
        val sfbczc = hidden(html, "sfbczc").orEmpty()

        if (xnm.isBlank()) xnm = jsVar(html, "xnm")
        if (xqm.isBlank()) xqm = jsVar(html, "xqm")

        // 教务系统默认给的是「最新学年」，按 8 月分界修正成真实当前学年
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val computedXnm = if (today.monthValue >= 8) today.year else today.year - 1
        if (xnm.isBlank()) {
            xnm = computedXnm.toString()
            if (xqm.isBlank()) xqm = if (today.monthValue >= 8) "3" else "12"
        } else if (xnm != computedXnm.toString()) {
            xnm = computedXnm.toString()
            xqm = if (today.monthValue >= 8) "3" else "12"
        }
        if (xqm.isBlank()) xqm = if (today.monthValue >= 8) "3" else "12"

        return SemesterMeta(
            xnm = xnm,
            xqm = xqm,
            xqhId = xqhId,
            sfbczc = sfbczc,
            xnmOptions = selectOptions(html, "xnm"),
            xqmOptions = selectOptions(html, "xqm"),
        )
    }

    /** 从 <input> 标签里取 id/name 匹配的 value，兼容属性乱序与单双引号。 */
    private fun hidden(html: String, field: String): String? {
        val tags = inputTag.findAll(html)
        for (tag in tags) {
            val attrs = attribute.findAll(tag.value).associate {
                it.groupValues[1].lowercase() to it.groupValues[2]
            }
            val id = attrs["id"]
            val name = attrs["name"]
            if (field.equals(id, ignoreCase = true) || field.equals(name, ignoreCase = true)) {
                return attrs["value"].orEmpty()
            }
        }
        return null
    }

    private fun jsVar(html: String, name: String): String {
        val escaped = Regex.escape(name)
        val patterns = listOf(
            Regex("""var\s+$escaped\s*=\s*["']([^"']*)["']"""),
            Regex("""["']$escaped["']\s*:\s*["']([^"']*)["']"""),
        )
        for (pattern in patterns) {
            pattern.find(html)?.let { return it.groupValues[1] }
        }
        return ""
    }

    private fun selectOptions(html: String, id: String): List<SelectOption> {
        val escaped = Regex.escape(id)
        val select = Regex(
            """<select\b[^>]*id\s*=\s*["']$escaped["'][^>]*>(.*?)</select>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1) ?: return emptyList()
        return optionTag.findAll(select).map {
            SelectOption(value = it.groupValues[1], label = it.groupValues[2].trim())
        }.toList()
    }

    // ── 课表解析 ────────────────────────────────────────────

    private fun currentWeek(weeks: List<WeekInfo>): Int {
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        weeks.forEachIndexed { index, week ->
            val start = parseWeekStart(week.zcrq) ?: return@forEachIndexed
            if (!today.isBefore(start) && !today.isAfter(start.plusDays(6))) return index + 1
        }
        return 1
    }

    // ── HTTP ────────────────────────────────────────────────

    private fun postJson(url: String, fields: Map<String, String>): Any {
        val body = fields.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val text = request(url, "POST", body).trim()
        return parseJson(text)
    }

    private fun parseJson(text: String): Any {
        if (text.startsWith("{")) {
            return runCatching { JSONObject(text) }.getOrElse { throw JwxtException("课表数据格式异常") }
        }
        if (text.startsWith("[")) {
            return runCatching { JSONArray(text) }.getOrElse { throw JwxtException("课表数据格式异常") }
        }
        if (text.contains("login_slogin") || text.contains("用户登录") ||
            text.contains("统一身份认证")
        ) {
            throw JwxtSessionExpiredException()
        }
        if (text.contains("无功能权限")) {
            throw JwxtException("教务系统密码已到期（无功能权限），需要重置密码")
        }
        if (text.contains("<html", ignoreCase = true) || text.contains("<!doctype", ignoreCase = true)) {
            throw JwxtException("教务系统返回了网页而不是课表数据")
        }
        throw JwxtException("教务系统返回了无法识别的内容")
    }

    private fun request(url: String, method: String, body: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Referer", indexUrl)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                // 只有 AJAX 接口带 X-Requested-With；页面 GET 带上它会被网关直接回 901
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
                doOutput = true
                setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8",
                )
            } else {
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8",
                )
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            android.util.Log.d(
                "JwxtClient",
                "$method ${url.substringBefore('?')} -> $code",
            )
            if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_MOVED_PERM
            ) {
                throw JwxtSessionExpiredException()
            }
            if (code !in 200..299) {
                val detail = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                android.util.Log.d("JwxtClient", "error body: ${detail.take(600)}")
                throw JwxtException("教务系统返回 HTTP $code ${detail.take(80)}".trim())
            }
            return connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (e: JwxtSessionExpiredException) {
            throw e
        } catch (e: JwxtException) {
            throw e
        } catch (e: IOException) {
            throw JwxtException("无法连接教务系统，请检查网络后重试")
        } finally {
            connection.disconnect()
        }
    }

    private fun asArray(json: Any?): JSONArray = when (json) {
        is JSONArray -> json
        is JSONObject -> {
            val keys = listOf("items", "data", "rows", "list")
            keys.firstNotNullOfOrNull { json.optJSONArray(it) } ?: JSONArray()
        }
        else -> JSONArray()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val inputTag = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val attribute = Regex("""([A-Za-z_][\w:-]*)\s*=\s*["']([^"']*)["']""")
        private val optionTag = Regex(
            """<option\b[^>]*value\s*=\s*["']([^"']*)["'][^>]*>(.*?)</option>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        /**
         * 从登录页地址推断教务系统根路径：
         * `https://jwxt.sdmu.edu.cn/jwglxt/xtgl/login_slogin.html` → `.../jwglxt`。
         */
        fun baseOf(serviceUrl: String): String {
            var url = serviceUrl.trim()
            if (url.isBlank()) return ""
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            val xtgl = url.indexOf("/xtgl/")
            if (xtgl > 0) return url.substring(0, xtgl).trimEnd('/')
            val jwglxt = url.indexOf("/jwglxt")
            if (jwglxt > 0) return url.substring(0, jwglxt + "/jwglxt".length).trimEnd('/')
            return url.trimEnd('/')
        }
    }
}

private fun JSONObject.optStringAny(vararg keys: String): String {
    for (key in keys) {
        val value = optString(key)
        if (value.isNotBlank()) return value
    }
    for (actual in keys()) {
        if (keys.any { it.equals(actual, ignoreCase = true) }) {
            val value = optString(actual)
            if (value.isNotBlank()) return value
        }
    }
    return ""
}

/** 周起始日期，兼容 "2025-09-01" 与 "(2025-09-01至2025-09-07)" 两种写法。 */
fun parseWeekStart(zcrq: String): LocalDate? {
    val match = Regex("""(\d{4}-\d{2}-\d{2})""").find(zcrq) ?: return null
    return runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
}