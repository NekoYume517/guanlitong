package com.sdmu.guanlitong.data.cx

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 学习通课程接口（直连）。
 *
 * 对照实现：D:\code\chaoxing-fenbushi\server\app\cx_api.py 的 `get_course_list`
 * 与 `_is_anti_crawl`。每页 50 条，取到不满一页就停；最多翻 [MAX_PAGES] 页，
 * 防止接口异常时无限翻页。
 */
class CxApi(
    cookies: Map<String, String>,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val attempts: Int = NETWORK_ATTEMPTS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {

    private val cookies: Map<String, String> = LinkedHashMap(cookies)

    suspend fun courses(): List<CxCourse> = withContext(Dispatchers.IO) {
        if (cookies.isEmpty()) throw CxException(AUTH_LOST, 401)

        val all = mutableListOf<CxCourse>()
        for (page in 1..MAX_PAGES) {
            val (status, body) = get(courseListUrl(page))
            when {
                isAntiCrawl(status, body) ->
                    throw CxException("学习通要求验证，请稍后再试", 403)

                status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_UNAUTHORIZED ->
                    throw CxException(AUTH_LOST, 401)

                status != HttpURLConnection.HTTP_OK ->
                    throw CxException("获取课程失败（HTTP $status）", 502)

                isLoginPage(body) -> throw CxException(AUTH_LOST, 401)

                else -> {
                    val pageCourses = CxCourseParser.parse(body)
                    all += pageCourses
                    if (pageCourses.size < PAGE_SIZE) return@withContext all
                }
            }
        }
        all
    }

    private fun courseListUrl(page: Int): String =
        "https://mooc1-1.chaoxing.com/visit/courselistdata" +
            "?courseType=1&courseFolderId=0&query=&pageSize=$PAGE_SIZE" +
            "&superstarClass=0&page=$page"

    /** 课程中间页：拿 enc / cpi / fid 等学习参数（对照 `get_course_detail`）。 */
    suspend fun courseDetail(courseId: String, clazzId: String, personId: String): CxCourseDetail =
        withContext(Dispatchers.IO) {
            val url = "https://mooc1-1.chaoxing.com/mooc-ans/visit/stucoursemiddle" +
                "?courseid=$courseId&clazzid=$clazzId&vc=1&cpi=$personId&ismooc2=1&v=2"
            val (status, body) = getFollowing(url, MOOC_REF)
            ensurePageOk(status, body, "获取课程详情")
            CxCourseDetail(
                enc = hiddenValue(body, "enc"),
                cpi = hiddenValue(body, "cpi").ifEmpty { personId },
                fid = hiddenValue(body, "fid"),
            )
        }

    /** 章节树（对照 `get_chapter_list`）。 */
    suspend fun chapterTree(
        courseId: String,
        clazzId: String,
        cpi: String,
        enc: String,
    ): List<CxChapterGroup> = withContext(Dispatchers.IO) {
        val url = "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse" +
            "?courseid=$courseId&clazzid=$clazzId&cpi=$cpi&enc=$enc&openc="
        val (status, body) = getFollowing(url, MOOC2_REF)
        ensurePageOk(status, body, "获取章节列表")
        val groups = CxChapterParser.parseTree(body)
        if (groups.isEmpty()) {
            // 空树要么是课程确实没有章节，要么是拿到认证/引导页——留痕便于排查，
            // 但不记录正文（可能含个人信息）。
            val title = TITLE_RE.find(body)?.groupValues?.getOrNull(1).orEmpty()
            Log.w(
                TAG,
                "chapterTree empty status=$status len=${body.length} " +
                    "title=$title hasChapterUnit=${body.contains("chapter_unit")} " +
                    "hasEnc=${body.contains("id=\"enc\"")}",
            )
        }
        groups
    }

    /**
     * 单个子章节的任务点（对照 `get_knowledge_cards`，自动翻 num 页）。
     * 风控页必须抛错，不能当成「没有任务点」。
     */
    suspend fun chapterCards(
        courseId: String,
        clazzId: String,
        cpi: String,
        chapterId: String,
    ): CxChapterPage = withContext(Dispatchers.IO) {
        val cards = mutableListOf<CxTaskCard>()
        var studyEnc = ""
        for (num in 0 until MAX_CARD_PAGES) {
            val url = "https://mooc1-1.chaoxing.com/mooc-ans/knowledge/cards" +
                "?clazzid=$clazzId&courseid=$courseId&knowledgeid=$chapterId" +
                "&num=$num&ut=s&cpi=$cpi&v=2025-0424-1038-4&mooc2=1" +
                "&isMicroCourse=false&editorPreview=0&crossId="
            val (status, body) = getFollowing(url, MOOC_REF)
            ensurePageOk(status, body, "获取章节任务点")
            val page = CxChapterParser.parseCards(body, chapterId)
            if (studyEnc.isEmpty()) studyEnc = page.studyEnc
            if (page.cards.isEmpty()) break
            cards += page.cards
            delay(CARD_PAGE_DELAY_MS) // 翻页限速，避免触发风控
        }
        CxChapterPage(cards, studyEnc)
    }

    /** 读本会话 Cookie（签到接口要 UID）。 */
    internal fun cookie(name: String): String = cookies[name].orEmpty()

    internal suspend fun get(url: String, referer: String = MOOC_REF): Pair<Int, String> {
        val (status, body, _) = exchange("GET", url, null, referer)
        return status to body
    }

    /**
     * 手动跟随 30x（每次仍带全量 Cookie）。
     * 学习通的 `stucoursemiddle` / `studentcourse` 常以 302 跳一次到最终页；
     * 关掉自动跟随是为了登录失效时能直接看 302，所以这里自己跟。
     */
    internal suspend fun getFollowing(url: String, referer: String = MOOC_REF): Pair<Int, String> {
        var current = url
        var redirects = 0
        repeat(MAX_REDIRECTS) {
            val (status, body, location) = exchange("GET", current, null, referer)
            if (status !in REDIRECT_CODES) {
                logFollowSummary(url, current, redirects, status)
                return status to body
            }
            val next = location?.takeIf { it.isNotBlank() } ?: run {
                logFollowSummary(url, current, redirects, status)
                return status to body
            }
            current = runCatching { URL(URL(current), next).toString() }.getOrDefault(current)
            redirects++
        }
        logFollowSummary(url, current, redirects, HttpURLConnection.HTTP_MOVED_TEMP)
        return HttpURLConnection.HTTP_MOVED_TEMP to ""
    }

    /**
     * 一次跟随请求的摘要：主机、Cookie 数量与名字、重定向次数、最终 URL。
     *
     * 只打 Cookie 名字，不打值。判断 activelist 是否被 302 到 passport/sso、
     * 或 Cookie 根本没带上，看这一行就够。
     */
    private fun logFollowSummary(
        startUrl: String,
        finalUrl: String,
        redirects: Int,
        status: Int,
    ) {
        val host = runCatching { URL(startUrl).host }.getOrDefault("?")
        val finalHost = runCatching { URL(finalUrl).host }.getOrDefault("?")
        val names = cookies.keys.joinToString(",").take(160)
        Log.i(
            TAG,
            "request host=$host finalHost=$finalHost cookies=${cookies.size}[$names] " +
                "redirects=$redirects final=$finalUrl status=$status",
        )
    }

    internal suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String = MOOC_REF,
    ): Pair<Int, String> {
        val body = form.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val (status, responseBody, _) = exchange("POST", url, body, referer)
        return status to responseBody
    }

    /**
     * 单次请求，连接/读取失败（超时、DNS 失败、连接重置、读响应中断）按
     * [RETRY_BASE_DELAY_MS] 起步的指数退避重试，最多 [attempts] 次；仍失败抛
     * [CxException]（[CX_CODE_NETWORK]），让上层把「这一章没取到」与「这章没有任务点」
     * 区分开，绝不当成空页面吞掉。
     *
     * `readAll` 不做异常兜底：读响应体失败必须算失败，否则超时会被静默变成空 HTML。
     */
    private suspend fun exchange(
        method: String,
        url: String,
        formBody: String?,
        referer: String,
    ): Triple<Int, String, String?> {
        var lastError: IOException? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) pause(RETRY_BASE_DELAY_MS shl (attempt - 1))
            try {
                return once(method, url, formBody, referer)
            } catch (e: IOException) {
                lastError = e
                Log.w(
                    TAG,
                    "request failed attempt=${attempt + 1}/$attempts $method " +
                        url.substringBefore('?') + " ${e.javaClass.simpleName}",
                )
            }
        }
        throw CxException(
            "网络请求失败（${lastError?.javaClass?.simpleName ?: "IOException"}），请检查网络后重试",
            CX_CODE_NETWORK,
        )
    }

    private fun once(
        method: String,
        url: String,
        formBody: String?,
        referer: String,
    ): Triple<Int, String, String?> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false // 登录失效时由 302 直接判定，不去读登录页
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("User-Agent", MOBILE_UA)
            setRequestProperty("Cookie", cookieHeader())
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            setRequestProperty("Referer", referer)
            if (formBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        return try {
            if (formBody != null) {
                connection.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val body = readAll(
                if (status >= 400) connection.errorStream else connection.inputStream,
            )
            Triple(status, body, connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }

    /** 统一的页面状态判定：风控 > 登录失效 > 其他非 200。 */
    internal fun ensurePageOk(status: Int, body: String, what: String) {
        if (isAntiCrawl(status, body)) throw CxException("学习通要求验证，请稍后再试", 403)
        if (status == HttpURLConnection.HTTP_MOVED_PERM ||
            status == HttpURLConnection.HTTP_MOVED_TEMP ||
            status == HttpURLConnection.HTTP_UNAUTHORIZED ||
            isLoginPage(body)
        ) {
            throw CxException(AUTH_LOST, 401)
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw CxException("${what}失败（HTTP $status）", 502)
        }
    }

    private fun hiddenValue(html: String, fieldId: String): String =
        Regex("""id="$fieldId"[^>]*value="([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** 202 或带验证码特征的 200，都是超星风控页。 */
    private fun isAntiCrawl(status: Int, body: String): Boolean =
        status == 202 || (status == 200 && ("验证码" in body || "processVerify" in body))

    /** 只有页面标题就是登录页时才认定会话失效，避免课程页里的退出链接误伤。 */
    private fun isLoginPage(body: String): Boolean =
        LOGIN_TITLE_RE.containsMatchIn(body)

    private fun readAll(stream: InputStream?): String =
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    private companion object {
        const val TAG = "CxApi"
        val TITLE_RE = Regex("""<title>([^<]*)</title>""", RegexOption.IGNORE_CASE)
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 10
        const val MAX_CARD_PAGES = 20
        const val CARD_PAGE_DELAY_MS = 250L
        const val MAX_REDIRECTS = 5
        const val DEFAULT_CONNECT_TIMEOUT_MS = 12_000
        const val DEFAULT_READ_TIMEOUT_MS = 20_000
        const val NETWORK_ATTEMPTS = 3
        const val RETRY_BASE_DELAY_MS = 500L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val AUTH_LOST = "登录已失效，请重新登录"
        const val MOOC_REF = "https://mooc1-1.chaoxing.com/"
        const val MOOC2_REF = "https://mooc2-ans.chaoxing.com/"
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.6099.144 Mobile Safari/537.36"
        val LOGIN_TITLE_RE = Regex("""<title>[^<]*登录[^<]*</title>""", RegexOption.IGNORE_CASE)
    }
}