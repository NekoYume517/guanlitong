package com.sdmu.guanlitong.data.zhs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 刷课协议客户端，逐节对照参考实现的 `brush_lesson` / `videolist_real_video_id`：
 *
 * 1. `loadVideoPointerInfo` 拉弹题，顺带建立学习记录；
 * 2. `queryStuyInfo` 确保记录存在，并拿到本节已学秒数 `base_time`；
 * 3. 弹题用 `lessonPopupExam` 取题、选 `result==1` 的选项，
 *    用 `batchSaveLessonPopupExamSaveAnswer` 提交；
 * 4. `prelearningNote` 拿每会话都会变的 `studiedLessonDto.id`，base64 成 `zwsds`；
 * 5. 再 warm 一次 `loadVideoPointerInfo`；
 * 6. 按倍速节奏逐个 `saveCacheIntervalTimeV2` + `saveDatabaseIntervalTimeV2`，
 *    报文 `ewssw` / `sdsew` / `zwsds` / `courseId` 用 module 11 的 AES 密钥加密；
 * 7. 响应出现「需要弹出滑块验证」立即抛 [ZhsCaptchaException]。
 *
 * 报文里的 `sdsew` 用真实 videoId（`videolist` 映射，查不到回落 lessonVideoId），
 * 与参考实现一致；Cookie 与 `jt-cas` 原样透传，会话不回写。
 */
internal class ZhsStudyClient(
    session: ZhsSession,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val http = ZhsHttp(LinkedHashMap(session.cookies))
    private val auth = session.cookies["jt-cas"].orEmpty()
    private val uuid = ZhsBrushCodec.uuid(session.cookies["CASLOGC"])

    /** module 11 密钥；缺失时由 [ensureKey] 换一次，调用方负责写回会话。 */
    var studyKey: String = session.studyKey
        private set

    suspend fun ensureKey() = withContext(Dispatchers.IO) {
        if (studyKey.isBlank()) studyKey = ZhsLearnApi.studyKey(http)
    }

    /** `secret` → `videolist`，返回 `(lessonId, lessonVideoId) → 真实 videoId`。 */
    suspend fun realVideoIds(course: ZhsCourse): Map<Pair<Long, Long>, Long> = withContext(Dispatchers.IO) {
        val secret = course.secret
        if (secret.isBlank()) return@withContext emptyMap()
        ensureKey()
        val json = postJson(VIDEOLIST_URL, { date -> ZhsBrushCodec.videolistPayload(secret, date) }, "视频列表加载失败")
        val chapters = json.optJSONObject("data")?.optJSONArray("videoChapterDtos") ?: return@withContext emptyMap()
        val result = LinkedHashMap<Pair<Long, Long>, Long>()
        for (i in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(i) ?: continue
            val lessons = chapter.optJSONArray("videoLessons") ?: continue
            for (j in 0 until lessons.length()) {
                val lesson = lessons.optJSONObject(j) ?: continue
                val videos = lesson.optJSONArray("videoSmallLessons") ?: continue
                for (k in 0 until videos.length()) {
                    val video = videos.optJSONObject(k) ?: continue
                    val lessonId = ZhsCourseParser.optId(video.opt("lessonId")) ?: continue
                    val lessonVideoId = ZhsCourseParser.optId(video.opt("id")) ?: continue
                    val videoId = ZhsCourseParser.optId(video.opt("videoId")) ?: continue
                    if (videoId > 0) result[lessonId to lessonVideoId] = videoId
                }
            }
        }
        result
    }

    /** 本节服务器已学秒数：`lesson` 与 `lv` 两级取大。 */
    suspend fun studyTime(course: ZhsCourse, item: ZhsFlatItem): Int = withContext(Dispatchers.IO) {
        ensureKey()
        val json = postJson(
            QUERY_URL,
            { date -> ZhsLearnApi.progressPayload(listOf(item.lessonId), course.recruitId, videoIds(item), date) },
            "学习进度查询失败",
        )
        studyTimeOf(json, item)
    }

    /**
     * 刷一节。返回成功提交的弹题数；滑块验证抛 [ZhsCaptchaException]，
     * 登录失效抛 [ZhsErrorKind.EXPIRED]。
     */
    suspend fun brush(
        course: ZhsCourse,
        item: ZhsFlatItem,
        speed: ZhsSpeedController,
        onProgress: (Int, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Int = brushResolved(course, resolveVideoId(course, item), speed, onProgress, onLog)

    /**
     * 章节页的单节条目只带 lessonVideoId 兜底，这里补一次 `videolist` 的真实 videoId；
     * 批量条目已由 `flatItems` 映射过，命中短路不额外发请求。
     */
    private suspend fun resolveVideoId(course: ZhsCourse, item: ZhsFlatItem): ZhsFlatItem {
        if (item.lessonVideoId <= 0 || item.videoId != item.lessonVideoId) return item
        val map = try {
            realVideoIds(course)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return item
        }
        val real = map[item.lessonId to item.lessonVideoId] ?: return item
        return item.copy(videoId = real)
    }

    private suspend fun brushResolved(
        course: ZhsCourse,
        item: ZhsFlatItem,
        speed: ZhsSpeedController,
        onProgress: (Int, String) -> Unit,
        onLog: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        ensureKey()
        val pointer = postJson(
            POINTER_URL,
            { date -> ZhsBrushCodec.pointerPayload(item.lessonId, course.recruitId, course.courseId, item.lessonVideoId, date) },
            "弹题加载失败",
        )
        // 第一次 queryStuyInfo 会建立学习记录，失败不阻断刷课。
        bestEffort {
            postJson(
                QUERY_URL,
                { date -> ZhsLearnApi.progressPayload(listOf(item.lessonId), course.recruitId, videoIds(item), date) },
                "学习进度查询失败",
            )
        }
        val answered = answerPopups(course, item, pointer)
        val baseTime = studyTimeOf(
            postJson(
                QUERY_URL,
                { date -> ZhsLearnApi.progressPayload(listOf(item.lessonId), course.recruitId, videoIds(item), date) },
                "学习进度查询失败",
            ),
            item,
        )
        val zwsds = try {
            fetchZwsds(course, item)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            DEFAULT_ZWSDS
        }
        // warm 一次学习会话，和参考实现一致。
        bestEffort {
            postJson(
                POINTER_URL,
                { date -> ZhsBrushCodec.pointerPayload(item.lessonId, course.recruitId, course.courseId, item.lessonVideoId, date) },
                "弹题加载失败",
            )
        }

        var choice = speed.choice
        var plan = ZhsBrushPlanner.plan(item.sec, choice)
        onLog("⚡ 倍速模式: ${plan.label}")
        var index = 0
        while (index < plan.calls) {
            currentCoroutineContext().ensureActive()
            // 运行中改速：下一个上报前读取新档位，剩余请求按新倍率重排（参考实现 speed_cmd）。
            val next = speed.choice
            if (next != choice) {
                plan = ZhsBrushPlanner.replan(item.sec, index, plan, next)
                choice = next
                onLog("⚡ 切换倍速: ${plan.label}")
            }
            val sess = plan.sessionAt(index)
            val acc = if (baseTime > 0) baseTime + sess else sess
            val sdsew = ZhsBrushCodec.z(
                listOf(
                    course.recruitId, item.lessonId, item.lessonVideoId, item.videoId,
                    item.chapterId, 0, sess, acc, formatHms(sess), uuid,
                ),
            )
            val payloadDate = clock()
            val encrypted = ZhsCrypto.aesEncrypt(
                ZhsBrushCodec.brushPayload(
                    courseId = course.courseId,
                    ewssw = ZhsBrushCodec.ewssw(sess),
                    sdsew = sdsew,
                    zwsds = zwsds,
                    date = payloadDate,
                ),
                studyKey,
            )
            // 表单时间戳是独立的一次取时，和参考实现两次调用 time() 一致。
            val form = linkedMapOf("dateFormate" to clock().toString(), "secretStr" to encrypted)
            val cache = post(SAVE_CACHE_URL, form, "刷课请求失败")
            if (cache.body.contains(CAPTCHA_MARK)) throw ZhsCaptchaException()
            if (index == 0) {
                val gap = min(2_000L, plan.intervalMs)
                if (gap > 0) delay(gap)
            }
            val database = post(SAVE_DB_URL, form, "刷课请求失败")
            if (database.body.contains(CAPTCHA_MARK)) throw ZhsCaptchaException()
            onProgress(((index + 1) * 100 / plan.calls).coerceIn(0, 100), plan.label)
            delay(plan.intervalMs)
            index++
        }
        answered
    }

    private fun videoIds(item: ZhsFlatItem): List<Long> =
        if (item.lessonVideoId > 0) listOf(item.lessonVideoId) else emptyList()

    private fun studyTimeOf(json: JSONObject, item: ZhsFlatItem): Int {
        val data = json.optJSONObject("data") ?: return 0
        var time = data.optJSONObject("lesson")?.optJSONObject(item.lessonId.toString())
            ?.optInt("studyTotalTime", 0) ?: 0
        if (item.lessonVideoId > 0) {
            val video = data.optJSONObject("lv")?.optJSONObject(item.lessonVideoId.toString())
                ?.optInt("studyTotalTime", 0) ?: 0
            time = max(time, video)
        }
        return time
    }

    /** `prelearningNote` → `studiedLessonDto.id` → base64；没拿到时用参考实现的兜底值。 */
    private suspend fun fetchZwsds(course: ZhsCourse, item: ZhsFlatItem): String {
        val json = postJson(
            PRELEARNING_URL,
            { date ->
                ZhsBrushCodec.prelearningPayload(
                    courseId = course.courseId,
                    chapterId = item.chapterId,
                    lessonId = item.lessonId,
                    lessonVideoId = item.lessonVideoId,
                    recruitId = course.recruitId,
                    videoId = item.videoId,
                    date = date,
                )
            },
            "学习记录创建失败",
        )
        val id = json.optJSONObject("data")?.optJSONObject("studiedLessonDto")
            ?.let { ZhsCourseParser.optId(it.opt("id")) } ?: 0L
        return ZhsBrushCodec.zwsds(id) ?: DEFAULT_ZWSDS
    }

    /** 弹题：取题 → 选 `result==1` → 单题提交；单题失败不阻断。 */
    private suspend fun answerPopups(course: ZhsCourse, item: ZhsFlatItem, pointer: JSONObject): Int {
        val points = pointer.optJSONObject("data")?.optJSONArray("questionPoint") ?: return 0
        var answered = 0
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i) ?: continue
            val questionIds = point.optString("questionIds")
            if (questionIds.isBlank()) continue
            bestEffort {
                val exam = postJson(
                    POPUP_EXAM_URL,
                    { date ->
                        ZhsBrushCodec.popupExamPayload(
                            chapterId = item.chapterId,
                            lessonId = item.lessonId,
                            lessonVideoId = item.lessonVideoId,
                            questionIds = questionIds,
                            timeSec = point.optInt("timeSec", 0),
                            date = date,
                        )
                    },
                    "弹题加载失败",
                )
                val tests = exam.optJSONObject("data")?.optJSONArray("lessonTestQuestionUseInterfaceDtos")
                    ?: return@bestEffort
                for (j in 0 until tests.length()) {
                    val test = tests.optJSONObject(j)?.optJSONObject("testQuestion") ?: continue
                    val options = test.optJSONArray("questionOptions") ?: continue
                    for (k in 0 until options.length()) {
                        val option = options.optJSONObject(k) ?: continue
                        if (option.opt("result")?.toString() != "1") continue
                        val answerId = option.opt("id")?.toString() ?: continue
                        bestEffort {
                            postJson(
                                POPUP_ANSWER_URL,
                                { date ->
                                    ZhsBrushCodec.answerPayload(
                                        courseId = course.courseId,
                                        recruitId = course.recruitId,
                                        testQuestionId = ZhsCourseParser.optId(test.opt("questionId")) ?: 0L,
                                        lessonId = item.lessonId,
                                        lessonVideoId = item.lessonVideoId,
                                        answerId = answerId,
                                        date = date,
                                    )
                                },
                                "弹题提交失败",
                            )
                        }
                        answered++
                        break
                    }
                }
            }
        }
        return answered
    }

    /** 构造 `dateFormate` + `secretStr` 表单并 POST；加密用的时间戳与表单时间戳各取一次。 */
    private fun postJson(url: String, payload: (Long) -> String, fallback: String): JSONObject {
        val body = ZhsLearnApi.network({
            val encrypted = ZhsCrypto.aesEncrypt(payload(clock()), studyKey)
            val form = linkedMapOf("dateFormate" to clock().toString(), "secretStr" to encrypted)
            http.postForm(url, form, headers())
        }, fallback)
        check(body)
        ZhsLearnApi.rejectHtml(body.body)
        return runCatching { JSONObject(body.body) }.getOrElse {
            throw ZhsException("知到返回数据格式异常，请稍后重试", ZhsErrorKind.PARSE)
        }
    }

    private fun post(url: String, form: Map<String, String>, fallback: String): ZhsHttp.Response {
        val response = ZhsLearnApi.network({ http.postForm(url, form, headers()) }, fallback)
        check(response)
        return response
    }

    /** 401 / 认证失效 → 过期；403 是拦截，不能伪装成登录过期。 */
    private fun check(response: ZhsHttp.Response) {
        if (response.url.contains("passport.zhihuishu.com")) throw ZhsLearnApi.expired()
        if (response.code == 401 || zhsAuthRequired(response.body)) throw ZhsLearnApi.expired()
        if (response.code == 403) {
            throw ZhsException("请求被智慧树拦截，请稍后再试", ZhsErrorKind.SERVER)
        }
    }

    private fun headers(): Map<String, String> = buildMap {
        put("User-Agent", "Mozilla/5.0")
        put("Accept", "application/json")
        put("Origin", ORIGIN)
        put("Referer", "$ORIGIN/")
        if (auth.isNotBlank()) put("Authorization", auth)
    }

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun formatHms(totalSec: Int): String {
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    internal companion object {
        const val API = "https://studyservice-api.zhihuishu.com/gateway/t/v1"
        const val POINTER_URL = "$API/popupAnswer/loadVideoPointerInfo"
        const val POPUP_EXAM_URL = "$API/popupAnswer/lessonPopupExam"
        const val POPUP_ANSWER_URL = "$API/popupAnswer/batchSaveLessonPopupExamSaveAnswer"
        const val QUERY_URL = "$API/learning/queryStuyInfo"
        const val PRELEARNING_URL = "$API/learning/prelearningNote"
        const val VIDEOLIST_URL = "$API/learning/videolist"
        const val SAVE_CACHE_URL = "$API/learning/saveCacheIntervalTimeV2"
        const val SAVE_DB_URL = "$API/learning/saveDatabaseIntervalTimeV2"
        const val ORIGIN = "https://wisdom-mooc.zhihuishu.com"

        /** 服务端要求滑块验证的固定文案。 */
        const val CAPTCHA_MARK = "需要弹出滑块验证"

        /** 参考实现里 `zwsds_cache` 的兜底值。 */
        const val DEFAULT_ZWSDS = "MTI1MzM1Mjg3NTg="
    }
}