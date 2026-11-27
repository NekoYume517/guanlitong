package com.sdmu.guanlitong.service

import android.content.Context
import com.sdmu.guanlitong.data.cx.CxSignStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 自动签到的运行状态（与刷课的 [CxTaskStatus] 分开，互不覆盖）。 */
data class CxSignRuntimeState(
    val running: Boolean = false,
    val lastMessage: String = "",
    /** 最近一次有结果通知的时间；没有结果时为 0。 */
    val lastNotifiedAt: Long = 0,
)

object CxSignStatus {

    private val _state = MutableStateFlow(CxSignRuntimeState())
    val state: StateFlow<CxSignRuntimeState> = _state.asStateFlow()

    internal fun start() {
        _state.value = CxSignRuntimeState(running = true)
    }

    internal fun pulse(notices: Int) {
        if (notices <= 0) return
        _state.value = _state.value.copy(lastNotifiedAt = System.currentTimeMillis())
    }

    internal fun finish(message: String) {
        _state.value = _state.value.copy(running = false, lastMessage = message)
    }
}

/**
 * 进程被杀后的自动恢复：实时提醒还开着、但当前进程里轮询没在跑，就重启前台服务。
 * 服务没设 START_STICKY，所以 App 启动、进学习通页面时各检查一次；
 * [CxTaskService] 内部有启停去重，多次请求不会重复开轮询。
 */
object CxSignRecovery {
    fun ensureRunning(context: Context) {
        if (!CxSignStore(context).settings().monitorEnabled) return
        // 任何恢复入口都顺手把心跳 / WorkManager 保活链接上。
        CxKeepAlive.enable(context)
        if (CxSignStatus.state.value.running) return
        CxTaskService.startSign(context)
    }
}