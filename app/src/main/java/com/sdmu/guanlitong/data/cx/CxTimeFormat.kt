package com.sdmu.guanlitong.data.cx

/**
 * 刷课进度用的时长格式：「MM:SS」。
 *
 * 负数按 0 处理；分钟超过 99 时不截断，按真实分钟数显示（例如 6000 秒 → 「100:00」），
 * 因为学习通有的音频任务点本身就超过两小时，截断会让浮球显示错误的数字。
 */
fun cxFormatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val minutes = safe / 60
    val rest = safe % 60
    return "${minutes.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
}