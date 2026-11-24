package com.sdmu.guanlitong.data.jwxt

/**
 * 教务原生页使用的数据源。
 *
 * 官方和抢课助手两套实现暴露同一组方法，页面只依赖这个接口。
 */
interface JwxtDataSource {
    suspend fun fetchTimetable(): Timetable

    suspend fun fetchGrades(): List<GradeItem>

    suspend fun fetchExams(): List<ExamItem>

    suspend fun queryCourses(keyword: String): SelectionStatus

    suspend fun classes(course: SelectionCourse): List<TeachingClass>

    suspend fun fetchChosen(): List<ChosenCourse>

    suspend fun choose(
        kchId: String,
        doJxbId: String,
        courseName: String,
    ): OperationResult

    suspend fun drop(kchId: String, doJxbId: String): OperationResult

    // ── 抢课任务（仅抢课助手支持） ──────────────────────────

    suspend fun fetchTasks(): List<GrabTaskInfo> =
        throw JwxtException("当前数据来源不支持抢课任务，请切换到抢课助手")

    suspend fun createTask(
        course: SelectionCourse,
        klass: TeachingClass,
    ): OperationResult = throw JwxtException("当前数据来源不支持抢课任务，请切换到抢课助手")

    suspend fun cancelTask(taskId: Int): OperationResult =
        throw JwxtException("当前数据来源不支持抢课任务，请切换到抢课助手")

    suspend fun fetchTask(taskId: Int): GrabTaskInfo =
        throw JwxtException("当前数据来源不支持抢课任务，请切换到抢课助手")

    /** 任务日志；[afterId] > 0 时只取该条之后的增量。 */
    suspend fun fetchLogs(taskId: Int, afterId: Int = 0): List<GrabLogInfo> =
        throw JwxtException("当前数据来源不支持抢课任务，请切换到抢课助手")
}

private class OfficialJwxtDataSource(
    private val baseUrl: String,
    private val cookie: String,
) : JwxtDataSource {

    private val api: JwxtApiClient by lazy { JwxtApiClient(baseUrl, cookie) }

    override suspend fun fetchTimetable(): Timetable = JwxtClient(baseUrl, cookie).fetch()

    override suspend fun fetchGrades(): List<GradeItem> = api.fetchGrades()

    override suspend fun fetchExams(): List<ExamItem> = api.fetchExams()

    override suspend fun queryCourses(keyword: String): SelectionStatus = api.queryCourses(keyword)

    override suspend fun classes(course: SelectionCourse): List<TeachingClass> = api.classes(course)

    override suspend fun fetchChosen(): List<ChosenCourse> = api.fetchChosen()

    override suspend fun choose(
        kchId: String,
        doJxbId: String,
        courseName: String,
    ): OperationResult = api.choose(kchId, doJxbId, courseName)

    override suspend fun drop(kchId: String, doJxbId: String): OperationResult =
        api.drop(kchId, doJxbId)
}

private class GrabJwxtDataSource(baseUrl: String, cookie: String) : JwxtDataSource {

    private val api = GrabApiClient(baseUrl, cookie)

    override suspend fun fetchTimetable(): Timetable = api.fetchTimetable()

    override suspend fun fetchGrades(): List<GradeItem> = api.fetchGrades()

    override suspend fun fetchExams(): List<ExamItem> = api.fetchExams()

    override suspend fun queryCourses(keyword: String): SelectionStatus = api.queryCourses(keyword)

    override suspend fun classes(course: SelectionCourse): List<TeachingClass> = api.classes(course)

    override suspend fun fetchChosen(): List<ChosenCourse> = api.fetchChosen()

    override suspend fun choose(
        kchId: String,
        doJxbId: String,
        courseName: String,
    ): OperationResult = api.choose(kchId, doJxbId, courseName)

    override suspend fun drop(kchId: String, doJxbId: String): OperationResult =
        api.drop(kchId, doJxbId)

    override suspend fun fetchTasks(): List<GrabTaskInfo> = api.fetchTasks()

    override suspend fun createTask(
        course: SelectionCourse,
        klass: TeachingClass,
    ): OperationResult = api.createTask(course, klass)

    override suspend fun cancelTask(taskId: Int): OperationResult = api.cancelTask(taskId)

    override suspend fun fetchTask(taskId: Int): GrabTaskInfo = api.fetchTask(taskId)

    override suspend fun fetchLogs(taskId: Int, afterId: Int): List<GrabLogInfo> =
        api.fetchLogs(taskId, afterId)
}

/**
 * 会话失效时自动重登再重试一次的数据源包装。
 *
 * 页面只拿到 [JwxtAccess.client]，所有请求都会经过这里：
 * 遇到 `JwxtSessionExpiredException` 时交给 [relogin]（带重试与指数退避），
 * 拿到新 Cookie 后重建客户端并重放这次请求；重登失败则把原异常抛给页面。
 */
private class ReloginJwxtDataSource(
    private var delegate: JwxtDataSource,
    private val relogin: suspend () -> String?,
    private val recreate: (String) -> JwxtDataSource?,
) : JwxtDataSource {

    private suspend fun <T> guard(block: suspend (JwxtDataSource) -> T): T = try {
        block(delegate)
    } catch (e: JwxtSessionExpiredException) {
        val cookie = relogin() ?: throw e
        val next = recreate(cookie) ?: throw e
        delegate = next
        block(next)
    }

    override suspend fun fetchTimetable(): Timetable = guard { it.fetchTimetable() }

    override suspend fun fetchGrades(): List<GradeItem> = guard { it.fetchGrades() }

    override suspend fun fetchExams(): List<ExamItem> = guard { it.fetchExams() }

    override suspend fun queryCourses(keyword: String): SelectionStatus =
        guard { it.queryCourses(keyword) }

    override suspend fun classes(course: SelectionCourse): List<TeachingClass> =
        guard { it.classes(course) }

    override suspend fun fetchChosen(): List<ChosenCourse> = guard { it.fetchChosen() }

    override suspend fun choose(
        kchId: String,
        doJxbId: String,
        courseName: String,
    ): OperationResult = guard { it.choose(kchId, doJxbId, courseName) }

    override suspend fun drop(kchId: String, doJxbId: String): OperationResult =
        guard { it.drop(kchId, doJxbId) }

    override suspend fun fetchTasks(): List<GrabTaskInfo> = guard { it.fetchTasks() }

    override suspend fun createTask(
        course: SelectionCourse,
        klass: TeachingClass,
    ): OperationResult = guard { it.createTask(course, klass) }

    override suspend fun cancelTask(taskId: Int): OperationResult = guard { it.cancelTask(taskId) }

    override suspend fun fetchTask(taskId: Int): GrabTaskInfo = guard { it.fetchTask(taskId) }

    override suspend fun fetchLogs(taskId: Int, afterId: Int): List<GrabLogInfo> =
        guard { it.fetchLogs(taskId, afterId) }
}

object JwxtDataSourceFactory {

    /** 地址缺失或未登录时返回 null，由页面给出登录引导。 */
    fun create(
        source: JwxtSource,
        officialUrl: String,
        grabUrl: String,
        cookie: String,
    ): JwxtDataSource? {
        if (cookie.isBlank()) return null
        return when (source) {
            JwxtSource.Official -> JwxtClient.baseOf(officialUrl)
                .takeIf { it.isNotBlank() }
                ?.let { OfficialJwxtDataSource(it, cookie) }

            JwxtSource.Grab -> GrabApiClient.baseOf(grabUrl)
                .takeIf { it.isNotBlank() }
                ?.let { GrabJwxtDataSource(it, cookie) }
        }
    }

    internal fun withRelogin(
        inner: JwxtDataSource,
        relogin: suspend () -> String?,
        recreate: (String) -> JwxtDataSource?,
    ): JwxtDataSource = ReloginJwxtDataSource(inner, relogin, recreate)
}