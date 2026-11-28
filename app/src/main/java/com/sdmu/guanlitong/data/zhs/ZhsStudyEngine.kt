package com.sdmu.guanlitong.data.zhs

import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * 刷课推进策略，对照参考实现 `brush_time_batch` / `brush_lesson`：
 *
 * - 批量（[run]）：按章节顺序刷未看完的视频；已看完（`sec>0 && studyTime>=sec`）与
 *   `watched` 的条目跳过；每节新增学时 = 刷后 `queryStuyInfo` − 刷前，负值按 0；
 * - 单节（[runItem]）：用户点哪节刷哪节，即使已看完也按点击执行（旧项目行为）；
 * - 目标学时（[targetSec] > 0）：全部刷完仍未达标，按视频时长升序反复刷，
 *   完整一轮零新增就停，避免空转；
 * - 滑块验证立即停止，返回 `captcha = true`；
 * - 倍速由 [ZhsSpeedController] 承载，运行中改档位会在下一个上报前生效。
 */

/** 刷课过程中某一节的状态，界面据此渲染每节实时进度。 */
enum class ZhsStudyStatus { RUNNING, DONE, CAPTCHA }

/** 刷课过程中的进度事件，界面直接展示。 */
data class ZhsStudyProgress(
    val index: Int,
    val total: Int,
    val name: String,
    /** 本节上报进度 0..100。 */
    val percent: Int,
    /** 本节的上报节奏说明（如「默认 · 6个请求」）。 */
    val speedLabel: String,
    /** 本节结束时的新增学时；进行中为 0。 */
    val addedSec: Int = 0,
    val status: ZhsStudyStatus = ZhsStudyStatus.RUNNING,
    val lessonId: Long = 0,
    val lessonVideoId: Long = 0,
    /** 0 = 阶段 A（按章节顺序）；>0 = 阶段 B 的第几轮（对应旧项目「第N轮」）。 */
    val round: Int = 0,
    /** 视频总时长（秒），用于进度页「已播/总时长」。 */
    val videoSec: Int = 0,
    /** 本节结束时的弹题作答数；进行中为 0。 */
    val answered: Int = 0,
) {
    val done: Boolean get() = status == ZhsStudyStatus.DONE
}

data class ZhsStudyOutcome(
    val brushedCount: Int,
    val addedSec: Int,
    /** 本次目标学时；0 表示「刷完所有未看完的」。 */
    val targetSec: Int,
    val captcha: Boolean,
    val answered: Int,
    /** 用户手动停止：当前这节已刷完，后续条目不再执行。 */
    val cancelled: Boolean = false,
)

/** 引擎依赖的网络能力；单测用假实现即可完全离线。 */
internal interface ZhsStudyGateway {
    suspend fun flatItems(course: ZhsCourse, chapters: List<ZhsChapter>): List<ZhsFlatItem>

    suspend fun studyTime(course: ZhsCourse, item: ZhsFlatItem): Int

    /** 刷一节，返回提交成功的弹题数；滑块验证抛 [ZhsCaptchaException]。 */
    suspend fun brushItem(
        course: ZhsCourse,
        item: ZhsFlatItem,
        speed: ZhsSpeedController,
        onProgress: (Int, String) -> Unit,
        onLog: (String) -> Unit,
    ): Int
}

/** 真实网关：videolist 映射 + study 协议客户端。 */
internal class ZhsStudyGatewayImpl(private val client: ZhsStudyClient) : ZhsStudyGateway {

    override suspend fun flatItems(course: ZhsCourse, chapters: List<ZhsChapter>): List<ZhsFlatItem> =
        ZhsLearnParser.flatItems(chapters, client.realVideoIds(course))

    override suspend fun studyTime(course: ZhsCourse, item: ZhsFlatItem): Int =
        client.studyTime(course, item)

    override suspend fun brushItem(
        course: ZhsCourse,
        item: ZhsFlatItem,
        speed: ZhsSpeedController,
        onProgress: (Int, String) -> Unit,
        onLog: (String) -> Unit,
    ): Int = client.brush(course, item, speed, onProgress, onLog)
}

internal class ZhsStudyEngine(private val gateway: ZhsStudyGateway) {

    /** 批量刷课：跳过已看完的，[targetSec] > 0 时刷到达标为止。 */
    suspend fun run(
        course: ZhsCourse,
        chapters: List<ZhsChapter>,
        speed: ZhsSpeedController,
        targetSec: Int = 0,
        onProgress: (ZhsStudyProgress) -> Unit = {},
        onLog: (String) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): ZhsStudyOutcome = execute(
        course = course,
        items = gateway.flatItems(course, chapters),
        speed = speed,
        targetSec = targetSec,
        skipFinished = true,
        onProgress = onProgress,
        onLog = onLog,
        shouldStop = shouldStop,
    )

    /** 单节刷课：不跳过已完成，按用户点击执行。 */
    suspend fun runItem(
        course: ZhsCourse,
        item: ZhsFlatItem,
        speed: ZhsSpeedController,
        onProgress: (ZhsStudyProgress) -> Unit = {},
        onLog: (String) -> Unit = {},
    ): ZhsStudyOutcome = execute(
        course = course,
        items = listOf(item),
        speed = speed,
        targetSec = 0,
        skipFinished = false,
        onProgress = onProgress,
        onLog = onLog,
    )

    private suspend fun execute(
        course: ZhsCourse,
        items: List<ZhsFlatItem>,
        speed: ZhsSpeedController,
        targetSec: Int,
        skipFinished: Boolean,
        onProgress: (ZhsStudyProgress) -> Unit,
        onLog: (String) -> Unit,
        shouldStop: () -> Boolean = { false },
    ): ZhsStudyOutcome {
        val total = items.size
        var brushed = 0
        var added = 0
        var answered = 0
        var cancelled = false
        val targetText = if (targetSec > 0) " / ${targetSec / 60}分" else ""

        for ((index, item) in items.withIndex()) {
            if (skipFinished && item.finished) {
                onLog(
                    if (item.watched) {
                        "⏭ 跳过已看完: ${item.name}"
                    } else {
                        "⏭ 已看满: ${item.name} (${item.studyTime}s/${item.sec}s)"
                    },
                )
                continue
            }
            if (shouldStop()) {
                cancelled = true
                break
            }
            if (targetSec > 0 && added >= targetSec) break
            onLog("▶ [${index + 1}/$total] ${item.name} (已看 ${item.studyTime}s/${if (item.sec > 0) item.sec else "?"}s)")
            val before = gateway.studyTime(course, item)
            onProgress(
                ZhsStudyProgress(
                    index + 1, total, item.name, 0, "", 0,
                    ZhsStudyStatus.RUNNING, item.lessonId, item.lessonVideoId,
                    videoSec = item.sec,
                ),
            )
            val count = try {
                gateway.brushItem(
                    course,
                    item,
                    speed,
                    { percent, label ->
                        onProgress(
                            ZhsStudyProgress(
                                index + 1, total, item.name, percent, label, 0,
                                ZhsStudyStatus.RUNNING, item.lessonId, item.lessonVideoId,
                                videoSec = item.sec,
                            ),
                        )
                    },
                    onLog,
                )
            } catch (e: ZhsCaptchaException) {
                onProgress(
                    ZhsStudyProgress(
                        index + 1, total, item.name, 0, "", 0,
                        ZhsStudyStatus.CAPTCHA, item.lessonId, item.lessonVideoId,
                        videoSec = item.sec,
                    ),
                )
                onLog("🛑 滑块验证, 批量停止! 请前往智慧树APP验证")
                return ZhsStudyOutcome(brushed, added, targetSec, captcha = true, answered)
            }
            answered += count
            val gained = max(0, gateway.studyTime(course, item) - before)
            added += gained
            brushed++
            onProgress(
                ZhsStudyProgress(
                    index + 1, total, item.name, 100, "", gained,
                    ZhsStudyStatus.DONE, item.lessonId, item.lessonVideoId,
                    videoSec = item.sec, answered = count,
                ),
            )
            onLog("✅ 本节 +${gained}s, 累计 ${formatSpan(added)}$targetText")
        }

        if (!cancelled && targetSec > 0 && added < targetSec && items.isNotEmpty()) {
            val pool = items.sortedBy { it.sec }
            onLog("🔁 全部视频已刷一遍仍未达标, 挑最短视频反复刷 (候选 ${pool.size} 个)")
            var cycle = 0
            while (added < targetSec) {
                if (shouldStop()) {
                    cancelled = true
                    break
                }
                cycle++
                var cycleGained = 0
                for (item in pool) {
                    if (added >= targetSec) break
                    if (shouldStop()) {
                        cancelled = true
                        break
                    }
                    onLog("🔁 第${cycle}轮: ${item.name} (${item.sec}s)")
                    val before = gateway.studyTime(course, item)
                    onProgress(
                        ZhsStudyProgress(
                            cycle, pool.size, item.name, 0, "", 0,
                            ZhsStudyStatus.RUNNING, item.lessonId, item.lessonVideoId,
                            round = cycle, videoSec = item.sec,
                        ),
                    )
                    val count = try {
                        gateway.brushItem(
                            course,
                            item,
                            speed,
                            { percent, speedLabel ->
                                onProgress(
                                    ZhsStudyProgress(
                                        cycle, pool.size, item.name, percent, speedLabel, 0,
                                        ZhsStudyStatus.RUNNING, item.lessonId, item.lessonVideoId,
                                        round = cycle, videoSec = item.sec,
                                    ),
                                )
                            },
                            onLog,
                        )
                    } catch (e: ZhsCaptchaException) {
                        onProgress(
                            ZhsStudyProgress(
                                cycle, pool.size, item.name, 0, "", 0,
                                ZhsStudyStatus.CAPTCHA, item.lessonId, item.lessonVideoId,
                                round = cycle, videoSec = item.sec,
                            ),
                        )
                        onLog("🛑 滑块验证, 批量停止! 请前往智慧树APP验证")
                        return ZhsStudyOutcome(brushed, added, targetSec, captcha = true, answered)
                    }
                    answered += count
                    val gained = max(0, gateway.studyTime(course, item) - before)
                    added += gained
                    cycleGained += gained
                    brushed++
                    onProgress(
                        ZhsStudyProgress(
                            cycle, pool.size, item.name, 100, "", gained,
                            ZhsStudyStatus.DONE, item.lessonId, item.lessonVideoId,
                            round = cycle, videoSec = item.sec, answered = count,
                        ),
                    )
                    onLog("✅ 本节 +${gained}s, 累计 ${formatSpan(added)}$targetText")
                }
                if (cancelled) break
                if (cycleGained <= 0) {
                    onLog("⚠ 一整轮服务器未累计任何新增时长, 停止避免空转")
                    break
                }
                if (added < targetSec) delay(2_000)
            }
        }

        return ZhsStudyOutcome(brushed, added, targetSec, captcha = false, answered, cancelled = cancelled)
    }
}
