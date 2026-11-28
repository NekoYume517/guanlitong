package com.sdmu.guanlitong.service

import android.content.Context
import android.os.PowerManager

/**
 * 刷课任务运行期间的 CPU 部分唤醒锁。
 *
 * 前台服务只保证进程优先级，**不保证 CPU 不休眠**：息屏/进 Doze 后 `delay` 不推进、
 * 联网被推迟，任务看起来就「不动」了。这里只在任务真正运行的那段时间持有
 * [PowerManager.PARTIAL_WAKE_LOCK]，结束、取消、异常、服务销毁都释放。
 *
 * 两条约束：
 * - `setReferenceCounted(false)` + 内部 `held` 标记，重复 acquire/release 都无害，
 *   不会出现「get 一次、release 两次」的引用计数崩溃；
 * - acquire 一定带超时，作为异常路径的兜底；正常任务时长远短于 [TIMEOUT_MS]。
 *
 * 构造参数是平台函数的注入点，单测不需要 Android 运行时。
 */
internal class BrushWakeLock(
    private val tag: String,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val acquirePlatform: (String, Long) -> Boolean,
    private val releasePlatform: () -> Unit,
) {

    private var held = false

    val isHeld: Boolean get() = held

    /** 幂等获取；平台失败（无权限、异常）返回 false，不抛出。 */
    fun acquire(): Boolean {
        if (held) return true
        held = runCatching { acquirePlatform(tag, timeoutMs) }.getOrDefault(false)
        return held
    }

    /** 幂等释放；从未持有或已释放时什么都不做。 */
    fun release() {
        if (!held) return
        held = false
        runCatching { releasePlatform() }
    }

    companion object {
        /** 兜底上限：超时后由系统强制释放，避免任何路径下永久持锁。 */
        const val TIMEOUT_MS = 12L * 60 * 60 * 1000

        fun create(context: Context, tag: String): BrushWakeLock {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            var lock: PowerManager.WakeLock? = null
            return BrushWakeLock(
                tag = tag,
                acquirePlatform = { wakeTag, timeout ->
                    val manager = power
                    if (manager == null) {
                        false
                    } else {
                        val wakeLock = lock ?: manager
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeTag)
                            .also {
                                it.setReferenceCounted(false)
                                lock = it
                            }
                        wakeLock.acquire(timeout)
                        wakeLock.isHeld
                    }
                },
                releasePlatform = {
                    lock?.let { if (it.isHeld) it.release() }
                },
            )
        }
    }
}