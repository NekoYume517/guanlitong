package com.sdmu.guanlitong.data.zhs

/**
 * 课程详情「章节」tab 的数据结构，对照参考实现 `get_study_progress` 的输出：
 * 章 → 节 → 视频。两级课（lesson 自带 videoId）只有一层节；三级课多一层视频小节。
 *
 * 进度字段来自 `queryStuyInfo`：`studyTotalTime` 是服务器记录的已学秒数，
 * `watchState == 1` 表示该视频已完成。章节结构本身用 `watchStatus` 标记完成，
 * 合并进度时以 `queryStuyInfo` 为准。
 */
data class ZhsChapter(
    val name: String,
    val id: Long,
    val lessons: List<ZhsLesson>,
)

data class ZhsLesson(
    val name: String,
    val lessonId: Long,
    /** 2 = 章 → 视频；3 = 章 → 节 → 视频小节。 */
    val depth: Int,
    /** 两级课的 videoId；三级课在 [videos] 里。 */
    val videoId: Long = 0L,
    val sec: Int = 0,
    val watched: Boolean = false,
    val studyTime: Int = 0,
    /** 这节没有视频（讨论、作业等），界面上只显示名字。 */
    val noVideo: Boolean = false,
    val videos: List<ZhsVideo> = emptyList(),
)

data class ZhsVideo(
    val name: String,
    val lessonVideoId: Long,
    val sec: Int,
    val watched: Boolean,
    val studyTime: Int,
)

/**
 * 刷课用的扁平条目（对照 `brush_time_batch` 的 flat 构建）。
 *
 * - 三级课每条对应一个视频小节，[videoId] 优先用 `videolist` 的真实 videoId，
 *   查不到时回落为 [lessonVideoId]；
 * - 两级课一条，[lessonVideoId] 固定 0，[videoId] 是 lesson 自带的 videoId。
 */
data class ZhsFlatItem(
    val chapterId: Long,
    val lessonId: Long,
    val lessonVideoId: Long,
    val videoId: Long,
    val sec: Int,
    val name: String,
    val studyTime: Int,
    val watched: Boolean,
) {
    /** 已看完或已看满（`sec>0 && studyTime>=sec`）的条目批量刷课时跳过。 */
    val finished: Boolean
        get() = watched || (sec > 0 && studyTime >= sec)
}

/** 章节树的汇总：只统计视频单元，无视频的节不计入。 */
data class ZhsSummary(
    val totalVideos: Int,
    val watchedVideos: Int,
    val totalSec: Int,
    val watchedSec: Int,
) {
    /** 0..100；没有视频时为 0。 */
    val percent: Int
        get() = if (totalSec <= 0) 0 else ((watchedSec * 100L) / totalSec).toInt().coerceIn(0, 100)
}

/** 一次课程详情（章节树 + 进度汇总）。 */
data class ZhsCourseDetail(
    val chapters: List<ZhsChapter>,
    val summary: ZhsSummary,
)