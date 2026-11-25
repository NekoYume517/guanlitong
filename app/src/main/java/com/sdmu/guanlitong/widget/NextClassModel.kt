package com.sdmu.guanlitong.widget

import com.sdmu.guanlitong.data.jwxt.CachedTimetable
import com.sdmu.guanlitong.data.jwxt.Course
import com.sdmu.guanlitong.data.jwxt.Timetable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 概览与桌面共用的时间规则，避免桌面和应用内出现不同的「下一节课」。 */
val CampusZone: ZoneId = ZoneId.of("Asia/Shanghai")

data class UpcomingClass(
    val course: Course,
    val date: LocalDate,
    val start: LocalTime?,
    val end: LocalTime?,
    val isOngoing: Boolean,
    val minutesUntil: Long?,
) {
    val timeLabel: String
        get() = when {
            start != null && end != null -> "${formatClock(start)}–${formatClock(end)}"
            start != null -> formatClock(start)
            else -> "${course.startPeriod}–${course.endPeriod} 节"
        }
}

data class NextClassSnapshot(
    val upcoming: List<UpcomingClass>,
    val hasTimetable: Boolean,
    val sessionLost: Boolean,
    val savedAt: Long,
    val today: LocalDate,
)

fun nextClassSnapshot(
    cached: CachedTimetable?,
    loggedIn: Boolean,
    now: LocalDateTime = LocalDateTime.now(CampusZone),
): NextClassSnapshot = NextClassSnapshot(
    upcoming = cached?.timetable?.let { upcomingClasses(it, cached.savedAt, now, limit = 5) }.orEmpty(),
    hasTimetable = cached?.timetable != null,
    sessionLost = !loggedIn,
    savedAt = cached?.savedAt ?: 0L,
    today = now.toLocalDate(),
)

/** 缓存的 currentWeek 属于 savedAt 当天，先反推基周一，再算目标日期的周次。 */
fun upcomingClasses(
    timetable: Timetable,
    cachedAt: Long,
    now: LocalDateTime,
    limit: Int = 5,
): List<UpcomingClass> {
    val today = now.toLocalDate()
    val baseDate = if (cachedAt > 0) {
        Instant.ofEpochMilli(cachedAt).atZone(CampusZone).toLocalDate()
    } else {
        today
    }
    val baseMonday = baseDate.minusDays((baseDate.dayOfWeek.value - 1).toLong())
    fun weekOf(date: LocalDate): Int {
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        return timetable.currentWeek + ChronoUnit.WEEKS.between(baseMonday, monday).toInt()
    }
    val result = mutableListOf<UpcomingClass>()
    for (offset in 0L..6L) {
        val date = today.plusDays(offset)
        val courses = timetable.coursesInWeek(weekOf(date))
            .filter { it.dayOfWeek == date.dayOfWeek.value }
            .sortedBy { it.startPeriod }
        for (course in courses) {
            val start = parseClock(timetable.jcTimes[course.startPeriod]?.start)
            val end = parseClock(timetable.jcTimes[course.endPeriod]?.end)
            val startsAt = start?.let { date.atTime(it) }
            val endsAt = end?.let { date.atTime(it) }
            val ongoing = startsAt != null && endsAt != null &&
                !now.isBefore(startsAt) && now.isBefore(endsAt)
            if (!ongoing && startsAt != null && !now.isBefore(startsAt)) continue
            result += UpcomingClass(
                course = course,
                date = date,
                start = start,
                end = end,
                isOngoing = ongoing,
                minutesUntil = startsAt?.let { Duration.between(now, it).toMinutes().coerceAtLeast(0) },
            )
            if (result.size >= limit) return result
        }
    }
    return result
}

fun parseClock(raw: String?): LocalTime? {
    val parts = raw?.trim()?.split(':') ?: return null
    if (parts.size < 2) return null
    return runCatching { LocalTime.of(parts[0].toInt(), parts[1].toInt()) }.getOrNull()
}

fun formatClock(time: LocalTime): String = String.format(Locale.CHINA, "%02d:%02d", time.hour, time.minute)

fun dayPrefix(item: UpcomingClass, today: LocalDate): String = when {
    item.date == today -> ""
    item.date == today.plusDays(1) -> "明天"
    else -> "${item.date.monthValue}/${item.date.dayOfMonth}"
}

fun countdownLabel(item: UpcomingClass): String = when {
    item.isOngoing -> "正在上课"
    item.minutesUntil == null -> "待上课"
    item.minutesUntil <= 0L -> "马上开始"
    item.minutesUntil < 60L -> "${item.minutesUntil} 分钟后"
    else -> {
        val hours = item.minutesUntil / 60
        val minutes = item.minutesUntil % 60
        if (minutes == 0L) "还有 $hours 小时" else "还有 $hours 小时 $minutes 分"
    }
}
