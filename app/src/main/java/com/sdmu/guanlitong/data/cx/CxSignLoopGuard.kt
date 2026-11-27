package com.sdmu.guanlitong.data.cx

/**
 * 签到轮询的启停去重。
 *
 * App 启动、课程页、设置页都会请求「确保监控在跑」，同一时刻只允许一条轮询协程：
 * [begin] 只在没人跑时返回 true，[end] 由轮询协程退出时（自然结束/被取消）调用。
 * 纯 JVM 逻辑，服务持有一个实例，单测可直接覆盖。
 */
class CxSignLoopGuard {

    @Volatile
    private var running = false

    /** 请求开始一轮；已经在跑时返回 false，调用方不要再启动协程。 */
    @Synchronized
    fun begin(): Boolean {
        if (running) return false
        running = true
        return true
    }

    /** 轮询协程退出时调用（放在 finally 里），允许下一次 [begin]。 */
    @Synchronized
    fun end() {
        running = false
    }

    @Synchronized
    fun isRunning(): Boolean = running
}