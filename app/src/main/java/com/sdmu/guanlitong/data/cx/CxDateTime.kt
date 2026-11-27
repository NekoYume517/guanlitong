package com.sdmu.guanlitong.data.cx

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 学习通时间字段解析（收件箱 `insertTime`、作业/考试截止时间）。
 *
 * 对照老项目 `automation.py` 的 `_parse_datetime` / `_parse_datetime_inbox`：
 * 支持毫秒时间戳、`yyyy-MM-dd HH:mm(:ss)`、`yyyy/MM/dd`、`yyyy年MM月dd日`、
 * 以及不带年份的 `MM-dd HH:mm`（用当前年份补齐）。
 *
 * 与老项目的一处刻意差异：老项目把解析出的时间直接标成 UTC，这里统一按北京时间
 * （UTC+8）解释——页面上的时间就是北京时间，设备时区不应影响 24h 提醒窗口。
 */
object CxDateTime {

    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    fun parseMs(text: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        epochMs(raw)?.let { return it }
        formatted(raw)?.let { return it }
        return monthDay(raw, nowMs)
    }

    /** 毫秒时间戳：至少 12 位纯数字，且落在 2000–2100 之间，避免把「20261220」当时间戳。 */
    private fun epochMs(raw: String): Long? {
        if (raw.length < 12 || !raw.all(Char::isDigit)) return null
        val ms = raw.toLongOrNull() ?: return null
        return ms.takeIf { it in MS_MIN until MS_MAX }
    }

    private val DATE_TIME_FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss.S",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy年MM月dd日 HH:mm:ss",
        "yyyy年MM月dd日 HH:mm",
    ).map { DateTimeFormatter.ofPattern(it) }

    private val DATE_FORMATS = listOf(
        "yyyy-MM-dd",
        "yyyy/MM/dd",
        "yyyy年MM月dd日",
    ).map { DateTimeFormatter.ofPattern(it) }

    private fun formatted(raw: String): Long? {
        val normalized = raw.replace(WHITESPACE, " ")
        for (fmt in DATE_TIME_FORMATS) {
            val parsed = runCatching { LocalDateTime.parse(normalized, fmt) }.getOrNull()
            if (parsed != null) return parsed.atZone(ZONE).toInstant().toEpochMilli()
        }
        for (fmt in DATE_FORMATS) {
            val parsed = runCatching { LocalDate.parse(normalized, fmt) }.getOrNull()
            if (parsed != null) return parsed.atStartOfDay(ZONE).toInstant().toEpochMilli()
        }
        return null
    }

    /** 老项目的 `%m-%d %H:%M`：没有年份，用当前年补齐。 */
    private fun monthDay(raw: String, nowMs: Long): Long? {
        val m = MONTH_DAY_RE.find(raw) ?: return null
        val month = m.groupValues[1].toIntOrNull() ?: return null
        val day = m.groupValues[2].toIntOrNull() ?: return null
        val hour = m.groupValues[3].toIntOrNull() ?: return null
        val minute = m.groupValues[4].toIntOrNull() ?: return null
        val year = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZONE).year
        val parsed = runCatching { LocalDateTime.of(year, month, day, hour, minute) }.getOrNull()
            ?: return null
        return parsed.atZone(ZONE).toInstant().toEpochMilli()
    }

    /** 提醒文案里的「截止时间」展示。 */
    fun format(ms: Long): String =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZONE)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private val WHITESPACE = Regex("""\s+""")
    private val MONTH_DAY_RE = Regex("""^(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})$""")

    private const val MS_MIN = 946_684_800_000L // 2000-01-01
    private const val MS_MAX = 4_102_444_800_000L // 2100-01-01
}