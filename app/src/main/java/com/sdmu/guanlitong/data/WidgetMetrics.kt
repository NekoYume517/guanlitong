package com.sdmu.guanlitong.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 小组件图形用的纯计算。
 *
 * 图形即数据：这些函数决定液面高度、圆环节奏、堆叠条长度，必须可单测。
 */

/** 剩余时间占比，clamp 0..1；结束时间缺失或时间窗无效时返回 0（图形退化为空环）。 */
fun remainingRatio(startsAtMs: Long, endsAtMs: Long, nowMs: Long): Float {
    if (endsAtMs <= 0L || startsAtMs <= 0L || endsAtMs <= startsAtMs) return 0f
    val left = (endsAtMs - nowMs).toFloat() / (endsAtMs - startsAtMs).toFloat()
    return left.coerceIn(0f, 1f)
}

/** 完成度占比，clamp 0..1；目标缺失时返回 0。 */
fun completionRatio(value: Double, target: Double): Float {
    if (target <= 0.0) return 0f
    return (value / target).toFloat().coerceIn(0f, 1f)
}

/** 更新时间角标：今天只显示时分，跨天带上月日；没有时间返回 null。 */
fun freshnessLabel(
    savedAtMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.of("Asia/Shanghai"),
): String? {
    if (savedAtMs <= 0L) return null
    val saved = Instant.ofEpochMilli(savedAtMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val pattern = if (saved.toLocalDate() == today) "HH:mm" else "M月d日 HH:mm"
    return DateTimeFormatter.ofPattern(pattern, Locale.CHINA).format(saved)
}

/** 秒数 → `mm:ss`（不足 1 小时）或 `H:MM:SS`，用于刷课进度盘。 */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "0:00"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.CHINA, "%d:%02d", minutes, secs)
    }
}

/** 缓存超过 [ttlMs] 才需要标注「HH:mm 更新」，否则返回 null，避免卡面啰嗦。 */
fun staleUpdateLabel(
    savedAtMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    ttlMs: Long = 60 * 60_000L,
    zone: ZoneId = ZoneId.of("Asia/Shanghai"),
): String? {
    if (savedAtMs <= 0L) return null
    if (nowMs - savedAtMs < ttlMs) return null
    return freshnessLabel(savedAtMs, nowMs, zone)?.let { "$it 更新" }
}