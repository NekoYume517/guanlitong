package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** ananas/status 返回的视频元信息；duration 单位秒。 */
data class CxVideoStatus(val duration: Int = 0, val dtoken: String = "")

/** 一次 multimedia/log 上报的结果。 */
data class CxPushResult(
    val isPassed: Boolean,
    val raw: String = "",
    val error: String? = null,
)

/**
 * 刷课协议层（直连），逐项对照老项目 `cx_api.py`：
 * `get_video_status` / `push_video_progress` / `send_chapter_setlog` /
 * `flush_pending_setlog` / `answer_video_quizzes` / `mark_chapter_studying`。
 *
 * 这个类只负责协议，不管节奏；心跳节奏在 [CxStudyRunner]。
 * 实例内缓存课程级 setlog encode（课程常量，取一次即可）。
 */
class CxStudyApi(
    private val api: CxApi,
    private val uid: String,
    private val fid: String,
) {

    private data class PendingSetlog(
        val course: CxStudyCourse,
        val chapterId: String,
    )

    /** 课程级 setlog encode（studentstudy 页 `encode=` 提取）。 */
    private val setlogEncodes = mutableMapOf<String, String>()

    /** 课程级 studyEnc（cards 页 `_from` 提取；新版 stucoursemiddle 已不带 enc）。 */
    private val studyEncCache = mutableMapOf<String, String>()

    private val pendingSetlogs = mutableListOf<PendingSetlog>()
    private var lastFlushAt = 0L

    suspend fun courseDetail(courseId: String, clazzId: String, personId: String) =
        api.courseDetail(courseId, clazzId, personId)

    suspend fun chapterTree(courseId: String, clazzId: String, cpi: String, enc: String) =
        api.chapterTree(courseId, clazzId, cpi, enc)

    suspend fun chapterCards(courseId: String, clazzId: String, cpi: String, chapterId: String):
        CxChapterPage {
        val page = api.chapterCards(courseId, clazzId, cpi, chapterId)
        if (page.studyEnc.isNotBlank()) studyEncCache[courseId] = page.studyEnc
        return page
    }

    /** 标记章节开始学习；失败只记日志，不阻断刷课。 */
    suspend fun markChapterStudying(course: CxStudyCourse, chapterId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                api.postForm(
                    "https://mooc1-1.chaoxing.com/mooc-ans/mycourse/studentstudyAjax",
                    mapOf(
                        "courseId" to course.courseId,
                        "clazzid" to course.clazzId,
                        "chapterId" to chapterId,
                        "cpi" to course.cpi,
                        "verificationcode" to "",
                    ),
                    REF_MOOC1,
                )
            }
        }
    }

    /** 视频真实时长与 dtoken。风控抛 403，其余非 200 抛 502。 */
    suspend fun videoStatus(objectId: String): CxVideoStatus = withContext(Dispatchers.IO) {
        if (objectId.isBlank()) return@withContext CxVideoStatus()
        val url = "https://mooc1.chaoxing.com/ananas/status/$objectId" +
            "?k=${fid.ifBlank { "9790" }}&flag=normal&ro=0&_dc=${System.currentTimeMillis()}"
        val (status, body) = api.getFollowing(url, REF_MOOC1)
        api.ensurePageOk(status, body, "获取视频状态")
        val json = jsonOf(body) ?: return@withContext CxVideoStatus()
        CxVideoStatus(
            duration = intOf(json, "duration"),
            dtoken = json.optString("dtoken"),
        )
    }

    /**
     * 上报观看进度。参数串见 [CxStudyParams]。
     *
     * @param dtype Video / Audio
     */
    suspend fun pushProgress(
        course: CxStudyCourse,
        card: CxTaskCard,
        current: Int,
        duration: Int,
        isDrag: Int,
        dtoken: String,
        dtype: String,
    ): CxPushResult = withContext(Dispatchers.IO) {
        if (dtoken.isBlank()) return@withContext CxPushResult(false, error = "no dtoken")

        val clip = CxVideoEnc.clipTime(card.begins, card.ends, duration)
        val enc = CxVideoEnc.enc(
            clazzId = course.clazzId,
            uid = uid,
            jobid = card.jobid,
            objectId = card.objectId,
            playingTimeMs = current * 1000L,
            durationMs = duration * 1000L,
            clipTime = clip,
        )
        val attDuration =
            if (dtype == "Video") card.attDuration?.takeIf { it > 0 } else null
        val attDurationEnc =
            if (dtype == "Video") card.attDurationEnc.takeIf { attDuration != null } else null
        val query = CxStudyParams.query(
            clazzId = course.clazzId,
            playingTime = current,
            duration = duration,
            clipTime = clip,
            objectId = card.objectId,
            otherInfo = card.otherInfo,
            jobid = card.jobid,
            userId = uid,
            isDrag = isDrag,
            enc = enc,
            dtype = dtype,
            attDuration = attDuration,
            attDurationEnc = attDurationEnc,
            timestampMs = System.currentTimeMillis(),
        )
        val url = CxStudyParams.url(card.reportUrl, dtoken, query)
        val (status, body) = api.getFollowing(url, REF_MOOC1)
        api.ensurePageOk(status, body, "上报视频进度")

        val json = jsonOf(body)
        CxPushResult(
            isPassed = json?.optBoolean("isPassed") == true,
            raw = body,
        )
    }

    /**
     * 视频弹题自动作答：选项自带 isRight，提交走 quiz-validation。
     * 弹题是旁路，任何失败（含风控）只跳过，不影响主流程。
     */
    suspend fun answerQuizzes(course: CxStudyCourse, card: CxTaskCard): Int =
        withContext(Dispatchers.IO) {
            if (card.mid.isBlank() || card.objectId.isBlank()) return@withContext 0
            runCatching {
                val url = "https://mooc1.chaoxing.com/mooc-ans/richvideo/initdatawithviewerV2" +
                    "?mid=${card.mid}&cpi=${course.cpi}&classid=${course.clazzId}" +
                    "&courseid=${course.courseId}"
                val (status, body) = api.getFollowing(url, REF_MOOC1)
                if (status == 202 || body.contains("验证码") || body.contains("processVerify")) {
                    return@runCatching 0
                }
                val trimmed = body.trim()
                if (status != 200 || !trimmed.startsWith("[")) return@runCatching 0
                val quizzes = JSONArray(trimmed)
                var answered = 0
                for (i in 0 until quizzes.length()) {
                    val quiz = quizzes.optJSONObject(i) ?: continue
                    val options = quiz.optJSONArray("options") ?: JSONArray()
                    val correct = buildList {
                        for (j in 0 until options.length()) {
                            val option = options.optJSONObject(j) ?: continue
                            if (isRight(option.opt("isRight"))) add(option.optString("name"))
                        }
                    }
                    if (correct.isEmpty()) continue

                    val answer = correct.joinToString(",")
                    val vurl = "https://mooc1.chaoxing.com/question/quiz-validation" +
                        "?classid=${course.clazzId}&cpi=${course.cpi}" +
                        "&objectid=${card.objectId}" +
                        "&eventid=${encode(quiz.optString("resourceId"))}" +
                        "&memberinfo=${encode(quiz.optString("memberinfo"))}" +
                        "&answerContent=${encode(answer)}"
                    val (vs, vbody) = api.getFollowing(vurl, REF_MOOC1)
                    if (vs == 200 && jsonOf(vbody)?.optBoolean("isRight") == true) answered++
                }
                answered
            }.getOrDefault(0)
        }

    /**
     * 章节打开信标（官方 setlog）。被风控时挂起，由 [flushPendingSetlog] 冷却后补发。
     * 401 仍上抛，交给执行器走单飞重登。
     */
    suspend fun sendSetlog(course: CxStudyCourse, chapterId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                var encode = setlogEncodes[course.courseId].orEmpty()
                if (encode.isEmpty()) {
                    // studyEnc 优先取 cards 页 `_from`；新版本 stucoursemiddle 通常不给 enc，
// 直接拿 course.enc 会拼出空参数，setlog 静默失败。
                    val studyEnc = studyEncCache[course.courseId].orEmpty()
                        .ifEmpty { course.enc }
                    val pageUrl = "https://mooc1.chaoxing.com/mycourse/studentstudy" +
                        "?chapterId=$chapterId&courseId=${course.courseId}" +
                        "&clazzid=${course.clazzId}&cpi=${course.cpi}&enc=$studyEnc&mooc2=1"
                    val (status, html) = api.getFollowing(pageUrl, REF_MOOC1)
                    if (status == 202 || (status == 200 && isVerifyPage(html))) {
                        pendingSetlogs += PendingSetlog(course, chapterId)
                        return@withContext false
                    }
                    if (status == 302 || status == 401 || isLoginPage(html)) {
                        throw CxException("登录已失效，请重新登录", 401)
                    }
                    if (status != 200) return@withContext false
                    encode = Regex("encode=([0-9a-f]{32})").find(html)?.groupValues?.get(1).orEmpty()
                    if (encode.isEmpty()) return@withContext false
                    setlogEncodes[course.courseId] = encode
                }

                val personid = course.cpi.ifEmpty { uid }
                val beacon = "https://fystat-ans.chaoxing.com/log/setlog" +
                    "?personid=$personid&courseId=${course.courseId}" +
                    "&classId=${course.clazzId}&encode=$encode&chapterId=$chapterId"
                val (beaconStatus, _) = api.getFollowing(beacon, REF_MOOC1)
                if (beaconStatus == 202) {
                    pendingSetlogs += PendingSetlog(course, chapterId)
                    return@withContext false
                }
                beaconStatus == 200
            } catch (e: CxException) {
                if (e.code == 403) {
                    pendingSetlogs += PendingSetlog(course, chapterId)
                    false
                } else {
                    throw e
                }
            }
        }

    /** 补发挂起的 setlog 信标，60 秒内最多一次。返回本次成功数。 */
    suspend fun flushPendingSetlog(encByCourse: Map<String, String> = emptyMap()): Int {
        if (pendingSetlogs.isEmpty()) return 0
        val now = System.currentTimeMillis()
        if (now - lastFlushAt < 60_000) return 0
        lastFlushAt = now

        val pending = pendingSetlogs.toList()
        pendingSetlogs.clear()
        var ok = 0
        for (item in pending) {
            val course = item.course.copy(enc = encByCourse[item.course.courseId] ?: item.course.enc)
            if (sendSetlog(course, item.chapterId)) ok++
        }
        return ok
    }

    private fun isVerifyPage(html: String): Boolean =
        html.contains("验证码") || html.contains("processVerify")

    private fun isLoginPage(html: String): Boolean =
        Regex("""<title>[^<]*登录[^<]*</title>""", RegexOption.IGNORE_CASE).containsMatchIn(html)

    private fun isRight(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() == 1
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> false
    }

    private fun intOf(obj: JSONObject, key: String): Int =
        when (val v = obj.opt(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }

    private fun jsonOf(body: String): JSONObject? =
        runCatching { JSONObject(body) }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val REF_MOOC1 = "https://mooc1-1.chaoxing.com/"
    }
}