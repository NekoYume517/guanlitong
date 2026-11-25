package com.sdmu.guanlitong.ui.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全站统一的展示格式。
 *
 * 教务接口里的 `xnm` / `xqm` 是编码（`2025` / `3`、`12`、`16`），
 * 直接拼进界面会显示成「第 3 学期」，所以学期、日期都在这里转一次。
 */

/** 「第一学期」；认不出的编码返回空串，不把原始值露给用户。 */
fun termText(xqm: String): String = when (xqm.trim()) {
    "3" -> "第一学期"
    "12" -> "第二学期"
    "16" -> "第三学期"
    else -> ""
}

/** 「2025–2026 学年 · 第一学期」；缺字段时只保留能看的那部分。 */
fun semesterText(xnm: String, xqm: String): String {
    val start = xnm.trim().toIntOrNull()
    val year = if (start != null) "$start–${start + 1} 学年" else xnm.trim()
    return listOf(year, termText(xqm))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/** 时间戳 → 「11月25日 16:47」。 */
fun formatMonthDayTime(timestamp: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(timestamp))

private val DATE_TIME = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})[T ](\d{1,2}):(\d{2})""")

/**
 * 接口里的时间串 → 「6月24日 12:41」。
 *
 * 抢课助手给的是 `2026-06-24T12:41:02.290658+08:00` 这种 ISO 串，
 * 只取日期和时间部分，认不出格式就原样返回。
 */
fun formatDateTime(raw: String): String {
    val text = raw.trim()
    if (text.isBlank()) return ""
    val match = DATE_TIME.find(text) ?: return text
    val (_, month, day, hour, minute) = match.destructured
    return "${month.toInt()}月${day.toInt()}日 ${hour.padStart(2, '0')}:$minute"
}
