package com.sdmu.guanlitong.data.cx

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * 有开放签到时的轮询间隔：提醒时效靠这一档保证。
 *
 * 原先是固定 30s 整轮，13 门课一轮 ≈ 26 次请求/分钟、1500+ 次/小时，与用户操作
 * 无关地一直跑；固定间隔的批量访问正是学习通风控盯的特征。现在只有真的存在
 * 开放活动时才用这个高频档，并且单轮限课（见 [CX_SIGN_SCAN_COURSES_PER_ROUND]）。
 */
const val CX_SIGN_POLL_ACTIVE_MS = 90_000L

/**
 * 没有开放活动时的轮询间隔：5 分钟一轮。
 *
 * 无活动时每小时只剩 12 轮 × 4 门课 ≈ 50 次请求，仍能发现新签到。
 */
const val CX_SIGN_POLL_IDLE_MS = 5 * 60_000L

/**
 * 每轮最多扫的课程数：13 门课分 4 轮轮完，单轮请求从 13 次压到 4 次。
 *
 * 有开放活动时每轮必扫这些课程，其余名额按轮转指针覆盖，见 [CxSignCourseRotation]。
 */
const val CX_SIGN_SCAN_COURSES_PER_ROUND = 4

/** 按上一轮是否扫到开放活动选择下一轮间隔；纯函数，单测与轮询循环共用。 */
fun cxSignPollIntervalMs(hasOpenActivity: Boolean): Long =
    if (hasOpenActivity) CX_SIGN_POLL_ACTIVE_MS else CX_SIGN_POLL_IDLE_MS

/** 课程列表缓存 30 分钟：课程变化很慢，少一次请求是一次。 */
const val CX_SIGN_COURSE_CACHE_MS = 30 * 60_000L

/** 可签到的活动状态：签到 status==1 才开放，签退 status!=2 即可。 */
private const val ACTIVE_STATUS_OPEN = 1
private const val ACTIVE_STATUS_DONE = 2

/** 签到轮询需要的网络能力，抽成接口方便单测注入假实现。 */
interface CxSignGateway {
    suspend fun courses(): List<CxCourse>
    suspend fun actives(course: CxCourse): List<CxActive>
    suspend fun sign(
        active: CxActive,
        course: CxCourse,
        kind: CxSignKind,
        settings: CxSignSettings,
    ): CxSignResult
}

/** 要给用户发的一条结果通知。 */
data class CxSignNoticeItem(
    val notice: CxSignNotice,
    val activeId: String,
    val courseId: String,
    val classId: String,
    val courseName: String,
    val activeName: String,
    val message: String,
    /** 签到还是签退，决定通知文案里的「签到成功 / 签退成功」。 */
    val kind: CxSignKind,
    /** 该活动的 otherId：3 = 手势，2 = 二维码，5 = 拍照；通知据此选文案。 */
    val otherId: Int = 0,
)

/**
 * 签到轮询的一轮：拉课程 → 拉每门课的活动 → 按开关与台账决定要不要提交。
 *
 * 轮询本身由 [CxSignSettings.monitorEnabled]（实时提醒总开关，默认开）决定；
 * [CxSignSettings.checkinEnabled] / [CxSignSettings.checkoutEnabled] 只决定发现
 * 开放活动后要不要自动提交。监控开、自动关时只发一次「需要你手动」的提醒，
 * 台账记 PROMPTED，用户后来打开自动签到仍可补签。
 *
 * 其它原则（见 [planSign] / [signNotice]）：
 * - 401/403 上抛（重登与风控提示靠它），其它网络异常只算这一条失败；
 * - 需要手动的方式（二维码/拍照/手势签到）一律不提交，只提醒一次；
 * - 一条活动一次只提交一次，失败在下一轮按 60s 间隔重试，最多 5 次。
 *
 * 手势签到不再有固定密码：老师每个活动给的图案都不同，自动路径永远不提交，
 * 只把活动标成 MANUAL 交给用户当场手划；手动提交成功后由界面落 SUCCESS 台账。
 */
class CxSignRunner(
    private val gateway: CxSignGateway,
    private val ledger: CxSignLedger,
    private val accountKey: String,
    private val settings: () -> CxSignSettings,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** 轮转限课状态必须跨 runner 实例保留，默认进程级共享。 */
    private val rotation: CxSignCourseRotation = CxSignCourseRotation.shared,
) {

    private var recovered = false

    /**
     * 上一轮是否扫到可处理的开放活动（签到 status=1 / 未结束的签退）。
     * 轮询循环据此在 90s 与 5 分钟两档之间切换，见 [cxSignPollIntervalMs]。
     */
    var lastPollHadOpenActivity: Boolean = false
        private set

    /**
     * 上一轮扫描到的进行中签到（只按服务端 status 判断，未按本地成功记录过滤）。
     * 签到服务据此落盘小组件快照；不额外发请求。
     */
    var lastOpenCheckins: List<CxOpenSign> = emptyList()
        private set

    suspend fun poll(): List<CxSignNoticeItem> {
        // 本轮没发请求/被退避时也归零，避免下一轮沿用上一轮的高频档。
        lastPollHadOpenActivity = false
        lastOpenCheckins = emptyList()
        val current = settings()
        Log.i(
            TAG,
            "poll start monitor=${current.monitorEnabled} " +
                "checkin=${current.checkinEnabled} checkout=${current.checkoutEnabled}",
        )
        // 实时提醒总开关关掉时连课程列表都不拉；自动开关不再决定是否扫描。
        if (!current.monitorEnabled) {
            Log.i(TAG, "poll skipped monitorEnabled=false")
            return emptyList()
        }

        // 受限期间连课程列表都不拉，安静等到解除时间（或 30 分钟退避）再说。
        ledger.restriction(accountKey, nowMs())?.let { restriction ->
            Log.i(TAG, "poll skipped restricted until=${restriction.untilMs}")
            return emptyList()
        }

        // 第一轮先清理上次进程留下的 RUNNING，避免这条活动等满逃生阈值。
        if (!recovered) {
            ledger.recoverStaleRunning(accountKey, nowMs())
            recovered = true
        }

        val allCourses = try {
            gateway.courses()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CxException) {
            rethrowAuthOrRisk(e)
            Log.w(TAG, "courses failed code=${e.code}")
            return emptyList()
        }

        // 单轮限课：开放过的课程必扫，其余按轮转指针覆盖，13 门课 4 轮轮完。
        val courses = rotation.select(accountKey, allCourses)

        val notices = mutableListOf<CxSignNoticeItem>()
        val openCheckins = mutableListOf<CxOpenSign>()
        var activesSeen = 0
        var candidates = 0
        val openByCourse = mutableMapOf<String, Boolean>()
        for (course in courses) {
            val actives = try {
                gateway.actives(course)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CxSignRestrictedException) {
                // 记录解除时间并结束本轮：后续 poll 在到期前都不再发请求。
                ledger.saveRestriction(accountKey, e.restriction)
                Log.w(TAG, "poll aborted by restriction until=${e.restriction.untilMs}")
                return emptyList()
            } catch (e: CxException) {
                rethrowAuthOrRisk(e)
                Log.w(TAG, "actives failed course=${course.name} code=${e.code}")
                continue
            }
            activesSeen += actives.size
            var courseHasOpen = false
            for (active in actives) {
                // 候选 = 有 id 且能判成签到/签退的活动；与 handle 的入口条件一致。
                if (active.id.isNotBlank() && signKind(active) != null) {
                    candidates++
                    courseHasOpen = true
                }
                // 小组件只统计「开放中的签到」；已签到成功的过滤交给服务层（本地历史）。
                if (active.isCheckin && active.status == ACTIVE_STATUS_OPEN && active.id.isNotBlank()) {
                    openCheckins += CxOpenSign(
                        activeId = active.id,
                        activeName = active.name,
                        courseId = course.courseId,
                        clazzId = course.clazzId,
                        courseName = course.name,
                        otherId = active.otherIdValue,
                        endTime = active.endTime,
                        endsAtMs = CxDateTime.parseMs(active.endTime, nowMs()),
                        startsAtMs = CxDateTime.parseMs(active.startTime, nowMs()),
                    )
                }
                handle(active, course, current)?.let(notices::add)
            }
            openByCourse[course.courseId] = courseHasOpen
        }
        // 记下哪几门课还有开放活动：下一轮这些课必扫，轮转指针只覆盖其余课程。
        rotation.recordRound(accountKey, openByCourse)
        // 有开放活动才切 90s 高频档，否则退到 5 分钟，减少固定间隔批量访问。
        lastPollHadOpenActivity = candidates > 0
        lastOpenCheckins = openCheckins
        Log.i(
            TAG,
            "poll done courses=${allCourses.size} scanned=${courses.size} " +
                "actives=$activesSeen candidates=$candidates notices=${notices.size} " +
                "openCourses=${rotation.openCourseIds(accountKey).size} " +
                "next=${cxSignPollIntervalMs(lastPollHadOpenActivity)}",
        )
        return notices
    }

    private suspend fun handle(
        active: CxActive,
        course: CxCourse,
        settings: CxSignSettings,
    ): CxSignNoticeItem? {
        if (active.id.isBlank()) return null
        val kind = signKind(active) ?: return null
        val now = nowMs()
        val previous = ledger.record(accountKey, active.id, kind)

        // 需要手动的类型（二维码/拍照/手势签到）：不提交、不消耗重试次数、
        // 不记失败；只在第一次发现时提醒一次。
        val mode = plannedSignMode(active.otherIdValue)
        if (mode == CxSignMode.MANUAL) {
            // 已经手动签过或已经提醒过：保持原记录。尤其是 SUCCESS 不能被
            // 下一轮改写成 MANUAL，否则手动签到成功后会丢失去重依据。
            if (previous?.status == CxSignRecordStatus.SUCCESS ||
                previous?.status == CxSignRecordStatus.MANUAL
            ) {
                return null
            }
            val manual = manualRecord(previous, now)
            ledger.save(accountKey, active.id, kind, manual)
            val notice = signNotice(previous, manual)
            return if (notice == CxSignNotice.NONE) {
                null
            } else {
                recordHistory(
                    course,
                    active,
                    kind,
                    CxSignHistoryResult.MANUAL,
                    now,
                    detail = manualSignReason(active.otherIdValue, kind),
                )
                CxSignNoticeItem(
                    notice = notice,
                    activeId = active.id,
                    courseId = course.courseId,
                    classId = course.clazzId,
                    courseName = course.name,
                    activeName = active.name,
                    message = manualSignReason(active.otherIdValue, kind),
                    kind = kind,
                    otherId = active.otherIdValue,
                )
            }
        }

        // 监控开、对应的自动开关关：只提醒一次「发现签到，需要你手动」。
        // 提醒记录是 PROMPTED，不是 SKIP，用户之后打开自动签到仍会补签。
        val autoEnabled = when (kind) {
            CxSignKind.CHECKIN -> settings.checkinEnabled
            CxSignKind.CHECKOUT -> settings.checkoutEnabled
        }
        if (!autoEnabled) return promptIfNew(active, course, kind, previous, now)

        when (planSign(previous, now, activeEnded = false)) {
            CxSignPlan.SKIP, CxSignPlan.WAIT -> return null
            CxSignPlan.ATTEMPT -> Unit
        }

        val running = runningRecord(previous, now)
        ledger.save(accountKey, active.id, kind, running)
        val result = submit(active, course, kind, settings)
        // 必须把刚落的 RUNNING 传进去：次数已经在那里加过，传 previous 会少算一次
        // （遗留 RUNNING 超时重试时，5 次上限会变成 6 次）。
        val after = attemptedRecord(running, nowMs(), result.success)
        ledger.save(accountKey, active.id, kind, after)
        Log.i(
            TAG,
            "sign kind=${kind.name} active=${active.id} name=${active.name} " +
                "ok=${result.success} attempts=${after.attempts} msg=${result.message}",
        )

        val notice = signNotice(previous, after)
        if (notice == CxSignNotice.NONE) return null
        recordHistory(
            course = course,
            active = active,
            kind = kind,
            result = if (notice == CxSignNotice.SUCCESS) {
                CxSignHistoryResult.SUCCESS
            } else {
                CxSignHistoryResult.FAILED
            },
            nowMs = now,
            detail = result.message,
        )
        val message = when (notice) {
            CxSignNotice.SUCCESS -> result.message
            CxSignNotice.GAVE_UP -> "已尝试 ${after.attempts} 次仍失败：${result.message}"
            else -> result.message
        }
        return CxSignNoticeItem(
            notice = notice,
            activeId = active.id,
            courseId = course.courseId,
            classId = course.clazzId,
            courseName = course.name,
            activeName = active.name,
            message = message,
            kind = kind,
            otherId = active.otherIdValue,
        )
    }

    /**
     * 监控开、自动关时的一次性提醒。
     *
     * 已有 RUNNING/SUCCESS/MANUAL/GAVE_UP/PROMPTED 都跳过；只有空记录或还没放弃的
     * FAILED 会提醒，并把记录落成 PROMPTED。PROMPTED 不挡后续补签，见 [planSign]。
     */
    private fun promptIfNew(
        active: CxActive,
        course: CxCourse,
        kind: CxSignKind,
        previous: CxSignRecord?,
        now: Long,
    ): CxSignNoticeItem? {
        if (previous != null) {
            val promptable = previous.status == CxSignRecordStatus.FAILED &&
                previous.attempts < CX_SIGN_MAX_ATTEMPTS
            if (!promptable) return null
        }
        ledger.save(accountKey, active.id, kind, promptRecord(previous, now))
        recordHistory(course, active, kind, CxSignHistoryResult.MANUAL, now)
        return CxSignNoticeItem(
            notice = CxSignNotice.PROMPT,
            activeId = active.id,
            courseId = course.courseId,
            classId = course.clazzId,
            courseName = course.name,
            activeName = active.name,
            message = "自动${kind.label}未开启，" +
                "这条${kind.label}（${cxSignModeLabel(active.otherIdValue)}）需要你手动处理",
            kind = kind,
            otherId = active.otherIdValue,
        )
    }

    /** 只把真正发过通知的结果写进「最近记录」，避免每次重试都刷一条失败。 */
    private fun recordHistory(
        course: CxCourse,
        active: CxActive,
        kind: CxSignKind,
        result: CxSignHistoryResult,
        nowMs: Long,
        detail: String = "",
    ) {
        ledger.appendHistory(
            accountKey,
            signResultHistory(
                activeId = active.id,
                kind = kind,
                result = result,
                atMs = nowMs,
                courseName = course.name,
                activeName = active.name,
                courseId = course.courseId,
                clazzId = course.clazzId,
                detail = detail,
            ),
        )
    }

    /** 401/403 上抛；其它 CxException / IO 只让这一条失败。 */
    private suspend fun submit(
        active: CxActive,
        course: CxCourse,
        kind: CxSignKind,
        settings: CxSignSettings,
    ): CxSignResult = try {
        gateway.sign(active, course, kind, settings)
    } catch (e: CancellationException) {
        throw e
    } catch (e: CxException) {
        rethrowAuthOrRisk(e)
        CxSignResult(false, e.message ?: "学习通请求失败")
    } catch (e: IOException) {
        CxSignResult(false, e.cxMessage())
    }

    private fun rethrowAuthOrRisk(e: CxException) {
        if (e.code == CX_CODE_AUTH || e.code == CX_CODE_RISK) throw e
    }

    /**
     * 只按活动类型与状态判断这是签到还是签退；监控开时所有开放活动都要处理，
     * 是否自动提交由 [handle] 再看对应开关。
     */
    private fun signKind(active: CxActive): CxSignKind? = when {
        active.isCheckin && active.status == ACTIVE_STATUS_OPEN -> CxSignKind.CHECKIN
        active.isCheckout && active.status != ACTIVE_STATUS_DONE -> CxSignKind.CHECKOUT
        else -> null
    }

    private companion object {
        const val TAG = "CxSignRunner"
    }
}

/** 课程列表缓存：按账号指纹 + TTL 命中；换账号或超过 30 分钟立即失效。 */
class CxCoursesCache(private val ttlMs: Long = CX_SIGN_COURSE_CACHE_MS) {

    private var cachedAccountKey: String? = null
    private var cachedAtMs = 0L
    private var cached: List<CxCourse>? = null

    fun get(accountKey: String, nowMs: Long): List<CxCourse>? {
        val value = cached ?: return null
        if (cachedAccountKey != accountKey) return null
        if (nowMs - cachedAtMs >= ttlMs) return null
        return value
    }

    fun put(accountKey: String, nowMs: Long, courses: List<CxCourse>) {
        cachedAccountKey = accountKey
        cachedAtMs = nowMs
        cached = courses
    }
}

/**
 * 线上实现：课程列表走 [CxRepository.courses]，活动与签到全部走 `withSession`
 * （401 单飞重登、用新 Cookie 重试一次）。
 */
class CxRemoteSignGateway(
    private val repo: CxRepository,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val courseCacheMs: Long = CX_SIGN_COURSE_CACHE_MS,
) : CxSignGateway {

    private val coursesCache = CxCoursesCache(courseCacheMs)

    override suspend fun courses(): List<CxCourse> {
        // 缓存必须认账号：换号/重登后拿上一个账号的课程去拉活动会张冠李戴。
        val accountKey = repo.withSession { signAccountKey(it) }
        val now = nowMs()
        coursesCache.get(accountKey, now)?.let { return it }
        return repo.courses().also { coursesCache.put(accountKey, now, it) }
    }

    override suspend fun actives(course: CxCourse): List<CxActive> =
        repo.withSession { session ->
            checkinApi(session).activeList(course.courseId, course.clazzId)
        }

    override suspend fun sign(
        active: CxActive,
        course: CxCourse,
        kind: CxSignKind,
        settings: CxSignSettings,
    ): CxSignResult = repo.withSession { session ->
        val api = checkinApi(session)
        when (kind) {
            CxSignKind.CHECKIN -> api.checkin(
                active = active,
                courseId = course.courseId,
                clazzId = course.clazzId,
                settingsLat = settings.seedLat,
                settingsLng = settings.seedLng,
                settingsAddress = settings.address,
            )
            CxSignKind.CHECKOUT -> api.checkout(
                active = active,
                courseId = course.courseId,
                clazzId = course.clazzId,
                settingsLat = settings.seedLat,
                settingsLng = settings.seedLng,
                settingsAddress = settings.address,
            )
        }
    }

    private fun checkinApi(session: CxSession): CxCheckinApi =
        CxCheckinApi(CxApi(session.cookies), session.uid)
}