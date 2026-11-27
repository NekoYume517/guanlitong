package com.sdmu.guanlitong.data.cx

/**
 * 一次课程扫描的结果。
 *
 * [missedChapters] 非空表示有子章节因为网络重试耗尽没取到——这些章节里的任务点
 * 完全未知，因此这门课**不能**被判定为「全部完成」，即使已扫到的都通过了。
 */
data class CxScanResult(
    val tasks: List<CxStudyTask> = emptyList(),
    val missedChapters: List<String> = emptyList(),
) {
    val partial: Boolean get() = missedChapters.isNotEmpty()
}

/** 刷课执行器的外部依赖抽象；真实实现走 [CxRemoteStudyGateway]，单测用假实现。 */
interface CxStudyGateway {

    /**
     * 扫描一门课程的全部子章节，返回未通过的音视频任务点（已带课程上下文）。
     * 单章网络失败记入 [CxScanResult.missedChapters]，不中断其余章节。
     */
    suspend fun scan(course: CxCourse): CxScanResult

    /** 刷一个任务点；风控/登录失效以 [CxException] 上抛。 */
    suspend fun brush(
        task: CxStudyTask,
        onProgress: (CxStudyProgress) -> Unit,
    ): CxStudyOutcome

    /** 主动重登一次（连续失败时调用）。 */
    suspend fun relogin()
}

/** 执行器事件，供前台服务/UI 显示进度。 */
sealed interface CxStudyEvent {
    data class Scanned(
        val round: Int,
        val total: Int,
        val videoCount: Int = 0,
        val audioCount: Int = 0,
        val missedChapters: List<String> = emptyList(),
    ) : CxStudyEvent
    data class TaskStarted(
        val round: Int,
        val index: Int,
        val total: Int,
        val task: CxStudyTask,
    ) : CxStudyEvent

    data class TaskProgress(
        val round: Int,
        val index: Int,
        val total: Int,
        val task: CxStudyTask,
        val progress: CxStudyProgress,
    ) : CxStudyEvent

    data class TaskFinished(
        val round: Int,
        val index: Int,
        val total: Int,
        val task: CxStudyTask,
        val passed: Boolean,
        val message: String,
    ) : CxStudyEvent

    data class Relogin(val reason: String) : CxStudyEvent
    data class Finished(val summary: CxStudySummary) : CxStudyEvent
}

/** 一次刷课任务的汇总。 */
data class CxStudySummary(
    val courseName: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val cancelled: Boolean,
    val fatalError: String? = null,
    /** 网络重试耗尽、始终没取到的子章节；非空即「部分失败」，绝不算完成。 */
    val missedChapters: List<String> = emptyList(),
) {
    val partial: Boolean get() = missedChapters.isNotEmpty()

    val allPassed: Boolean get() = failed == 0 && fatalError == null && !partial
}