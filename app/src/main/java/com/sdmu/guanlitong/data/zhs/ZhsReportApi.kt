package com.sdmu.guanlitong.data.zhs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 课程成绩分析，对照参考实现 `get_report`。
 *
 * 复用上一批的 stuonline 会话（CAS 换专属 JSESSIONID），依次请求：
 * `loadStuLearnReport`（主数据）、`loadStuHabit`、`loadCourseForum`、
 * `loadExamAndScore`、`loadKnowledgeMaster`。主数据失败整页失败；
 * 其余四个各自可选失败，对应字段留空，不拖垮整页。
 */
object ZhsReportApi {

    internal const val REPORT_API =
        "${ZhsLearnApi.STUONLINE_ORIGIN}/stuonline/json/stuLearnReportNew/loadStuLearnReport/"
    internal const val HABIT_API =
        "${ZhsLearnApi.STUONLINE_ORIGIN}/stuonline/json/stuLearnReportNew/loadStuHabit/"
    internal const val FORUM_API =
        "${ZhsLearnApi.STUONLINE_ORIGIN}/stuonline/json/stuLearnReportNew/loadCourseForum/"
    internal const val EXAM_API =
        "${ZhsLearnApi.STUONLINE_ORIGIN}/stuonline/json/stuLearnReportNew/loadExamAndScore/"
    internal const val MASTER_API =
        "${ZhsLearnApi.STUONLINE_ORIGIN}/stuonline/json/stuLearnReportNew/loadKnowledgeMaster/"

    suspend fun fetch(session: ZhsSession, course: ZhsCourse): ZhsReport =
        withContext(Dispatchers.IO) {
            if (!session.hasTicket) throw ZhsLearnApi.expired()
            val http = ZhsLearnApi.openStuonline(session, course, "成绩加载失败，请稍后重试")
            val main = post(http, course, REPORT_API, reportForm(course), required = true)
                ?: throw ZhsException("成绩加载失败，请稍后重试", ZhsErrorKind.SERVER)
            val habit = post(http, course, HABIT_API, tabForm(course), required = false)
            val forum = post(http, course, FORUM_API, tabForm(course), required = false)
            val exam = post(http, course, EXAM_API, examForm(course), required = false)
            val master = post(http, course, MASTER_API, tabForm(course), required = false)
            ZhsReportParser.parse(main, habit, forum, exam, master)
        }

    internal fun reportForm(course: ZhsCourse): Map<String, String> = linkedMapOf(
        "courseId" to course.courseId.toString(),
        "recruitId" to course.recruitId.toString(),
        "stuRankType" to "classRank",
    )

    internal fun tabForm(course: ZhsCourse): Map<String, String> = linkedMapOf(
        "courseId" to course.courseId.toString(),
        "recruitId" to course.recruitId.toString(),
        "type" to "l",
        "courseTypeEs" to "1",
    )

    internal fun examForm(course: ZhsCourse): Map<String, String> = linkedMapOf(
        "recruitId" to course.recruitId.toString(),
        "chapterIds" to "",
        "lessonIds" to "",
        "lessonType" to "",
    )

    private fun post(
        http: ZhsHttp,
        course: ZhsCourse,
        url: String,
        form: Map<String, String>,
        required: Boolean,
    ): String? {
        val headers = ZhsLearnApi.chapterHeaders(ZhsLearnApi.indexUrl(course))
        val response = try {
            http.postForm(url, form, headers)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (required) throw ioError(e, "成绩加载失败，请稍后重试")
            return null
        }
        return try {
            ZhsLearnApi.checkAuth(response)
            ZhsLearnApi.rejectHtml(response.body)
            response.body
        } catch (e: ZhsException) {
            if (required || e.kind == ZhsErrorKind.EXPIRED) throw e
            null
        }
    }
}