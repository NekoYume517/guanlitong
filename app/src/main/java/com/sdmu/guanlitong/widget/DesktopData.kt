package com.sdmu.guanlitong.widget

import android.content.Context
import android.appwidget.AppWidgetManager
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.SettingsStore
import com.sdmu.guanlitong.data.WidgetType
import com.sdmu.guanlitong.data.jwxt.CachedTimetable
import com.sdmu.guanlitong.data.jwxt.Course
import com.sdmu.guanlitong.data.jwxt.JwxtAccess
import com.sdmu.guanlitong.data.jwxt.JwxtCache
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class DesktopState(
    val size: DesktopSize,
    val type: WidgetType,
    val cached: CachedTimetable?,
    val nextClass: NextClassSnapshot,
    val todayCourses: List<Course>,
    val weekCourses: Int,
    val currentWeek: Int,
    val accounts: List<Pair<String, Boolean>>,
) {
    val title: String get() = type.title
}

object DesktopData {
    fun load(context: Context, size: DesktopSize, id: Int, typeOverride: WidgetType? = null): DesktopState {
        val config = DesktopWidgetStore(context).load(id)
        val settings = SettingsStore(context)
        val accounts = AccountStore(context).accounts.value
        val source = settings.jwxtSource.value
        val official = settings.urlFor(ServiceCatalog.byId("jwxt")!!)
        val grab = settings.urlFor(ServiceCatalog.byId("grab")!!)
        val origin = JwxtAccess(source, official, grab).origin
        val cached = origin.takeIf { it.isNotBlank() }?.let { JwxtCache.load(context, it) }
        val now = LocalDateTime.now(CampusZone)
        val baseDate = cached?.savedAt?.takeIf { it > 0 }?.let {
            java.time.Instant.ofEpochMilli(it).atZone(CampusZone).toLocalDate()
        } ?: now.toLocalDate()
        val weekOffset = ChronoUnit.WEEKS.between(
            baseDate.minusDays((baseDate.dayOfWeek.value - 1).toLong()),
            now.toLocalDate().minusDays((now.dayOfWeek.value - 1).toLong()),
        ).toInt()
        val currentWeek = (cached?.timetable?.currentWeek ?: 1) + weekOffset
        val loggedIn = accounts[if (source == JwxtSource.Grab) "grab" else "jwxt"]?.loggedIn == true
        return DesktopState(
            size = size,
            type = typeOverride ?: config.type,
            cached = cached,
            nextClass = nextClassSnapshot(cached, loggedIn, now),
            todayCourses = cached?.timetable?.coursesInWeek(currentWeek)
                ?.filter { it.dayOfWeek == now.dayOfWeek.value }?.sortedBy { it.startPeriod }.orEmpty(),
            weekCourses = cached?.timetable?.coursesInWeek(currentWeek)?.size ?: 0,
            currentWeek = currentWeek,
            accounts = listOf(
                "教务系统" to (accounts["jwxt"]?.loggedIn == true),
                "抢课助手" to (accounts["grab"]?.loggedIn == true),
            ),
        )
    }
}
