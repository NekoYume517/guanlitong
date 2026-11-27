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

    internal fun update(transform: (CxTaskState) -> CxTaskState) {
        _state.value = transform(_state.value)
    }

    internal fun reset() {
        _state.value = CxTaskState()
    }

    internal fun start(courseName: String, phase: String) {
        _state.value = CxTaskState(
            running = true,
            courseName = courseName,
            phase = phase,
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
    }
}