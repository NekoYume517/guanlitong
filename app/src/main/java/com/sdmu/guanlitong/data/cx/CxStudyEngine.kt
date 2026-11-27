package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.CancellationException

/**
 * 刷课执行器，对照老项目 `routes/courses.py` 的推进策略：
 *
 * 1. 扫描全课程章节，得到未通过任务点；某章网络重试耗尽没取到就记入
 *    「未取到的章节」，该课程此后一律按「部分失败」处理，不报完成；
 * 2. 第一轮逐个刷（video 与 audio 同等在队列里）；
 * 3. 连续 [reloginAfterConsecutiveFailures] 个任务点失败 → 主动重登一次（每轮最多一次）；
 * 4. 仍有失败、或有章节没取到 → 重新扫描状态，第二轮只刷仍失败的，顺带再试
 *    上次没取到的章节；
 * 5. 任务点是否完成只认服务端 `isPassed`，引擎不做本地判定。
 *
 * 风控（403）与登录失效（401）从 [CxStudyGateway] 上抛，引擎记录为 fatalError
 * 并结束，不重试风暴。取消通过协程取消传播。
 */
class CxStudyEngine(
    private val gateway: CxStudyGateway,
    private val reloginAfterConsecutiveFailures: Int = 2,
) {

    /**
     * @param onlyJobid 只刷指定任务点（单点模式不重扫、不第二轮）。
     */
    suspend fun run(
        course: CxCourse,
        onlyJobid: String? = null,
        onEvent: (CxStudyEvent) -> Unit = {},
    ): CxStudySummary {
        val passed = mutableSetOf<String>()
        val missed = mutableListOf<String>()
        var total = 0

        try {
            val firstScan = gateway.scan(course)
            missed += firstScan.missedChapters
            val scanned = firstScan.tasks.distinctBy { it.card.jobid }
            val first = if (onlyJobid == null) {
                scanned
            } else {
                scanned.filter { it.card.jobid == onlyJobid }
            }
            total = first.size
            onEvent(scannedEvent(1, first, firstScan.missedChapters))

            var consecutiveFailures = 0
            var reloginUsed = false
            first.forEachIndexed { index, task ->
                onEvent(CxStudyEvent.TaskStarted(1, index + 1, total, task))
                val outcome = gateway.brush(task) { progress ->
                    onEvent(CxStudyEvent.TaskProgress(1, index + 1, total, task, progress))
                }
                if (outcome.passed) {
                    passed += task.card.jobid
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    if (!reloginUsed && consecutiveFailures >= reloginAfterConsecutiveFailures) {
                        reloginUsed = true
                        consecutiveFailures = 0
                        onEvent(CxStudyEvent.Relogin("连续 $index 个任务点失败，重新登录后继续"))
                        gateway.relogin()
                    }
                }
                onEvent(
                    CxStudyEvent.TaskFinished(
                        1, index + 1, total, task, outcome.passed, outcome.message,
                    ),
                )
            }

            if (onlyJobid != null) {
                return finish(course, total, passed.size, missed, cancelled = false, error = null, onEvent)
            }
            if (missed.isEmpty() && passed.size >= total) {
                return finish(course, total, passed.size, missed, cancelled = false, error = null, onEvent)
            }

            // 有失败任务点，或上一轮有章节没取到（可能藏着未通过任务点）→ 重扫。
            val secondScan = gateway.scan(course)
            // 第二轮重新覆盖了全部章节，它的 missedChapters 才是当前真值：
            // 上一轮没取到、这一轮取到的章节就算恢复，必须从「部分失败」里移除。
            missed.clear()
            missed += secondScan.missedChapters
            val remaining = secondScan.tasks
                .distinctBy { it.card.jobid }
                .filter { !it.card.isPassed }
            if (remaining.isEmpty()) {
                // 仍没取到的章节（若有）由 missed 带进汇总，绝不算完成。
                return finish(
                    course, passed.size, passed.size, missed,
                    cancelled = false, error = null, onEvent,
                )
            }
            onEvent(scannedEvent(2, remaining, secondScan.missedChapters))

            remaining.forEachIndexed { index, task ->
                onEvent(CxStudyEvent.TaskStarted(2, index + 1, remaining.size, task))
                val outcome = gateway.brush(task) { progress ->
                    onEvent(
                        CxStudyEvent.TaskProgress(2, index + 1, remaining.size, task, progress),
                    )
                }
                if (outcome.passed) passed += task.card.jobid
                onEvent(
                    CxStudyEvent.TaskFinished(
                        2, index + 1, remaining.size, task, outcome.passed, outcome.message,
                    ),
                )
            }

            val finalTotal = passed.size + remaining.count { it.card.jobid !in passed }
            return finish(
                course = course,
                total = finalTotal,
                passed = passed.size,
                missed = missed,
                cancelled = false,
                error = null,
                onEvent = onEvent,
            )
        } catch (e: CancellationException) {
            return finish(course, total, passed.size, missed, cancelled = true, error = null, onEvent)
        } catch (e: CxException) {
            return finish(
                course = course,
                total = total,
                passed = passed.size,
                missed = missed,
                cancelled = false,
                error = e.message ?: "学习通请求失败",
                onEvent = onEvent,
                emitFinished = false,
            )
        }
    }

    private fun scannedEvent(
        round: Int,
        tasks: List<CxStudyTask>,
        missedChapters: List<String>,
    ): CxStudyEvent.Scanned = CxStudyEvent.Scanned(
        round = round,
        total = tasks.size,
        videoCount = tasks.count { it.card.type == "video" },
        audioCount = tasks.count { it.card.type == "audio" },
        missedChapters = missedChapters,
    )

    private fun finish(
        course: CxCourse,
        total: Int,
        passed: Int,
        missed: List<String>,
        cancelled: Boolean,
        error: String?,
        onEvent: (CxStudyEvent) -> Unit,
        emitFinished: Boolean = true,
    ): CxStudySummary {
        val summary = CxStudySummary(
            courseName = course.name,
            total = total,
            passed = passed.coerceAtMost(total),
            failed = (total - passed).coerceAtLeast(0),
            cancelled = cancelled,
            fatalError = error,
            missedChapters = missed,
        )
        if (emitFinished) onEvent(CxStudyEvent.Finished(summary))
        return summary
    }
}