package com.sdmu.guanlitong.widget

import android.content.Context
import android.appwidget.AppWidgetManager
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.SettingsStore
import com.sdmu.guanlitong.data.WidgetType
import com.sdmu.guanlitong.data.cx.CxCredStore
import com.sdmu.guanlitong.data.cx.CxInboxSummary
import com.sdmu.guanlitong.data.cx.CxNoticeStore
import com.sdmu.guanlitong.data.cx.CxSignSnapshot
import com.sdmu.guanlitong.data.cx.CxSignSnapshotStore
import com.sdmu.guanlitong.data.cx.CxSignStore
import com.sdmu.guanlitong.data.cx.CxStudySnapshot
import com.sdmu.guanlitong.data.cx.CxStudySnapshotStore
import com.sdmu.guanlitong.data.cx.belongsTo
import com.sdmu.guanlitong.data.cx.signAccountKey
import com.sdmu.guanlitong.data.cx.summarizeInbox
import com.sdmu.guanlitong.data.jwxt.CachedGrades
import com.sdmu.guanlitong.data.jwxt.GradeWidgetConfig
import com.sdmu.guanlitong.data.jwxt.CachedTimetable
import com.sdmu.guanlitong.data.jwxt.Course
import com.sdmu.guanlitong.data.jwxt.JwxtAccess
import com.sdmu.guanlitong.data.jwxt.JwxtCache
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import com.sdmu.guanlitong.data.pu.CachedPu
import com.sdmu.guanlitong.data.pu.PuCache
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
    /** 最近一次自动刷新失败、但仍保留旧课表；界面据此标注「更新失败」。 */
    val refreshIssue: Boolean = false,
    /** 快捷功能只读本地目录，按尺寸挑好入口；无数据时由界面给空态。 */
    val quickActions: List<DesktopQuickAction> = DesktopQuickActions.forSize(size),
    /** 学习通签到快照；只读本地，换账号后不属于当前账号的会被置空。 */
    val sign: CxSignSnapshot? = null,
    /** 签到实时提醒总开关；关着时卡片显示「签到监控未开启」。 */
    val signMonitorEnabled: Boolean = false,
    /** 学习通收件箱摘要；只读本地库。 */
    val inbox: CxInboxSummary? = null,
    /** 教务成绩缓存；只读本地，未预热时为 null。 */
    val grades: CachedGrades? = null,
    /** 第二课堂缓存；只读本地。 */
    val pu: CachedPu? = null,
    /** 刷课进度快照；只读本地。 */
    val study: CxStudySnapshot? = null,
    /** 这张成绩卡片的学期/课程/样式配置；null 表示默认。 */
    val gradeConfig: GradeWidgetConfig? = null,
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
        val cxSession = runCatching { CxCredStore(context).session }.getOrNull()
        val cxKey = cxSession?.let(::signAccountKey).orEmpty()
        val signStore = CxSignStore(context)
        val inboxStore = CxNoticeStore(context)
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
                "山管 App" to (accounts["sdgy"]?.loggedIn == true),
                "第二课堂" to (accounts["pu"]?.loggedIn == true),
                "学习通" to (accounts["cx"]?.loggedIn == true),
            ),
            refreshIssue = origin.isNotBlank() &&
                DesktopWidgetRefreshStore(context).lastFailureAt(origin) > (cached?.savedAt ?: 0L),
            sign = CxSignSnapshotStore.peek(context)?.takeIf { it.belongsTo(cxKey) },
            signMonitorEnabled = signStore.settings().monitorEnabled,
            inbox = if (cxKey.isBlank()) null else summarizeInbox(
                notices = inboxStore.notices(cxKey),
                syncedAtMs = inboxStore.lastSyncedAt(cxKey),
            ),
            grades = DesktopWidgetRefresher.currentCachedGrades(context),
            pu = PuCache.peek(context),
            study = CxStudySnapshotStore.peek(context),
            gradeConfig = config.gradeConfig,
        )
    }
}
