package com.sdmu.guanlitong.data.zhs

import org.json.JSONObject

/**
 * 章节树与学习进度的解析、合并（纯函数，JVM 单测直接跑）。
 *
 * 结构对照参考实现 `get_chapter_list` / `get_study_progress`：
 * - 章节接口 `chapterOrLesson.clList[]` → `lessonTempDtos[]`；
 * - 有 `lessonVideoTempDtos[]` 的是三级课（章 → 节 → 视频小节），
 *   否则 lesson 自带 `videoId` 的是两级课，两者都没有就是无视频的节；
 * - 进度接口 `data.lesson[lessonId]` / `data.lv[lessonVideoId]`
 *   给出 `studyTotalTime`（已学秒数）与 `watchState`（1 = 已完成），
 *   按 id 合并回章节树。
 */
internal object ZhsLearnParser {

    /** 没有章节结构时，这些提示不算错误。 */
    private val SUCCESS_MESSAGES = setOf("ok", "success", "成功", "操作成功", "请求成功")

    /** 进度接口里的一个视频条目。 */
    internal data class ProgressItem(val studyTotalTime: Int, val watchState: Int)

    internal data class Progress(
        val lessons: Map<Long, ProgressItem> = emptyMap(),
        val videos: Map<Long, ProgressItem> = emptyMap(),
    )

    fun parseChapters(body: String): List<ZhsChapter> {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ZhsException("章节数据格式异常，请稍后重试", ZhsErrorKind.PARSE)
        val root = json.optJSONObject("chapterOrLesson")
        if (root == null) {
            // 有错误提示按错误处理；「ok / 成功」这类占位提示视为没有章节。
            ZhsCourseParser.extractMessage(json)
                ?.takeIf { it.lowercase() !in SUCCESS_MESSAGES }
                ?.let { throw ZhsException(it, ZhsErrorKind.PARSE) }
            return emptyList()
        }
        val list = root.optJSONArray("clList") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            list.optJSONObject(index)?.let(::chapterOf)
        }
    }

    internal fun chapterOf(raw: JSONObject): ZhsChapter? {
        val name = text(raw, "chapterName") ?: "未命名章节"
        val id = ZhsCourseParser.optId(raw.opt("chapterId")) ?: 0L
        val lessons = raw.optJSONArray("lessonTempDtos")
        val parsed = if (lessons == null) emptyList() else (0 until lessons.length()).mapNotNull { index ->
            lessons.optJSONObject(index)?.let(::lessonOf)
        }
        return ZhsChapter(name = name, id = id, lessons = parsed)
    }

    internal fun lessonOf(raw: JSONObject): ZhsLesson? {
        val name = text(raw, "lessonName") ?: "未命名小节"
        val lessonId = ZhsCourseParser.optId(raw.opt("lessonId")) ?: 0L
        val subs = raw.optJSONArray("lessonVideoTempDtos")
        if (subs != null && subs.length() > 0) {
            val videos = (0 until subs.length()).mapNotNull { index ->
                subs.optJSONObject(index)?.let(::videoOf)
            }
            return ZhsLesson(
                name = name,
                lessonId = lessonId,
                depth = 3,
                videos = videos,
            )
        }
        val videoId = ZhsCourseParser.optId(raw.opt("videoId")) ?: 0L
        if (videoId > 0) {
            return ZhsLesson(
                name = name,
                lessonId = lessonId,
                depth = 2,
                videoId = videoId,
                sec = int(raw, "lessonSec"),
                watched = isFinished(raw.opt("watchStatus")),
            )
        }
        return ZhsLesson(name = name, lessonId = lessonId, depth = 2, noVideo = true)
    }

    internal fun videoOf(raw: JSONObject): ZhsVideo = ZhsVideo(
        name = text(raw, "lessonVideoName") ?: "未命名视频",
        lessonVideoId = ZhsCourseParser.optId(raw.opt("lessonVideoId")) ?: 0L,
        sec = int(raw, "lessonVideoSec"),
        watched = isFinished(raw.opt("watchStatus")),
        studyTime = 0,
    )

    /** `data.lesson` / `data.lv` → 按 id 索引的进度表。 */
    fun parseProgress(body: String): Progress {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ZhsException("学习进度格式异常，请稍后重试", ZhsErrorKind.PARSE)
        val data = json.optJSONObject("data") ?: return Progress()
        return Progress(
            lessons = items(data.optJSONObject("lesson")),
            videos = items(data.optJSONObject("lv")),
        )
    }

    private fun items(raw: JSONObject?): Map<Long, ProgressItem> {
        if (raw == null) return emptyMap()
        val result = LinkedHashMap<Long, ProgressItem>()
        for (key in raw.keys()) {
            val item = raw.optJSONObject(key) ?: continue
            val id = key.toLongOrNull() ?: continue
            result[id] = ProgressItem(
                studyTotalTime = int(item, "studyTotalTime"),
                watchState = number(item.opt("watchState"))?.toInt() ?: 0,
            )
        }
        return result
    }

    /**
     * 把进度合并回章节树：两级课用 lessonId 对齐 `lesson`，三级课用 lessonVideoId
     * 对齐 `lv`。合并后的 [ZhsLesson.studyTime] / [ZhsVideo.studyTime] 不超过视频时长。
     */
    fun merge(chapters: List<ZhsChapter>, progress: Progress): List<ZhsChapter> = chapters.map { chapter ->
        chapter.copy(
            lessons = chapter.lessons.map { lesson ->
                if (lesson.depth == 3) {
                    lesson.copy(
                        videos = lesson.videos.map { video ->
                            val item = progress.videos[video.lessonVideoId]
                            if (item == null) video else video.copy(
                                studyTime = item.studyTotalTime.coerceIn(0, video.sec.coerceAtLeast(0)),
                                watched = item.watchState == 1,
                            )
                        },
                    )
                } else {
                    val item = progress.lessons[lesson.lessonId]
                    if (item == null) lesson else lesson.copy(
                        studyTime = item.studyTotalTime.coerceIn(0, lesson.sec.coerceAtLeast(0)),
                        watched = item.watchState == 1,
                    )
                }
            },
        )
    }

    /** 汇总「已看 X/Y 节」「已学 a/b」。无视频的节不计。 */
    fun summary(chapters: List<ZhsChapter>): ZhsSummary {
        var total = 0
        var watched = 0
        var totalSec = 0
        var watchedSec = 0
        for (chapter in chapters) {
            for (lesson in chapter.lessons) {
                if (lesson.depth == 3) {
                    for (video in lesson.videos) {
                        total++
                        totalSec += video.sec.coerceAtLeast(0)
                        if (video.watched) watched++
                        watchedSec += video.studyTime.coerceIn(0, video.sec.coerceAtLeast(0))
                    }
                } else if (!lesson.noVideo) {
                    total++
                    totalSec += lesson.sec.coerceAtLeast(0)
                    if (lesson.watched) watched++
                    watchedSec += lesson.studyTime.coerceIn(0, lesson.sec.coerceAtLeast(0))
                }
            }
        }
        return ZhsSummary(
            totalVideos = total,
            watchedVideos = watched,
            totalSec = totalSec,
            watchedSec = watchedSec,
        )
    }

    internal fun text(raw: JSONObject, key: String): String? =
        raw.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

    /** 数字或数字字符串 → Int；其余（含缺失）按 0。 */
    internal fun int(raw: JSONObject, key: String): Int = number(raw.opt(key))?.toInt() ?: 0

    private fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        is Boolean -> if (value) 1.0 else 0.0
        else -> null
    }

    private fun isFinished(value: Any?): Boolean = number(value)?.toInt() == 1

    /** 章节树里所有 lessonId / lessonVideoId，供进度接口使用（去重、只收正整数）。 */
    fun lessonIds(chapters: List<ZhsChapter>): List<Long> =
        chapters.flatMap { it.lessons }
            .map { it.lessonId }
            .filter { it > 0 }
            .distinct()

    fun videoIds(chapters: List<ZhsChapter>): List<Long> =
        chapters.flatMap { it.lessons }
            .flatMap { it.videos }
            .map { it.lessonVideoId }
            .filter { it > 0 }
            .distinct()

    /**
     * 章节树 → 刷课扁平条目，规则对照 `brush_time_batch`：
     * - 三级课每个 `lessonVideoTempDtos` 小节一条，[ZhsFlatItem.videoId] 取
     *   `videolist` 映射（`(lessonId, lessonVideoId) → 真实 videoId`），没有就用
     *   lessonVideoId；
     * - 两级课一条，`lessonVideoId = 0`，videoId 用 lesson 自带值；
     * - 无视频的节、`lessonId` 或 `videoId` 为 0 的条目直接丢弃。
     */
    fun flatItems(
        chapters: List<ZhsChapter>,
        realVideoIds: Map<Pair<Long, Long>, Long> = emptyMap(),
    ): List<ZhsFlatItem> {
        val items = ArrayList<ZhsFlatItem>()
        for (chapter in chapters) {
            for (lesson in chapter.lessons) {
                if (lesson.noVideo) continue
                if (lesson.depth == 3) {
                    for (video in lesson.videos) {
                        val mapped = realVideoIds[lesson.lessonId to video.lessonVideoId] ?: 0L
                        items += ZhsFlatItem(
                            chapterId = chapter.id,
                            lessonId = lesson.lessonId,
                            lessonVideoId = video.lessonVideoId,
                            videoId = mapped.takeIf { it > 0 } ?: video.lessonVideoId,
                            sec = video.sec,
                            name = video.name,
                            studyTime = video.studyTime,
                            watched = video.watched,
                        )
                    }
                } else if (lesson.videoId > 0) {
                    items += ZhsFlatItem(
                        chapterId = chapter.id,
                        lessonId = lesson.lessonId,
                        lessonVideoId = 0,
                        videoId = lesson.videoId,
                        sec = lesson.sec,
                        name = lesson.name,
                        studyTime = lesson.studyTime,
                        watched = lesson.watched,
                    )
                }
            }
        }
        return items.filter { it.lessonId > 0 && it.videoId > 0 }
    }
}