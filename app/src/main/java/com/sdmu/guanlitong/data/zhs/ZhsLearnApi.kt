package com.sdmu.guanlitong.data.zhs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 课程章节与学习进度，对照参考实现 `get_chapter_list` / `get_study_progress`。
 *
 * 两条会话是分开的，和参考实现一致：
 * - stuonline 章节接口要用一次 CAS 换来的专属 JSESSIONID：拿现有 Cookie 建独立
 *   [ZhsHttp]，先 GET `passport/login?service=<学习报告页>` 完成认证，再 POST
 *   `loadStuLearingTab`；
 * - 进度接口走 study 系统：`c/has` 用 RSA module=11 换一把 AES 密钥，
 *   `queryStuyInfo` 的 `secretStr` 用这把密钥加密。
 *
 * 两边的 Cookie 互不回写，避免把 stuonline 的 JSESSIONID 混进账号态、带到别的接口。
 */
object ZhsLearnApi {

    internal const val PASSPORT_LOGIN = "https://passport.zhihuishu.com/login"
    internal const val STUONLINE_ORIGIN = "https://stuonline.zhihuishu.com"
    internal const val CHAPTER_API = "$STUONLINE_ORIGIN/stuonline/json/stuLearnReportNew/loadStuLearingTab/"
    internal const val STUDY_INFO_API =
        "https://studyservice-api.zhihuishu.com/gateway/t/v1/learning/queryStuyInfo"
    internal const val WISDOM_ORIGIN = "https://wisdom-mooc.zhihuishu.com"

    private const val UA = "Mozilla/5.0"

    suspend fun fetch(
        session: ZhsSession,
        course: ZhsCourse,
        clock: () -> Long = { System.currentTimeMillis() },
    ): ZhsCourseDetail = withContext(Dispatchers.IO) {
        if (!session.hasTicket) throw expired()
        val chapters = ZhsLearnParser.parseChapters(loadChapters(session, course))
        val merged = if (chapters.isEmpty()) {
            chapters
        } else {
            val progress = ZhsLearnParser.parseProgress(queryProgress(session, course, chapters, clock))
            ZhsLearnParser.merge(chapters, progress)
        }
        ZhsCourseDetail(chapters = merged, summary = ZhsLearnParser.summary(merged))
    }

    /** CAS 到 stuonline 学习报告页，拿专属 JSESSIONID；成绩接口复用这个会话。 */
    internal fun openStuonline(session: ZhsSession, course: ZhsCourse, fallback: String): ZhsHttp {
        val http = ZhsHttp(LinkedHashMap(session.cookies))
        val warm = network(
            { http.get("$PASSPORT_LOGIN?service=${ZhsHttp.urlEncode(indexUrl(course))}", casHeaders()) },
            fallback,
        )
        checkAuth(warm)
        return http
    }

    /** CAS 到 stuonline，再拉章节树。 */
    internal fun loadChapters(session: ZhsSession, course: ZhsCourse): String {
        val http = openStuonline(session, course, "章节加载失败，请稍后重试")
        val response = network(
            { http.postForm(CHAPTER_API, chapterForm(course), chapterHeaders(indexUrl(course))) },
            "章节加载失败，请稍后重试",
        )
        checkAuth(response)
        rejectHtml(response.body)
        return response.body
    }

    /** 换 study 系统密钥并查询进度。 */
    internal fun queryProgress(
        session: ZhsSession,
        course: ZhsCourse,
        chapters: List<ZhsChapter>,
        clock: () -> Long = { System.currentTimeMillis() },
    ): String {
        val http = ZhsHttp(LinkedHashMap(session.cookies))
        val key = studyKey(http)
        val date = clock()
        val payload = progressPayload(
            lessonIds = ZhsLearnParser.lessonIds(chapters),
            recruitId = course.recruitId,
            videoIds = ZhsLearnParser.videoIds(chapters),
            date = date,
        )
        val form = linkedMapOf(
            "dateFormate" to date.toString(),
            "secretStr" to ZhsCrypto.aesEncrypt(payload, key),
        )
        val response = network({ http.postForm(STUDY_INFO_API, form, studyHeaders(session)) }, "学习进度加载失败，请稍后重试")
        checkAuth(response)
        rejectHtml(response.body)
        return response.body
    }

    /** `c/has` 用 module=11 换学习系统 AES 密钥。 */
    internal fun studyKey(http: ZhsHttp): String {
        val encrypted = ZhsCrypto.rsaEncryptForExchange(module = 11)
        val url = "${ZhsCourseApi.KEY_EXCHANGE_URL}?uid=${ZhsHttp.urlEncode(encrypted)}"
        val response = network({ http.get(url, studyKeyHeaders()) }, "学习密钥获取失败，请稍后重试")
        checkAuth(response)
        val sl = runCatching {
            JSONObject(response.body).optJSONObject("rt")?.optString("sl")
        }.getOrNull().orEmpty()
        if (sl.isBlank()) throw ZhsException("学习密钥交换失败，请稍后重试", ZhsErrorKind.SERVER)
        return ZhsCrypto.rsaDecryptKey(sl)?.takeIf { it.isNotBlank() }
            ?: throw ZhsException("学习密钥解析失败，请稍后重试", ZhsErrorKind.SERVER)
    }

    internal fun indexUrl(course: ZhsCourse): String =
        "$STUONLINE_ORIGIN/stuonline/stuLearnReportNew/index?recruitId=${course.recruitId}&courseId=${course.courseId}"

    internal fun chapterForm(course: ZhsCourse): Map<String, String> = linkedMapOf(
        "courseId" to course.courseId.toString(),
        "recruitId" to course.recruitId.toString(),
        "type" to "l",
        "courseTypeEs" to "1",
    )

    /**
     * `queryStuyInfo` 的明文参数，紧凑 JSON、字段顺序固定
     * （与参考实现的 `json.dumps(separators=(',',':'))` 逐字节一致）。
     */
    internal fun progressPayload(
        lessonIds: List<Long>,
        recruitId: Long,
        videoIds: List<Long>,
        date: Long,
    ): String = buildString {
        append("{\"lessonIds\":[").append(lessonIds.joinToString(",")).append("],")
        append("\"recruitId\":").append(recruitId).append(",")
        append("\"lessonVideoIds\":[").append(videoIds.joinToString(",")).append("],")
        append("\"dateFormate\":").append(date).append('}')
    }

    internal fun casHeaders(): Map<String, String> = mapOf("User-Agent" to UA)

    internal fun chapterHeaders(indexUrl: String): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Origin" to STUONLINE_ORIGIN,
        "Referer" to indexUrl,
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun studyKeyHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json",
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun studyHeaders(session: ZhsSession): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put("Accept", "application/json")
        put("Origin", WISDOM_ORIGIN)
        put("Referer", "$WISDOM_ORIGIN/")
        // 参考实现把 jt-cas 原样放进 Authorization。
        session.cookies["jt-cas"]?.takeIf { it.isNotBlank() }?.let { put("Authorization", it) }
    }

    internal fun expired() = ZhsException("登录已过期，请重新登录知到。", ZhsErrorKind.EXPIRED)

    internal fun checkAuth(response: ZhsHttp.Response) {
        if (response.url.contains("passport.zhihuishu.com")) throw expired()
        if (response.code == 401 || response.code == 403) throw expired()
        if (zhsAuthRequired(response.body)) throw expired()
    }

    /** 上游认证失败时会回登录页 HTML，不能当成 JSON 继续解析。 */
    internal fun rejectHtml(body: String) {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("<")) return
        if (trimmed.contains("passport.zhihuishu.com")) throw expired()
        throw ZhsException("知到返回了网页而不是数据，请稍后重试", ZhsErrorKind.SERVER)
    }

    internal inline fun <T> network(block: () -> T, fallback: String): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ioError(e, fallback)
    }
}