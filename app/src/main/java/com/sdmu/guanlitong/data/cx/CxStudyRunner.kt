package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.delay

/** 刷课进度回调（供 UI 显示当前任务点与进度）。 */
data class CxStudyProgress(
    val card: CxTaskCard,
    val position: Int,
    val duration: Int,
) {
    val percent: Int
        get() = if (duration <= 0) 0 else (position * 100 / duration).coerceIn(0, 100)
}

/** 单个任务点的刷课结果。 */
data class CxStudyOutcome(
    val passed: Boolean,
    val message: String,
)

/**
 * 单个任务点的刷课时序，逐项对照老项目 `routes/courses.py` 的推进逻辑：
 * - 心跳间隔 [heartbeatMs]（默认 60s），每拍进度 +[speed]×间隔，等效 2 倍速；
 * - isdrag：3=开始、0=心跳、4=播完；任务点完成只认服务端 `isPassed`；
 * - 播完后按总时长 30% 宽限继续上报，仍不通过就是失败，绝不本地判「完成」；
 * - 弹题在开始时自动作答一次（旁路，不影响主流程）。
 *
 * 风控（403）与登录失效（401）以 [CxException] 上抛，由上层中止并说明原因。
 */
class CxStudyRunner(
    private val api: CxStudyApi,
    private val heartbeatMs: Long = 60_000,
    private val speed: Double = 2.0,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val onProgress: (CxStudyProgress) -> Unit = {},
) {

    suspend fun brush(task: CxStudyTask): CxStudyOutcome {
        val card = task.card
        val dtype = when (card.type) {
            "video" -> "Video"
            "audio" -> "Audio"
            else -> return CxStudyOutcome(false, "不支持的任务点类型：${card.type}")
        }
        if (card.objectId.isBlank()) return CxStudyOutcome(false, "任务点缺少 objectId")

        val course = task.course
        // 老项目顺序是 setlog → 500ms → studentstudyAjax → 1s → 任务点状态。
        // 信标失败（含风控挂起）不阻断；401 会在此上抛。
        api.sendSetlog(course, task.chapterId)
        pause(CHAPTER_GAP_MS)
        api.markChapterStudying(course, task.chapterId)
        pause(MARK_GAP_MS)

        val status = api.videoStatus(card.objectId)
        val duration = listOf(status.duration, card.attDuration ?: 0, card.duration)
            .firstOrNull { it > 0 } ?: DEFAULT_DURATION
        if (status.dtoken.isBlank()) {
            return CxStudyOutcome(false, "未拿到 dtoken，任务点无法上报")
        }

        push(course, card, 0, duration, CxStudyParams.ISDRAG_PLAY, status.dtoken, dtype)
        if (dtype == "Video") api.answerQuizzes(course, card)

        var passed = push(
            course, card, 0, duration, CxStudyParams.ISDRAG_PLAYING, status.dtoken, dtype,
        ).isPassed

        val step = ((heartbeatMs / 1000.0) * speed).toInt().coerceAtLeast(1)
        var position = 0
        var beats = 0
        val maxBeats = duration / step + 5
        while (!passed && position < duration && beats < maxBeats) {
            pause(heartbeatMs)
            beats++
            position = minOf(position + step, duration)
            passed = push(
                course, card, position, duration, CxStudyParams.ISDRAG_PLAYING,
                status.dtoken, dtype,
            ).isPassed
            onProgress(CxStudyProgress(card, position, duration))
        }

        if (!passed) {
            passed = push(
                course, card, duration, duration, CxStudyParams.ISDRAG_ENDED,
                status.dtoken, dtype,
            ).isPassed
        }

        // 宽限重试：老项目按总时长 30% 继续推进，仍不通过才算失败。
        val graceLimit = (duration * 0.3).toInt()
        var grace = 0
        while (!passed && grace < graceLimit) {
            pause(heartbeatMs)
            grace += step
            push(
                course, card, duration, duration, CxStudyParams.ISDRAG_PLAYING,
                status.dtoken, dtype,
            )
            passed = push(
                course, card, duration, duration, CxStudyParams.ISDRAG_ENDED,
                status.dtoken, dtype,
            ).isPassed
        }

        return CxStudyOutcome(
            passed = passed,
            message = if (passed) "已通过" else "服务端未标记完成（宽限重试后仍未通过）",
        )
    }

    private suspend fun push(
        course: CxStudyCourse,
        card: CxTaskCard,
        position: Int,
        duration: Int,
        isDrag: Int,
        dtoken: String,
        dtype: String,
    ): CxPushResult = api.pushProgress(
        course = course,
        card = card,
        current = position,
        duration = duration,
        isDrag = isDrag,
        dtoken = dtoken,
        dtype = dtype,
    )

    private companion object {
        const val DEFAULT_DURATION = 300
        const val CHAPTER_GAP_MS = 500L
        const val MARK_GAP_MS = 1_000L
    }
}