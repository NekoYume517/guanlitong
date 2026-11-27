package com.sdmu.guanlitong.data.cx

/** 课程中间页（stucoursemiddle）里携带的学习参数。 */
data class CxCourseDetail(
    val enc: String = "",
    val cpi: String = "",
    val fid: String = "",
)

/** 父章节（章）。 */
data class CxChapterGroup(
    val name: String,
    val children: List<CxChapterChild>,
)

/** 子章节（节），对应一个 knowledgeId。 */
data class CxChapterChild(
    val chapterId: String,
    val name: String,
)

/**
 * 章节内的任务点卡片，字段与 super star `mArg.attachments[i]` 一一对应。
 *
 * [type] 已转小写（video / audio / work / document …）。
 * [attDuration] / [attDurationEnc] 为服务端签发的观看时长凭证，上报时需原样回传。
 */
data class CxTaskCard(
    val chapterId: String,
    val jobid: String,
    val objectId: String,
    val mid: String,
    val type: String,
    val name: String,
    val isPassed: Boolean,
    val duration: Int,
    val attDuration: Int?,
    val attDurationEnc: String,
    val begins: Int,
    val ends: Int,
    val otherInfo: String,
    val reportUrl: String,
)

/** 自动刷课只覆盖音视频任务点；文档、作业、测验等一律手动处理。 */
val CxTaskCard.isBrushable: Boolean
    get() = type == "video" || type == "audio"

/**
 * 扫描与 UI 统计共用的「待刷队列」口径：未通过、且在自动刷课范围内。
 * 扫描时按它建队列，详情页按它显示待刷数量——两处口径不允许各写一份。
 */
fun List<CxTaskCard>.pendingBrushableTasks(): List<CxTaskCard> =
    filter { !it.isPassed && it.isBrushable }

/** 超星 mArg 里可能出现的内部类型标识；它们不能当标题展示。 */
private val TYPE_IDENTIFIERS = setOf(
    "video", "audio", "document", "doc",
    "work", "workid", "exam", "examid", "quiz",
    "read", "readtask", "readspeed", "insertvideo", "insertaudio", "live",
)

/**
 * 任务点标题：超星的 `property.name` 有时直接就是类型标识（作业卡片常是 `workid`），
 * 这种「名称」不是真的标题，退回类型文案显示。
 */
val CxTaskCard.displayName: String
    get() = name.trim()
        .takeIf { it.isNotEmpty() && it.lowercase() !in TYPE_IDENTIFIERS }
        ?: cxTypeLabel(type)

/**
 * 任务点类型的界面文案。超星 mArg 里的 `type` 是内部标识（`workid`/`examid` 这类），
 * 绝不能原样吐到界面上；兜底统一显示「任务点」。
 */
fun cxTypeLabel(type: String): String = when (type) {
    "video" -> "视频"
    "audio" -> "音频"
    "document" -> "文档"
    "work", "workid" -> "作业"
    "exam", "examid" -> "考试"
    "quiz" -> "测验"
    "read", "readtask", "readspeed" -> "阅读"
    else -> "任务点"
}

/** 单页 knowledge/cards 的解析结果。 */
data class CxChapterPage(
    val cards: List<CxTaskCard>,
    val studyEnc: String = "",
)

/** 一门课程刷课所需的全部上下文。 */
data class CxStudyCourse(
    val courseId: String,
    val clazzId: String,
    val personId: String,
    val name: String,
    val cpi: String,
    val enc: String,
)

/** 待刷任务点（带上所属章节与课程上下文）。 */
data class CxStudyTask(
    val course: CxStudyCourse,
    val chapterId: String,
    val chapterName: String,
    val card: CxTaskCard,
)