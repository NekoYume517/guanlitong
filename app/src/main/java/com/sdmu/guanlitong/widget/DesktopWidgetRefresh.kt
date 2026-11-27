package com.sdmu.guanlitong.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.SettingsStore
import com.sdmu.guanlitong.data.jwxt.JwxtAccess
import com.sdmu.guanlitong.data.jwxt.JwxtAccessResolver
import com.sdmu.guanlitong.data.jwxt.JwxtCache
import com.sdmu.guanlitong.data.jwxt.JwxtGradesCache
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import com.sdmu.guanlitong.data.jwxt.CachedGrades
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuCache
import com.sdmu.guanlitong.data.pu.PuCredStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 桌面小组件的数据刷新与慢速预热。
 *
 * - 课表：三张教务卡片（今日课程 / 下一节课 / 教务概览）共用 [JwxtCache]；
 * - 成绩：[JwxtGradesCache]；
 * - 第二课堂：[PuCache]（首页活动 + 总学时）。
 *
 * 学习通不在刷新范围内，一律不请求，因此不触碰签到轮询与「限制访问」风控通道。
 *
 * 纪律：
 * - 三个数据源各自 1 小时 TTL，过期才拉；手动刷新强制拉；
 * - 同一时刻只有一次刷新在跑，多个小组件实例共享同一次结果；
 * - 冷启动 / 刚登录且本地无缓存时允许立即拉一次，失败后按数据源进入冷却；
 * - 任意数据源失败都保留旧缓存；只有课表失败会标注在卡片上，成绩/二课只记录失败与日志；
 * - 账号未登录直接跳过，不做请求，也不清空缓存。
 */
object DesktopWidgetRefreshPolicy {
    /** 桌面数据（课表、成绩、第二课堂）统一的缓存有效期。 */
    const val DATA_TTL_MS = 60 * 60_000L

    /** 兼容旧名字：课表与其它数据共用同一 TTL。 */
    const val TIMETABLE_TTL_MS = DATA_TTL_MS

    /** 本地没有缓存时，一次失败后至少等这么久再试。 */
    const val NO_CACHE_RETRY_GAP_MS = 10 * 60_000L

    /** 有旧缓存时，一次失败后至少等这么久再试。 */
    const val STALE_RETRY_GAP_MS = 15 * 60_000L

    /** WorkManager 周期兜底，与缓存有效期保持一致。 */
    const val PERIODIC_REFRESH_MINUTES = 60L

    fun shouldRefresh(cachedAt: Long?, lastFailureAt: Long, now: Long): Boolean {
        if (cachedAt != null && now - cachedAt < DATA_TTL_MS) return false
        val gap = if (cachedAt == null) NO_CACHE_RETRY_GAP_MS else STALE_RETRY_GAP_MS
        return lastFailureAt <= 0 || now - lastFailureAt >= gap
    }
}

/** 桌面刷新涉及的三个数据源，各自记录失败冷却。 */
enum class DesktopRefreshSource(val key: String) {
    Timetable("timetable"),
    Grades("grades"),
    Pu("pu"),
}

sealed interface DesktopRefreshOutcome {
    /** 缓存还新鲜、失败冷却中，或桌面没有小组件。 */
    data object Skipped : DesktopRefreshOutcome

    /** 地址缺失或没有可用会话，不视为网络失败。 */
    data object NotLoggedIn : DesktopRefreshOutcome

    data class Updated(val savedAt: Long) : DesktopRefreshOutcome

    data class Failed(val message: String) : DesktopRefreshOutcome
}

/** 成绩 / 第二课堂预热的结论；与课表的 [DesktopRefreshOutcome] 分开，互不牵连。 */
sealed interface PrewarmOutcome {
    data object Skipped : PrewarmOutcome

    data object NotLoggedIn : PrewarmOutcome

    data class Updated(val savedAt: Long) : PrewarmOutcome

    data class Failed(val message: String) : PrewarmOutcome
}

data class DesktopRefreshReport(
    val timetable: DesktopRefreshOutcome = DesktopRefreshOutcome.Skipped,
    val grades: PrewarmOutcome = PrewarmOutcome.Skipped,
    val pu: PrewarmOutcome = PrewarmOutcome.Skipped,
)

sealed interface PrewarmDecision {
    data object Fetch : PrewarmDecision
    data object Fresh : PrewarmDecision
    data object Cooldown : PrewarmDecision
    data object NotLoggedIn : PrewarmDecision
}

/** 预热是否该发请求的纯逻辑；登录缺失优先于 TTL / 冷却判断。 */
object DesktopPrewarmPolicy {

    fun decide(
        now: Long,
        force: Boolean,
        loggedIn: Boolean,
        cachedAt: Long?,
        lastFailureAt: Long,
    ): PrewarmDecision = when {
        !loggedIn -> PrewarmDecision.NotLoggedIn
        force -> PrewarmDecision.Fetch
        cachedAt != null && now - cachedAt < DesktopWidgetRefreshPolicy.DATA_TTL_MS -> PrewarmDecision.Fresh
        lastFailureAt > 0 &&
            now - lastFailureAt <
            (if (cachedAt == null) DesktopWidgetRefreshPolicy.NO_CACHE_RETRY_GAP_MS
            else DesktopWidgetRefreshPolicy.STALE_RETRY_GAP_MS) -> PrewarmDecision.Cooldown
        else -> PrewarmDecision.Fetch
    }

    /** 成绩预热看当前教务数据来源对应的账号。 */
    fun jwxtLoggedIn(source: JwxtSource, accounts: Map<String, com.sdmu.guanlitong.data.Account>): Boolean =
        when (source) {
            JwxtSource.Official -> accounts["jwxt"]?.loggedIn == true
            JwxtSource.Grab -> accounts["grab"]?.loggedIn == true
        }

    fun puLoggedIn(accounts: Map<String, com.sdmu.guanlitong.data.Account>): Boolean =
        accounts["pu"]?.loggedIn == true
}

/**
 * 通用的单次预热执行器：登录缺失 / 缓存新鲜 / 冷却中直接跳过；
 * 拉取成功才落盘，失败只记录并保留旧缓存。
 *
 * 抽成独立函数是为了让 TTL、跳过、失败保留旧值都能直接单测。
 */
internal suspend fun <T> runPrewarm(
    now: Long,
    force: Boolean,
    loggedIn: Boolean,
    cachedAt: Long?,
    lastFailureAt: Long,
    fetch: suspend () -> T,
    onSuccess: (T) -> Long,
    onFailure: (Throwable, Long) -> PrewarmOutcome,
): PrewarmOutcome = when (
    DesktopPrewarmPolicy.decide(now, force, loggedIn, cachedAt, lastFailureAt)
) {
    PrewarmDecision.NotLoggedIn -> PrewarmOutcome.NotLoggedIn
    PrewarmDecision.Fresh, PrewarmDecision.Cooldown -> PrewarmOutcome.Skipped
    PrewarmDecision.Fetch -> try {
        PrewarmOutcome.Updated(onSuccess(fetch()))
    } catch (t: Throwable) {
        onFailure(t, now)
    }
}

/** 按教务数据根地址分 key 记录每个数据源的失败时间；换来源后互不影响。 */
class DesktopWidgetRefreshStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastFailureAt(
        origin: String,
        source: DesktopRefreshSource = DesktopRefreshSource.Timetable,
    ): Long = if (origin.isBlank()) 0L else prefs.getLong(keyFor(origin, source), 0L)

    fun markFailure(
        origin: String,
        now: Long,
        source: DesktopRefreshSource = DesktopRefreshSource.Timetable,
    ) {
        if (origin.isBlank()) return
        prefs.edit().putLong(keyFor(origin, source), now).apply()
    }

    fun markSuccess(
        origin: String,
        source: DesktopRefreshSource = DesktopRefreshSource.Timetable,
    ) {
        if (origin.isBlank()) return
        prefs.edit().remove(keyFor(origin, source)).apply()
    }

    private fun keyFor(origin: String, source: DesktopRefreshSource): String {
        val prefix = when (source) {
            // 保持升级前的 key，旧版本留下的课表失败记录继续生效。
            DesktopRefreshSource.Timetable -> "failed_at_"
            else -> "${source.key}_failed_at_"
        }
        return prefix + Integer.toHexString(origin.hashCode())
    }

    private companion object {
        const val PREFS = "desktop_widget_refresh"
    }
}

/** 同一时刻只跑一份任务；并发调用方拿到同一个 [Deferred]。 */
internal class SingleFlight<T> {

    private val lock = Any()
    private var inFlight: Deferred<T>? = null

    fun run(scope: CoroutineScope, block: suspend () -> T): Deferred<T> = synchronized(lock) {
        val running = inFlight
        if (running != null && running.isActive) {
            running
        } else {
            val deferred = scope.async { block() }
            inFlight = deferred
            deferred.invokeOnCompletion {
                synchronized(lock) { if (inFlight === deferred) inFlight = null }
            }
            deferred
        }
    }
}

object DesktopWidgetRefresher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val singleFlight = SingleFlight<DesktopRefreshReport>()

    /**
     * 发起（或复用）一次刷新，不等网络。多个小组件实例同时调用只会拿到同一个任务。
     *
     * @param force 手动刷新：跳过 TTL 与失败冷却，但仍受单飞去重约束。
     */
    fun request(context: Context, force: Boolean = false): Deferred<DesktopRefreshReport> {
        val appContext = context.applicationContext
        // 桌面没有卡片时就完全不发请求，避免为没装小组件的用户增加后台流量。
        if (!DesktopWidgetRefreshSchedule.hasAnyWidget(appContext)) {
            return CompletableDeferred(DesktopRefreshReport())
        }
        return singleFlight.run(scope) {
            val report = runOnce(appContext, force)
            // 只有课表结果会影响卡片展示；成绩 / 二课失败不改变课表的「更新失败」标注。
            if (report.timetable is DesktopRefreshOutcome.Updated ||
                report.timetable is DesktopRefreshOutcome.Failed
            ) {
                renderDesktopWidgets(appContext)
            }
            report
        }
    }

    suspend fun refreshNow(context: Context, force: Boolean = false): DesktopRefreshReport =
        request(context, force).await()

    /** 强制刷新成绩的独立入口；与其它刷新共享同一次在途任务。 */
    suspend fun refreshGradesNow(context: Context, force: Boolean = true): PrewarmOutcome =
        refreshNow(context, force).grades

    /** 强制刷新第二课堂的独立入口；与其它刷新共享同一次在途任务。 */
    suspend fun refreshPuNow(context: Context, force: Boolean = true): PrewarmOutcome =
        refreshNow(context, force).pu

    /** 当前来源下的课表缓存；配置页用它显示「上次更新」。 */
    fun currentCachedTimetable(context: Context) =
        currentJwxtOrigin(context).takeIf { it.isNotBlank() }
            ?.let { JwxtCache.load(context, it) }

    /** 当前来源下的成绩缓存；只读本地，不触发网络。 */
    fun currentCachedGrades(context: Context): CachedGrades? =
        currentJwxtOrigin(context).takeIf { it.isNotBlank() }
            ?.let { JwxtGradesCache.peek(context, it) }

    /** 第二课堂缓存；只读本地，不触发网络。 */
    fun currentCachedPu(context: Context) = PuCache.peek(context)

    private suspend fun runOnce(context: Context, force: Boolean): DesktopRefreshReport {
        val timetable = refreshTimetable(context, force)
        val grades = refreshGrades(context, force)
        val pu = refreshPu(context, force)
        return DesktopRefreshReport(timetable = timetable, grades = grades, pu = pu)
    }

    private suspend fun refreshTimetable(context: Context, force: Boolean): DesktopRefreshOutcome =
        withContext(Dispatchers.IO) {
            val access = currentAccess(context) ?: return@withContext DesktopRefreshOutcome.NotLoggedIn
            val origin = access.origin
            if (origin.isBlank()) return@withContext DesktopRefreshOutcome.NotLoggedIn

            val cached = JwxtCache.load(context, origin)
            val store = DesktopWidgetRefreshStore(context)
            val now = System.currentTimeMillis()
            if (!force &&
                !DesktopWidgetRefreshPolicy.shouldRefresh(
                    cached?.savedAt,
                    store.lastFailureAt(origin, DesktopRefreshSource.Timetable),
                    now,
                )
            ) {
                return@withContext DesktopRefreshOutcome.Skipped
            }

            // 只使用现有会话；不在后台跑验证码重登，避免静默请求风暴。
            val cookie = access.cookie()
            if (cookie.isBlank()) return@withContext DesktopRefreshOutcome.NotLoggedIn
            val client = access.client(cookie)
                ?: return@withContext DesktopRefreshOutcome.NotLoggedIn

            try {
                val timetable = client.fetchTimetable()
                val saved = JwxtCache.save(context, origin, timetable)
                store.markSuccess(origin, DesktopRefreshSource.Timetable)
                DesktopRefreshOutcome.Updated(saved.savedAt)
            } catch (t: Throwable) {
                store.markFailure(origin, now, DesktopRefreshSource.Timetable)
                DesktopRefreshOutcome.Failed(t.message?.take(60) ?: "刷新失败")
            }
        }

    private suspend fun refreshGrades(context: Context, force: Boolean): PrewarmOutcome =
        withContext(Dispatchers.IO) {
            val settings = SettingsStore(context)
            val source = settings.jwxtSource.value
            val accounts = AccountStore(context).accounts.value
            val access = currentAccess(context)
            val origin = access?.origin.orEmpty()
            val cached = origin.takeIf { it.isNotBlank() }
                ?.let { JwxtGradesCache.peek(context, it) }
            val store = DesktopWidgetRefreshStore(context)
            val now = System.currentTimeMillis()

            runPrewarm(
                now = now,
                force = force,
                loggedIn = DesktopPrewarmPolicy.jwxtLoggedIn(source, accounts),
                cachedAt = cached?.savedAt,
                lastFailureAt = store.lastFailureAt(origin, DesktopRefreshSource.Grades),
                fetch = {
                    val cookie = access?.cookie().orEmpty()
                    if (cookie.isBlank()) throw PrewarmLoginMissing()
                    val client = access?.client(cookie) ?: throw PrewarmLoginMissing()
                    client.fetchGrades()
                },
                onSuccess = { grades ->
                    val saved = JwxtGradesCache.save(context, origin, grades)
                    store.markSuccess(origin, DesktopRefreshSource.Grades)
                    saved.savedAt
                },
                onFailure = { error, failedAt ->
                    if (error is PrewarmLoginMissing) {
                        Log.d(TAG, "成绩预热跳过：教务会话不存在")
                        PrewarmOutcome.NotLoggedIn
                    } else {
                        store.markFailure(origin, failedAt, DesktopRefreshSource.Grades)
                        Log.w(TAG, "成绩预热失败，保留旧缓存：${error.message}")
                        PrewarmOutcome.Failed(error.message?.take(60) ?: "成绩预热失败")
                    }
                },
            )
        }

    private suspend fun refreshPu(context: Context, force: Boolean): PrewarmOutcome =
        withContext(Dispatchers.IO) {
            val accounts = AccountStore(context).accounts.value
            val cached = PuCache.peek(context)
            val store = DesktopWidgetRefreshStore(context)
            val now = System.currentTimeMillis()
            // 桌面刷新没有「窗口」，用固定 key 记录失败时间。
            val origin = PU_REFRESH_KEY

            runPrewarm(
                now = now,
                force = force,
                loggedIn = DesktopPrewarmPolicy.puLoggedIn(accounts),
                cachedAt = cached?.savedAt,
                lastFailureAt = store.lastFailureAt(origin, DesktopRefreshSource.Pu),
                fetch = {
                    val cred = PuCredStore(context)
                    if (!cred.loggedIn) throw PrewarmLoginMissing()
                    val page = PuActions.activities(page = 1, limit = 20)
                    val totalCredit = runCatching { PuActions.me().second.total }
                        .getOrElse { error ->
                            Log.w(TAG, "第二课堂学时预热失败，沿用旧学时：${error.message}")
                            cached?.totalCredit ?: 0.0
                        }
                    PuPrewarmPayload(page.list, totalCredit)
                },
                onSuccess = { payload ->
                    val saved = PuCache.save(context, payload.activities, payload.totalCredit)
                    store.markSuccess(origin, DesktopRefreshSource.Pu)
                    saved.savedAt
                },
                onFailure = { error, failedAt ->
                    if (error is PrewarmLoginMissing) {
                        Log.d(TAG, "第二课堂预热跳过：未登录")
                        PrewarmOutcome.NotLoggedIn
                    } else {
                        store.markFailure(origin, failedAt, DesktopRefreshSource.Pu)
                        Log.w(TAG, "第二课堂预热失败，保留旧缓存：${error.message}")
                        PrewarmOutcome.Failed(error.message?.take(60) ?: "第二课堂预热失败")
                    }
                },
            )
        }

    private fun currentAccess(context: Context): JwxtAccess? =
        JwxtAccessResolver.current(context)

    private fun currentJwxtOrigin(context: Context): String =
        JwxtAccessResolver.currentOrigin(context)

    private data class PuPrewarmPayload(
        val activities: List<com.sdmu.guanlitong.data.pu.PuActivity>,
        val totalCredit: Double,
    )

    private class PrewarmLoginMissing : Exception("session-missing")

    private const val PU_REFRESH_KEY = "pu"
    private const val TAG = "DesktopWidget"
}

/** 「上次更新 14:05」；不是今天时带上月日。 */
fun formatDesktopUpdatedLabel(savedAt: Long, now: Long = System.currentTimeMillis()): String {
    if (savedAt <= 0) return ""
    val saved = Instant.ofEpochMilli(savedAt).atZone(CampusZone)
    val today = Instant.ofEpochMilli(now).atZone(CampusZone).toLocalDate()
    val pattern = if (saved.toLocalDate() == today) "HH:mm" else "M月d日 HH:mm"
    val time = DateTimeFormatter.ofPattern(pattern, Locale.CHINA).format(saved)
    return "上次更新 $time"
}

/** 约 1 小时一次的兜底刷新；唯一任务，重复调用幂等。 */
class DesktopWidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!DesktopWidgetRefreshSchedule.hasAnyWidget(applicationContext)) {
            return Result.success()
        }
        runCatching { DesktopWidgetRefresher.refreshNow(applicationContext) }
        return Result.success()
    }
}

object DesktopWidgetRefreshSchedule {

    private const val WORK_NAME = "desktop_widget_refresh"

    fun ensurePeriodic(context: Context) {
        if (!hasAnyWidget(context)) return
        val request = PeriodicWorkRequestBuilder<DesktopWidgetRefreshWorker>(
            DesktopWidgetRefreshPolicy.PERIODIC_REFRESH_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelIfNoWidgets(context: Context) {
        if (hasAnyWidget(context)) return
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    fun hasAnyWidget(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return desktopReceivers.any { receiver ->
            manager.getAppWidgetIds(ComponentName(context, receiver)).isNotEmpty()
        }
    }

    private val desktopReceivers = listOf(
        SmallDesktopWidgetReceiver::class.java,
        MediumDesktopWidgetReceiver::class.java,
        LargeDesktopWidgetReceiver::class.java,
    )
}