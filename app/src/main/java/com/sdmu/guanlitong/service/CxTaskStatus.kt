package com.sdmu.guanlitong.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 前台任务（刷课 / 签到）的实时状态，服务写、UI 读。 */
data class CxTaskState(
    val running: Boolean = false,
    val courseName: String = "",
    val phase: String = "",
    val currentIndex: Int = 0,
    val total: Int = 0,
    val currentTask: String = "",
    val position: Int = 0,
    val duration: Int = 0,
    val message: String = "",
    val fatalError: String? = null,
    /** 非空表示「部分失败」：有章节没取到，结果不代表课程真实状态。 */
    val incomplete: String? = null,
) {
    val taskPercent: Int
        get() = if (duration <= 0) 0 else (position * 100 / duration).coerceIn(0, 100)

    val overallPercent: Int
        get() = if (total <= 0) 0 else ((currentIndex - 1).coerceAtLeast(0) * 100 / total)
}

object CxTaskStatus {

    private val _state = MutableStateFlow(CxTaskState())
    val state: StateFlow<CxTaskState> = _state.asStateFlow()

    private var startedAt = 0L

    internal fun update(transform: (CxTaskState) -> CxTaskState) {
        _state.value = transform(_state.value)
        syncRegistry()
    }

    internal fun reset() {
        _state.value = CxTaskState()
        BrushTaskRegistry.unregister(REGISTRY_ID)
    }

    internal fun start(courseName: String, phase: String) {
        startedAt = System.currentTimeMillis()
        _state.value = CxTaskState(
            running = true,
            courseName = courseName,
            phase = phase,
        )
        syncRegistry()
    }

    /** 学习通章节刷课同步到共享任务表，浮球与知到任务一起展示；结束即移除。 */
    private fun syncRegistry() {
        val state = _state.value
        if (!state.running) {
            BrushTaskRegistry.unregister(REGISTRY_ID)
            return
        }
        BrushTaskRegistry.register(
            BrushTaskSnapshot(
                id = REGISTRY_ID,
                source = BrushTaskSource.CX_STUDY,
                title = state.courseName,
                subtitle = state.currentTask.ifEmpty { state.phase },
                progress = if (state.total > 0) {
                    state.overallPercent / 100f
                } else {
                    state.taskPercent / 100f
                },
                statusText = if (state.total > 0) {
                    "${state.currentIndex}/${state.total}"
                } else {
                    state.currentTask
                },
                running = true,
                startedAt = startedAt,
                route = BrushTaskRoutes.CX_STUDY,
            ),
        )
    }

    internal fun finish(
        message: String,
        fatalError: String? = null,
        incomplete: String? = null,
    ) {
        _state.value = _state.value.copy(
            running = false,
            phase = "",
            currentTask = "",
            message = message,
            fatalError = fatalError,
            incomplete = incomplete,
        )
        BrushTaskRegistry.unregister(REGISTRY_ID)
    }

    private const val REGISTRY_ID = "cx.study"
}