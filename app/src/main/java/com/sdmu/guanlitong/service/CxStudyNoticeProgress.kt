package com.sdmu.guanlitong.service

/**
 * 刷课常驻通知的进度刻度与节流判定。
 *
 * 纯逻辑、不依赖 Android，便于 JVM 单测；通知本身仍由 [CxTaskService] 组装。
 */
object CxStudyNoticeProgress {

    /** 拿不到总节数时的百分比刻度。 */
    const val PERCENT_MAX = 100

    /** 两次进度通知的最小间隔：频繁 notify 会被系统限流，10 秒一档足够看。 */
    const val MIN_INTERVAL_MS = 10_000L

    /** 通知进度条刻度：value 已完成节数，max 本次刷课总节数。 */
    data class Scale(val value: Int, val max: Int)

    /**
     * 有总节数就按节数刻度（currentIndex / total）；总节数未知（<=0）退化为
     * 百分比刻度（任务点内进度 / [PERCENT_MAX]）。越界值一律钳制。
     */
    fun scale(currentIndex: Int, total: Int, taskPercent: Int): Scale =
        if (total > 0) {
            Scale(currentIndex.coerceIn(0, total), total)
        } else {
            Scale(taskPercent.coerceIn(0, PERCENT_MAX), PERCENT_MAX)
        }

    /**
     * 这次该不该 notify：终态必发；其余要「进度/文案真的变了」且距上次至少
     * [MIN_INTERVAL_MS]（还没发过时直接发）。
     */
    fun shouldPost(
        final: Boolean,
        changed: Boolean,
        lastPostAtMillis: Long?,
        nowMillis: Long,
    ): Boolean {
        if (final) return true
        if (!changed) return false
        if (lastPostAtMillis == null) return true
        return nowMillis - lastPostAtMillis >= MIN_INTERVAL_MS
    }
}