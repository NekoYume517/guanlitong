package com.sdmu.guanlitong.data.jwxt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 抢课助手（sdmu-website-2）后端接口。
 *
 * 用站点账号登录后，后端会拿站内配置的教务账号代查课表、成绩、考试和选课数据，
 * 返回值大多是教务原始字段，可以直接复用 [JwxtParsing] 的解析逻辑。
 */
class GrabApiClient(
    serviceUrl: String,
    private val cookie: String,
) {
    val baseUrl: String = baseOf(serviceUrl)

    // ── 课表 ────────────────────────────────────────────────

    suspend fun fetchTimetable(): Timetable = withContext(Dispatchers.IO) {
        requireSession()
        val semesterRoot = checkSuccess(request("/api/timetable/semester"))
        val semester = semesterRoot.optJSONObject("data")
            ?: throw JwxtException("抢课助手没有返回学期信息")
        val xnm = semester.optString("xnm")
        val xqm = semester.optString("xqm")
        val weeks = semester.optJSONArray("weeks")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val zs = item.str("zs")
                if (zs.isBlank()) null else WeekInfo(zs = zs, zcrq = item.str("zcrq"))
            }
        }.orEmpty()
        val currentWeek = semester.optInt("current_week", 1)
            .takeIf { it in 1..weeks.size.coerceAtLeast(1) } ?: 1

        val query = buildString {
            append("/api/timetable?kblx=2")
            if (xnm.isNotBlank()) append("&xnm=").append(enc(xnm))
            if (xqm.isNotBlank()) append("&xqm=").append(enc(xqm))
        }
        val root = checkSuccess(request(query))
        val data = root.optJSONObject("data")
            ?: throw JwxtException("抢课助手没有返回课表数据")
        val courses = parseKbcxCourses(data)
        val jcTimes = runCatching { fetchJcTimes(xnm, xqm) }.getOrDefault(emptyMap())
        val student = data.optJSONObject("xsxx")

        Timetable(
            xnm = xnm,
            xqm = xqm,
            weeks = weeks,
            currentWeek = currentWeek,
            courses = courses,
            jcTimes = jcTimes,
            headerDates = parseKbcxHeaderDates(data),
            studentName = student?.str("XM", "xm").orEmpty(),
            studentId = student?.str("XH", "xh").orEmpty(),
        )
    }

    private fun fetchJcTimes(xnm: String, xqm: String): Map<Int, JcTime> {
        val query = buildString {
            append("/api/timetable/jc_times")
            val params = buildList {
                if (xnm.isNotBlank()) add("xnm=" + enc(xnm))
                if (xqm.isNotBlank()) add("xqm=" + enc(xqm))
            }
            if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
        }
        val data = checkSuccess(request(query)).optJSONObject("data") ?: return emptyMap()
        return parseGrabJcTimes(data)
    }

    // ── 成绩 / 考试 ─────────────────────────────────────────

    suspend fun fetchGrades(): List<GradeItem> = withContext(Dispatchers.IO) {
        requireSession()
        parseGradeItems(checkSuccess(request("/api/jwxt/grades?page=1&page_size=300")))
    }

    suspend fun fetchExams(): List<ExamItem> = withContext(Dispatchers.IO) {
        requireSession()
        parseExamItems(checkSuccess(request("/api/jwxt/exams")))
    }

    // ── 选课 ────────────────────────────────────────────────

    suspend fun selectionOpen(): Boolean = withContext(Dispatchers.IO) {
        requireSession()
        val root = request("/api/courses/status")
        root.optBoolean("success", false) && root.optBoolean("is_open", false)
    }

    suspend fun queryCourses(keyword: String): SelectionStatus = withContext(Dispatchers.IO) {
        requireSession()
        val root = request("/api/courses/list?keyword=" + enc(keyword.trim()))
        if (!root.optBoolean("success", true)) {
            val message = friendly(root.optString("message").ifBlank { "查询课程失败" })
            if (!root.optBoolean("is_open", false)) {
                return@withContext SelectionStatus(false, emptyList(), message)
            }
            throw JwxtException(message)
        }
        val courses = parseSelectionCourses(root.optJSONArray("courses") ?: JSONArray())
        SelectionStatus(
            open = true,
            courses = courses,
            message = if (courses.isEmpty()) "没有查询到课程" else "共 ${courses.size} 门课",
        )
    }

    suspend fun classes(course: SelectionCourse): List<TeachingClass> = withContext(Dispatchers.IO) {
        requireSession()
        val query = buildString {
            append("/api/courses/jxb_list?kch_id=").append(enc(course.kchId))
            append("&cxbj=").append(enc(course.cxbj.ifBlank { "0" }))
            append("&fxbj=").append(enc(course.fxbj.ifBlank { "0" }))
        }
        val root = checkSuccess(request(query))
        parseTeachingClasses(root.optJSONArray("jxbs") ?: root.optJSONArray("items") ?: JSONArray())
    }

    suspend fun fetchChosen(): List<ChosenCourse> = withContext(Dispatchers.IO) {
        requireSession()
        val root = checkSuccess(request("/api/courses/chosen"))
        val rows = root.optJSONArray("courses")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("rows")
            ?: JSONArray()
        parseChosenRows(rows)
    }

    suspend fun choose(
        kchId: String,
        doJxbId: String,
        courseName: String,
    ): OperationResult = withContext(Dispatchers.IO) {
        requireSession()
        val body = JSONObject()
            .put("jxb_id", doJxbId)
            .put("do_jxb_id", doJxbId)
            .put("kch_id", kchId)
            .put("kcmc", courseName)
        operation(request("/api/courses/add", body), "选课")
    }

    suspend fun drop(
        kchId: String,
        doJxbId: String,
    ): OperationResult = withContext(Dispatchers.IO) {
        requireSession()
        val body = JSONObject()
            .put("jxb_id", doJxbId)
            .put("do_jxb_id", doJxbId)
            .put("kch_id", kchId)
        operation(request("/api/courses/drop", body), "退课")
    }

    // ── 抢课任务 ────────────────────────────────────────────

    suspend fun fetchTasks(): List<GrabTaskInfo> = withContext(Dispatchers.IO) {
        requireSession()
        val root = checkSuccess(request("/api/tasks/list?page=1&per_page=50"))
        val array = root.optJSONArray("tasks") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parseTask)
        }
    }

    private fun parseTask(item: JSONObject): GrabTaskInfo = GrabTaskInfo(
        id = item.optInt("id"),
        courseName = item.str("course_name"),
        courseId = item.str("course_id"),
        teacher = item.str("teacher_name"),
        classInfo = item.str("class_info"),
        credit = item.str("credit"),
        status = item.str("status"),
        taskType = item.str("task_type"),
        retryCount = item.optInt("retry_count", 0),
        maxRetries = item.optInt("max_retries", 0),
        resultMessage = item.str("result_message"),
        createdAt = item.str("created_at"),
        startedAt = item.str("started_at"),
        completedAt = item.str("completed_at"),
        scheduledTime = item.str("scheduled_time"),
    )

    suspend fun createTask(
        course: SelectionCourse,
        klass: TeachingClass,
    ): OperationResult = withContext(Dispatchers.IO) {
        requireSession()
        val body = JSONObject()
            .put("course_name", course.name)
            .put("course_id", klass.jxbId)
            .put("jxb_id", klass.jxbId)
            .put("do_jxb_id", klass.doJxbId)
            .put("kch_id", course.kchId)
            .put("kklxdm", "")
            .put("jxbzls", "0")
            .put("teacher_name", klass.teacher)
            .put("credit", klass.credit)
            .put(
                "class_info",
                listOf(klass.time, klass.place)
                    .filter { it.isNotBlank() && it != "--" }
                    .joinToString(" · "),
            )
            .put("task_type", "immediate")
        operation(request("/api/tasks/create", body), "创建抢课任务")
    }

    suspend fun cancelTask(taskId: Int): OperationResult = withContext(Dispatchers.IO) {
        requireSession()
        operation(request("/api/tasks/$taskId/cancel", JSONObject()), "取消任务")
    }

    suspend fun fetchTask(taskId: Int): GrabTaskInfo = withContext(Dispatchers.IO) {
        requireSession()
        val root = checkSuccess(request("/api/tasks/$taskId"))
        val item = root.optJSONObject("task")
            ?: throw JwxtException("抢课助手没有返回任务详情")
        parseTask(item)
    }

    /**
     * 任务日志。首次取 `/api/logs/list`（按时间倒序），
     * 之后轮询 `/api/logs/recent?after_id=` 只拿增量。
     */
    suspend fun fetchLogs(taskId: Int, afterId: Int = 0): List<GrabLogInfo> =
        withContext(Dispatchers.IO) {
            requireSession()
            val path = if (afterId > 0) {
                "/api/logs/recent?task_id=$taskId&after_id=$afterId"
            } else {
                "/api/logs/list?task_id=$taskId&per_page=100"
            }
            val root = checkSuccess(request(path))
            val array = root.optJSONArray("logs") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { item ->
                    GrabLogInfo(
                        id = item.optInt("id"),
                        taskId = item.optInt("task_id"),
                        level = item.str("level"),
                        message = item.str("message"),
                        createdAt = item.str("created_at"),
                    )
                }
            }
        }

    // ── HTTP ────────────────────────────────────────────────

    private fun requireSession() {
        if (baseUrl.isBlank()) throw JwxtException("尚未配置抢课助手地址")
        if (cookie.isBlank()) throw JwxtSessionExpiredException("尚未登录抢课助手，请先在「我的 → 抢课助手」登录")
    }

    private fun request(path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code in 300..399 || code == 401 || code == 403) {
                throw JwxtSessionExpiredException("抢课助手登录状态已失效，请重新登录")
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw JwxtException("抢课助手返回 HTTP $code ${text.take(80)}".trim())
            }
            val root = runCatching { JSONObject(text) }.getOrNull()
                ?: run {
                    if (text.contains("登录") || text.contains("<html", ignoreCase = true)) {
                        throw JwxtSessionExpiredException("抢课助手登录状态已失效，请重新登录")
                    }
                    throw JwxtException("抢课助手返回了无法识别的内容")
                }
            return root
        } catch (e: JwxtException) {
            throw e
        } catch (e: IOException) {
            throw JwxtException("无法连接抢课助手：${e.message ?: "网络错误"}")
        } finally {
            connection.disconnect()
        }
    }

    private fun checkSuccess(root: JSONObject): JSONObject {
        if (!root.optBoolean("success", true)) {
            throw JwxtException(friendly(root.optString("message").ifBlank { "抢课助手返回了失败结果" }))
        }
        return root
    }

    private fun operation(root: JSONObject, action: String): OperationResult {
        val ok = root.optBoolean("success", false)
        val message = root.optString("message").ifBlank {
            if (ok) "${action}成功" else "${action}失败"
        }
        return OperationResult(ok, friendly(message))
    }

    private fun friendly(message: String): String = when {
        message.contains("未配置教务系统账号") || message.contains("未配置教务") ->
            "抢课助手还没有配置教务账号，请先在网页版里完成配置"
        message.contains("Session不存在") || message.contains("Session 不存在") ->
            "抢课助手的教务会话不可用，请先在网页版里重新登录教务系统"
        else -> message
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 站点根地址：`https://jwxt.bansonic.top/` → `https://jwxt.bansonic.top`。 */
        fun baseOf(serviceUrl: String): String {
            var url = serviceUrl.trim()
            if (url.isBlank()) return ""
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            return url.trimEnd('/')
        }
    }
}

/** 抢课助手的一条抢课任务（`/api/tasks/list`）。 */
data class GrabTaskInfo(
    val id: Int,
    val courseName: String,
    val courseId: String,
    val teacher: String,
    val classInfo: String,
    val credit: String,
    val status: String,
    val taskType: String,
    val retryCount: Int,
    val maxRetries: Int = 0,
    val resultMessage: String,
    val createdAt: String,
    val startedAt: String = "",
    val completedAt: String = "",
    val scheduledTime: String,
) {
    val active: Boolean get() = status == "pending" || status == "running"
}

/** 抢课助手的一条任务日志（`/api/logs/list`）。 */
data class GrabLogInfo(
    val id: Int,
    val taskId: Int,
    val level: String,
    val message: String,
    val createdAt: String,
)

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")