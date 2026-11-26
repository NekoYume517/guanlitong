package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
class CxApi(cookies: Map<String, String>) {

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

    private fun get(url: String): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false // 登录失效时由 302 直接判定，不去读登录页
            connectTimeout = 12_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("User-Agent", MOBILE_UA)
            setRequestProperty("Cookie", cookieHeader())
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            setRequestProperty("Referer", "https://mooc1-1.chaoxing.com/")
        }
        return try {
            val status = connection.responseCode
            val body = readAll(
                if (status >= 400) connection.errorStream else connection.inputStream,
            )
            status to body
        } finally {
            connection.disconnect()
        }
    }

    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** 202 或带验证码特征的 200，都是超星风控页。 */
    private fun isAntiCrawl(status: Int, body: String): Boolean =
        status == 202 || (status == 200 && ("验证码" in body || "processVerify" in body))

    /** 只有页面标题就是登录页时才认定会话失效，避免课程页里的退出链接误伤。 */
    private fun isLoginPage(body: String): Boolean =
        LOGIN_TITLE_RE.containsMatchIn(body)

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        return runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 10
        const val AUTH_LOST = "登录已失效，请重新登录"
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.6099.144 Mobile Safari/537.36"
        val LOGIN_TITLE_RE = Regex("""<title>[^<]*登录[^<]*</title>""", RegexOption.IGNORE_CASE)
    }
}