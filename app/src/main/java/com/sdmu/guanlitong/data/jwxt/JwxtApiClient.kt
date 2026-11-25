package com.sdmu.guanlitong.data.jwxt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

// ── 模型 ────────────────────────────────────────────────────

data class GradeItem(
    val name: String,
    val score: String,
    val credit: Double,
    val point: Double?,
    val nature: String,
    val type: String,
    val teacher: String,
    val semester: String,
    val semesterKey: String,
) {
    val numericScore: Double? = score.toDoubleOrNull()

    val passed: Boolean = numericScore?.let { it >= 60 } == true ||
        score.contains("合格") || score.contains("通过") || score.contains("优秀") ||
        score.contains("良好") || score.contains("中等") || score.contains("及格")
}

data class ExamItem(
    val name: String,
    val timeText: String,
    val date: String,
    val start: String,
    val end: String,
    val location: String,
    val type: String,
    val teacher: String,
    val campus: String,
) {
    /** 已结束 / 进行中 / 未开始，用于状态标签。 */
    fun status(nowMillis: Long): Int {
        val parse = runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            val startAt = if (start.isNotBlank()) fmt.parse("$date $start") else fmt.parse("$date 00:00")
            val endAt = if (end.isNotBlank()) fmt.parse("$date $end") else fmt.parse("$date 23:59")
            startAt.time to endAt.time
        }.getOrNull() ?: return 2
        return when {
            nowMillis < parse.first -> 0
            nowMillis > parse.second -> 2
            else -> 1
        }
    }
}

data class TeachingClass(
    val jxbId: String,
    val doJxbId: String,
    val name: String,
    val time: String,
    val place: String,
    val teacher: String,
    val selected: Int?,
    val capacity: Int?,
    val credit: String,
    val type: String,
    val campus: String,
) {
    val canSelect: Boolean =
        capacity == null || capacity <= 0 || (selected ?: 0) < capacity
}

data class SelectionCourse(
    val kchId: String,
    val code: String,
    val name: String,
    val credit: String,
    val cxbj: String,
    val fxbj: String,
    val classes: List<TeachingClass> = emptyList(),
)

data class ChosenCourse(
    val kchId: String,
    val jxbId: String,
    val doJxbId: String,
    val name: String,
    val time: String,
    val place: String,
    val teacher: String,
    val credit: String,
    val type: String,
)

data class SelectionStatus(
    val open: Boolean,
    val courses: List<SelectionCourse>,
    val message: String,
)

data class OperationResult(val success: Boolean, val message: String)

// ── 客户端 ──────────────────────────────────────────────────

/**
 * 教务系统数据接口（成绩、考试、选课）。
 *
 * 与 [JwxtClient] 一样直接带 WebView 的 Cookie 请求教务系统；
 * 接口与参数移植自 sdmu-website-2 的 `jwxt_api.py` / `xk_api.py`。
 */
class JwxtApiClient(
    serviceUrl: String,
    private val cookie: String,
) {
    val baseUrl: String = JwxtClient.baseOf(serviceUrl)

    private val menuReferer = "$baseUrl/xtgl/index_initMenu.html"

    // ── 成绩 ────────────────────────────────────────────────

    suspend fun fetchGrades(): List<GradeItem> = withContext(Dispatchers.IO) {
        requireSession()
        ensureSession()
        val fields = linkedMapOf(
            "_search" to "false",
            "nd" to System.currentTimeMillis().toString(),
            "rows" to "300",
            "page" to "1",
            "sidx" to "",
            "sord" to "asc",
            "queryModel.showCount" to "300",
            "queryModel.currentPage" to "1",
            "queryModel.sortOrder" to "asc",
            "queryModel.sortName" to "",
            "xnm" to "",
            "xqm" to "",
            "sfzgcj" to "",
            "kcbj" to "",
            "pkey" to "",
        )
        val text = request(
            url = "$baseUrl/cjcx/cjcx_cxDgXscj.html?doType=query",
            body = form(fields),
            referer = "$baseUrl/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005",
        )
        if (text.contains("没有访问权限")) throw JwxtException("当前账号没有成绩查询权限")
        return@withContext parseGradeItems(jsonObject(text, "成绩数据"))
    }

    // ── 考试 ────────────────────────────────────────────────

    suspend fun fetchExams(): List<ExamItem> = withContext(Dispatchers.IO) {
        requireSession()
        ensureSession()
        val text = request(
            url = "$baseUrl/kwgl/kscx_cxXsksxxIndex.html?doType=query",
            body = form(linkedMapOf("page" to "1", "rows" to "300")),
            referer = "$baseUrl/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default",
        )
        return@withContext parseExamItems(jsonObject(text, "考试数据"))
    }

    // ── 选课 ────────────────────────────────────────────────

    /** 选课入口参数；同一个 [JwxtApiClient] 实例内缓存，避免重复 GET 打乱会话。 */
    private var selectionParams: Map<String, String>? = null

    suspend fun selectionOpen(): Boolean = withContext(Dispatchers.IO) {
        requireSession()
        indexParams()["iskxk"] == "1"
    }

    suspend fun queryCourses(keyword: String): SelectionStatus = withContext(Dispatchers.IO) {
        requireSession()
        val p = indexParams()
        if (p["iskxk"] != "1") {
            return@withContext SelectionStatus(false, emptyList(), "当前不在选课时间")
        }
        val payload = selectionQueryPayload(p).apply {
            put("filter_list[0]", keyword)
            put("kspage", "0")
            put("jspage", "100")
        }
        val text = request(
            url = "$baseUrl/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html",
            body = form(payload),
            referer = menuReferer,
        )
        if (text.trim() == "0") {
            return@withContext SelectionStatus(true, emptyList(), "教务系统拒绝了查询（可能是选课批次已结束）")
        }
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return@withContext SelectionStatus(true, emptyList(), "教务系统返回了无法识别的内容")
        val courses = parseSelectionCourses(root.optJSONArray("tmpList") ?: JSONArray())
        SelectionStatus(
            open = true,
            courses = courses,
            message = if (courses.isEmpty()) "没有查询到课程" else "共 ${courses.size} 门课",
        )
    }

    suspend fun classes(course: SelectionCourse): List<TeachingClass> = withContext(Dispatchers.IO) {
        requireSession()
        val p = indexParams()
        val payload = linkedMapOf(
            "searchInput" to "",
            "rwlx" to p.def("rwlx", "2"),
            "xkly" to p.def("xkly", "0"),
            "bklx_id" to p.def("bklx_id", "0"),
            "sfkkjyxdxnxq" to p.def("sfkkjyxdxnxq", "0"),
            "kzkcgs" to p.def("kzkcgs", "0"),
            "xqh_id" to p.def("xqh_id"),
            "jg_id" to p.def("jg_id_1"),
            "zyh_id" to p.def("zyh_id"),
            "zyfx_id" to p.def("zyfx_id"),
            "txbsfrl" to p.def("txbsfrl"),
            "njdm_id" to p.def("njdm_id"),
            "bh_id" to p.def("bh_id"),
            "xbm" to p.def("xbm"),
            "xslbdm" to p.def("xslbdm"),
            "mzm" to p.def("mzm"),
            "xz" to p.def("xz"),
            "ccdm" to p.def("ccdm"),
            "xsbj" to p.def("xsbj"),
            "sfkknj" to p.def("sfkknj"),
            "gnjkxdnj" to p.def("gnjkxdnj"),
            "sfkkzy" to p.def("sfkkzy"),
            "kzybkxy" to p.def("kzybkxy"),
            "sfznkx" to p.def("sfznkx"),
            "zdkxms" to p.def("zdkxms"),
            "sfkxq" to p.def("sfkxq"),
            "bhbcyxkjxb" to p.def("bhbcyxkjxb"),
            "sfkcfx" to p.def("sfkcfx"),
            "bbhzxjxb" to p.def("bbhzxjxb"),
            "kkbk" to p.def("kkbk"),
            "kkbkdj" to p.def("kkbkdj"),
            "bklbkcj" to p.def("bklbkcj"),
            "xkxnm" to p.def("xkxnm"),
            "xkxqm" to p.def("xkxqm"),
            "xkxskcgskg" to p.def("xkxskcgskg"),
            "rlkz" to p.def("rlkz"),
            "cdrlkz" to p.def("cdrlkz"),
            "rlzlkz" to p.def("rlzlkz"),
            "kklxdm" to p.def("kklxdm"),
            "kch_id" to course.kchId,
            "jxbzcxskg" to p.def("jxbzcxskg"),
            "xklc" to p.def("xklc"),
            "xkkz_id" to p.def("xkkz_id"),
            "cxbj" to course.cxbj,
            "fxbj" to course.fxbj,
        )
        val text = request(
            url = "$baseUrl/xsxk/zzxkyzbjk_cxJxbWithKchZzxkYzb.html",
            body = form(payload),
            referer = menuReferer,
        )
        val root = runCatching { JSONObject(text) }.getOrNull()
        val array = when {
            text.trim().startsWith("[") -> JSONArray(text)
            root != null -> root.optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        return@withContext parseTeachingClasses(array)
    }

    suspend fun fetchChosen(): List<ChosenCourse> = withContext(Dispatchers.IO) {
        requireSession()
        val p = indexParams()
        val payload = linkedMapOf(
            "jg_id" to p.def("jg_id_1"),
            "zyh_id" to p.def("zyh_id"),
            "njdm_id" to p.def("njdm_id"),
            "zyfx_id" to p.def("zyfx_id"),
            "bh_id" to p.def("bh_id"),
            "xz" to p.def("xz"),
            "ccdm" to p.def("ccdm"),
            "xqh_id" to p.def("xqh_id"),
            "xkxnm" to p.def("xkxnm"),
            "xkxqm" to p.def("xkxqm"),
            "xkly" to p.def("xkly", "0"),
        )
        val text = request(
            url = "$baseUrl/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html",
            body = form(payload),
            referer = menuReferer,
        )
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: runCatching { JSONObject(text).optJSONArray("items") ?: JSONArray() }.getOrNull()
            ?: JSONArray()
        return@withContext parseChosenRows(array)
    }

    suspend fun choose(
        kchId: String,
        doJxbId: String,
        courseName: String,
    ): OperationResult = withContext(Dispatchers.IO) {
        runCatching {
            requireSession()
            val html = request(
                url = "$baseUrl/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default",
                body = null,
                referer = menuReferer,
            )
            if (!html.contains("id=\"iskxk\" value=\"1\"") &&
                !html.contains("id='iskxk' value='1'")
            ) {
                return@runCatching OperationResult(false, "当前不在选课时间")
            }
            val payload = linkedMapOf(
                "jxb_ids" to doJxbId,
                "kch_id" to kchId,
                "kcmc" to courseName,
                "rwlx" to hidden(html, "rwlx", "2"),
                "rlkz" to hidden(html, "rlkz", "0"),
                "cdrlkz" to hidden(html, "cdrlkz", "0"),
                "rlzlkz" to hidden(html, "rlzlkz", "1"),
                "sxbj" to hidden(html, "sxbj", "1"),
                "xxkbj" to hidden(html, "xxkbj", "0"),
                "qz" to hidden(html, "qz", "0"),
                "cxbj" to hidden(html, "cxbj", "0"),
                "xkkz_id" to hidden(html, "firstXkkzId"),
                "njdm_id" to hidden(html, "njdm_id"),
                "zyh_id" to hidden(html, "zyh_id"),
                "kklxdm" to hidden(html, "firstKklxdm"),
                "xklc" to "1",
                "xkxnm" to hidden(html, "xkxnm"),
                "xkxqm" to hidden(html, "xkxqm"),
                "jcxx_id" to "",
            )
            val text = request(
                url = "$baseUrl/xsxk/zzxkyzbjk_xkBcZyZzxkYzb.html?gnmkdm=N253512",
                body = form(payload),
                referer = menuReferer,
            )
            parseOperation(text, "选课")
        }.getOrElse { OperationResult(false, operationErrorMessage(it, "选课失败，请稍后重试")) }
    }

    suspend fun drop(
        kchId: String,
        doJxbId: String,
    ): OperationResult = withContext(Dispatchers.IO) {
        runCatching {
            requireSession()
            selectionParams = null
            val p = indexParams()
            if (p["iskxk"] != "1") return@runCatching OperationResult(false, "当前不在选课时间")
            runCatching {
                request(
                    url = "$baseUrl/xsxk/zzxkyzb_cxTkTitleMsg.html?gnmkdm=N253512",
                    body = form(
                        linkedMapOf(
                            "xkkz_id" to p.def("xkkz_id"),
                            "jxb_id" to doJxbId,
                            "bj" to "10",
                        ),
                    ),
                    referer = menuReferer,
                )
            }
            val text = request(
                url = "$baseUrl/xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm=N253512",
                body = form(
                    linkedMapOf(
                        "kch_id" to kchId,
                        "jxb_ids" to doJxbId,
                        "xkxnm" to p.def("xkxnm"),
                        "xkxqm" to p.def("xkxqm"),
                        "txbsfrl" to p.def("txbsfrl", "0"),
                    ),
                ),
                referer = menuReferer,
            )
            parseOperation(text, "退课")
        }.getOrElse { OperationResult(false, operationErrorMessage(it, "退课失败，请稍后重试")) }
    }

    // ── 选课入口参数 ────────────────────────────────────────

    private fun indexParams(): Map<String, String> {
        selectionParams?.let { return it }
        val html = request(
            url = "$baseUrl/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default",
            body = null,
            referer = menuReferer,
        )
        val params = linkedMapOf(
            "iskxk" to hidden(html, "iskxk"),
            "csrftoken" to hidden(html, "csrftoken"),
            "sessionUserKey" to hidden(html, "sessionUserKey"),
            "xkkz_id" to hidden(html, "firstXkkzId"),
            "kklxdm" to hidden(html, "firstKklxdm"),
            "kklxmc" to hidden(html, "firstKklxmc"),
            "njdm_id" to hidden(html, "firstNjdmId"),
            "zyh_id" to hidden(html, "firstZyhId"),
            "xkxnm" to hidden(html, "xkxnm"),
            "xkxqm" to hidden(html, "xkxqm"),
            "xszxzt" to hidden(html, "xszxzt"),
            "rwlx" to hidden(html, "rwlx"),
            "xklc" to hidden(html, "xklc"),
            "xkly" to hidden(html, "xkly"),
            "bklx_id" to hidden(html, "bklx_id"),
            "sfkkjyxdxnxq" to hidden(html, "sfkkjyxdxnxq"),
            "kzkcgs" to hidden(html, "kzkcgs"),
            "xqh_id" to hidden(html, "xqh_id"),
            "jg_id_1" to hidden(html, "jg_id_1"),
            "njdm_id_1" to hidden(html, "njdm_id_1"),
            "zyh_id_1" to hidden(html, "zyh_id_1"),
            "zyfx_id" to hidden(html, "zyfx_id"),
            "bh_id" to hidden(html, "bh_id"),
            "xbm" to hidden(html, "xbm"),
            "xslbdm" to hidden(html, "xslbdm"),
            "mzm" to hidden(html, "mzm"),
            "xz" to hidden(html, "xz"),
            "ccdm" to hidden(html, "ccdm"),
            "xsbj" to hidden(html, "xsbj"),
            "sfkknj" to hidden(html, "sfkknj"),
            "sfkkzy" to hidden(html, "sfkkzy"),
            "kzybkxy" to hidden(html, "kzybkxy"),
            "sfznkx" to hidden(html, "sfznkx"),
            "zdkxms" to hidden(html, "zdkxms"),
            "sfkxq" to hidden(html, "sfkxq"),
            "bhbcyxkjxb" to hidden(html, "bhbcyxkjxb"),
            "sfkcfx" to hidden(html, "sfkcfx"),
            "bbhzxjxb" to hidden(html, "bbhzxjxb"),
            "kkbk" to hidden(html, "kkbk"),
            "kkbkdj" to hidden(html, "kkbkdj"),
            "bklbkcj" to hidden(html, "bklbkcj"),
            "rlkz" to hidden(html, "rlkz"),
            "cdrlkz" to hidden(html, "cdrlkz"),
            "rlzlkz" to hidden(html, "rlzlkz"),
            "txbsfrl" to hidden(html, "txbsfrl"),
            "isinxksj" to hidden(html, "isinxksj"),
            "xkzgbj" to hidden(html, "xkzgbj"),
            "jzxkf" to hidden(html, "jzxkf"),
            "xkxfqzfs" to hidden(html, "xkxfqzfs"),
            "kklxpx" to hidden(html, "kklxpx"),
            "jxbzbkg" to hidden(html, "jxbzbkg"),
            "jxbzhkg" to hidden(html, "jxbzhkg"),
            "sfkgbcx" to hidden(html, "sfkgbcx"),
            "sfrxtgkcxd" to hidden(html, "sfrxtgkcxd"),
            "tykczgxdcs" to hidden(html, "tykczgxdcs"),
            "bjgkczxbbjwcx" to hidden(html, "bjgkczxbbjwcx"),
            "gnjkxdnj" to hidden(html, "gnjkxdnj"),
            "xkmcjzxskcs" to hidden(html, "xkmcjzxskcs").ifBlank { "10" },
        )
        if (params["iskxk"].orEmpty().isBlank() && html.contains("login_slogin")) {
            throw JwxtSessionExpiredException()
        }
        selectionParams = params
        return params
    }

    private fun selectionQueryPayload(p: Map<String, String>): LinkedHashMap<String, String> =
        linkedMapOf(
            "filter_list[0]" to "",
            "xkkz_id" to p.def("xkkz_id"),
            "kklxdm" to p.def("kklxdm"),
            "rwlx" to p.def("rwlx", "2"),
            "xklc" to p.def("xklc", "1"),
            "xkly" to p.def("xkly", "0"),
            "bklx_id" to p.def("bklx_id", "0"),
            "sfkkjyxdxnxq" to p.def("sfkkjyxdxnxq", "0"),
            "kzkcgs" to p.def("kzkcgs", "0"),
            "xqh_id" to p.def("xqh_id"),
            "jg_id" to p.def("jg_id_1"),
            "njdm_id_1" to p.def("njdm_id_1"),
            "zyh_id_1" to p.def("zyh_id_1"),
            "gnjkxdnj" to p.def("gnjkxdnj"),
            "zyh_id" to p.def("zyh_id"),
            "zyfx_id" to p.def("zyfx_id"),
            "njdm_id" to p.def("njdm_id"),
            "bh_id" to p.def("bh_id"),
            "bjgkczxbbjwcx" to p.def("bjgkczxbbjwcx"),
            "xbm" to p.def("xbm"),
            "xslbdm" to p.def("xslbdm"),
            "mzm" to p.def("mzm"),
            "xz" to p.def("xz"),
            "ccdm" to p.def("ccdm"),
            "xsbj" to p.def("xsbj"),
            "sfkknj" to p.def("sfkknj"),
            "sfkkzy" to p.def("sfkkzy"),
            "kzybkxy" to p.def("kzybkxy"),
            "sfznkx" to p.def("sfznkx"),
            "zdkxms" to p.def("zdkxms"),
            "sfkxq" to p.def("sfkxq"),
            "bhbcyxkjxb" to p.def("bhbcyxkjxb"),
            "sfkcfx" to p.def("sfkcfx"),
            "kkbk" to p.def("kkbk"),
            "kkbkdj" to p.def("kkbkdj"),
            "bklbkcj" to p.def("bklbkcj"),
            "sfkgbcx" to p.def("sfkgbcx"),
            "sfrxtgkcxd" to p.def("sfrxtgkcxd"),
            "tykczgxdcs" to p.def("tykczgxdcs"),
            "xkxnm" to p.def("xkxnm"),
            "xkxqm" to p.def("xkxqm"),
            "bbhzxjxb" to p.def("bbhzxjxb"),
            "rlkz" to p.def("rlkz"),
            "xkzgbj" to p.def("xkzgbj"),
            "gnmkdm" to "N253512",
        )

    // ── HTTP ────────────────────────────────────────────────

    private fun requireSession() {
        if (baseUrl.isBlank()) throw JwxtException("尚未配置教务系统地址")
        if (cookie.isBlank()) throw JwxtSessionExpiredException("还没登录教务系统")
    }

    /** 先 GET 一次教务首页确认会话，避免直接 POST 数据接口被网关拦成 901。 */
    private fun ensureSession() {
        val text = request(menuReferer, null, menuReferer)
        if (text.contains("login_slogin", ignoreCase = true)) {
            throw JwxtSessionExpiredException()
        }
    }

    private fun request(url: String, body: String?, referer: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("User-Agent", USER_AGENT)
            if (body == null) {
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
            } else {
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
                setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8",
                )
                doOutput = true
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM) {
                throw JwxtSessionExpiredException("登录状态已失效，请重新登录教务系统")
            }
            if (code == 901) {
                throw JwxtSessionExpiredException("教务网关拒绝了本次请求（901），请重新登录或在网页中使用")
            }
            if (code !in 200..299) {
                val detail = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw JwxtException("教务系统返回 HTTP $code ${detail.take(80)}".trim())
            }
            val text = connection.inputStream
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.contains("login_slogin", ignoreCase = true) && !text.contains("id=\"iskxk\"")) {
                throw JwxtSessionExpiredException()
            }
            if (text.contains("无功能权限")) {
                throw JwxtException("教务系统密码已到期（无功能权限），需要重置密码")
            }
            return text
        } catch (e: JwxtException) {
            throw e
        } catch (e: IOException) {
            throw JwxtException("无法连接教务系统，请检查网络后重试")
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonObject(text: String, label: String): JSONObject {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }
                .getOrElse { throw JwxtException("$label 格式异常") }
        }
        if (trimmed.startsWith("<")) {
            throw JwxtException("教务系统返回了网页而不是$label")
        }
        throw JwxtException("教务系统返回了无法识别的$label")
    }

    private fun form(fields: Map<String, String>): String =
        fields.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    private fun parseOperation(text: String, action: String): OperationResult {
        val trimmed = text.trim()
        if (trimmed == "1" || trimmed == "\"1\"" || trimmed.equals("true", true)) {
            return OperationResult(true, "${action}成功")
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull()
        if (root != null) {
            val flag = root.optString("flag").ifBlank { root.optString("code") }
            val message = root.optString("msg").ifBlank { root.optString("message") }
            if (flag.isNotBlank()) {
                val ok = flag in setOf("1", "true", "success", "ok")
                return OperationResult(ok, message.ifBlank { if (ok) "${action}成功" else "${action}失败" })
            }
        }
        if (trimmed.contains("成功")) return OperationResult(true, "${action}成功")
        val tip = Regex("""[>「【]([^<>」】]{0,100}(?:失败|错误|不可|无余量|冲突)[^<>」】]{0,50})[<」】]""")
            .find(trimmed)?.groupValues?.get(1)
        return OperationResult(false, tip?.trim() ?: "${action}失败：${trimmed.take(80)}")
    }

    /** 从入口页 HTML 取 hidden input 的值（兼容 id/name 两种写法）。 */
    private fun hidden(html: String, field: String, default: String = ""): String {
        val escaped = Regex.escape(field)
        val patterns = listOf(
            Regex("""name=["']$escaped["'][^>]*value=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""id=["']$escaped["'][^>]*value=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""value=["']([^"']*)["'][^>]*name=["']$escaped["']""", RegexOption.IGNORE_CASE),
            Regex("""value=["']([^"']*)["'][^>]*id=["']$escaped["']""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            pattern.find(html)?.let { return it.groupValues[1] }
        }
        return default
    }

    companion object {
        private fun operationErrorMessage(error: Throwable, fallback: String): String = when (error) {
            is JwxtSessionExpiredException -> "登录状态已失效，请重新登录"
            is JwxtException -> error.message
                ?.takeUnless { it.isBlank() || it.contains("HTTP") }
                ?: fallback
            else -> "操作失败，请检查网络后重试"
        }

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}