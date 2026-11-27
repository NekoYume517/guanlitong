package com.sdmu.guanlitong.data.cx

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** 收件箱只读扫描的并发与节流：与章节加载同一套风控纪律。 */
const val CX_SIGN_SCAN_MAX_PARALLEL = 3
const val CX_SIGN_SCAN_GAP_MS = 250L

/**
 * 收件箱置顶用的一条进行中签到。
 *
 * 只收签到活动（activeType=2 且 status=1）；签退不在收件箱置顶里展示。
 */
data class CxOpenSign(
    val activeId: String,
    val activeName: String,
    val courseId: String,
    val clazzId: String,
    val courseName: String,
    val otherId: Int,
    val endTime: String,
    val endsAtMs: Long?,
    /** 服务端给的开始时间；用于小组件算剩余时间占比。 */
    val startsAtMs: Long? = null,
) {
    /** 方式标签：普通 / 位置 / 手势 / 二维码 / 拍照。 */
    val modeLabel: String get() = cxSignModeLabel(otherId)

    /** 手势签到点开走九宫格页；其它走收件箱详情的手动签到路径。 */
    val gesture: Boolean get() = otherId == 3

    /** 详情页提交路径用的活动链接（解析器认 id / courseId / classId）。 */
    val actionUrl: String
        get() = "https://mobilelearn.chaoxing.com/page/active/show" +
            "?id=$activeId&courseId=$courseId&classId=$clazzId"
}

/**
 * 一次扫描的返回：进行中的签到 + 诊断统计。
 *
 * [coursesScanned] / [activesSeen] / [finishedAtMs] 只用于日志与 debug 文案，
 * 不参与筛选判断，也不额外发请求。
 */
data class CxSignScanResult(
    val open: List<CxOpenSign>,
    val coursesScanned: Int,
    val activesSeen: Int,
    val finishedAtMs: Long,
)

/**
 * 全局限流：两次完整扫描之间至少隔 [gapMs]（默认 60 秒）。
 *
 * 每次扫描是 13 门课 × activelist，连点刷新会把请求量放大，正是风控诱因之一。
 * 间隔内直接复用上次结果（按新的排除集合重新过滤），不再发请求。
 * 用进程内单例 [shared] 在签到页/收件箱之间共享；单测传独立实例避免串状态。
 */
class CxSignScanThrottle(private val gapMs: Long = CX_SIGN_SCAN_MIN_GAP_MS) {

    private var lastAtMs = 0L
    private var lastResult: CxSignScanResult? = null

    @Synchronized
    fun cached(nowMs: Long): CxSignScanResult? {
        val result = lastResult ?: return null
        if (lastAtMs <= 0L) return null
        return if (nowMs - lastAtMs < gapMs) result else null
    }

    @Synchronized
    fun record(result: CxSignScanResult) {
        lastAtMs = result.finishedAtMs
        lastResult = result
    }

    @Synchronized
    fun clear() {
        lastAtMs = 0L
        lastResult = null
    }

    companion object {
        val shared = CxSignScanThrottle()
    }
}

/**
 * 收件箱打开时的一次只读扫描：课程列表（复用 [CxRemoteSignGateway] 的 5 分钟缓存）
 * → 每门课的 activeList → 只保留开放中的签到，按结束时间升序。
 *
 * 并发上限 [CX_SIGN_SCAN_MAX_PARALLEL]、每个请求前过 [CxRequestPacer]（250ms），
 * 与刷课章节扫描同一套防风控节奏。401/403 直接上抛、让整次扫描停下；其它课程
 * 失败只跳过那一门，不拖垮置顶区块。
 *
 * 每次扫描打诊断日志（课程数、每门课的活动数、每条活动的 type/status），
 * 用来定位「活动明明发了但页面没显示」是数据、筛条件还是课程范围问题。
 */
class CxSignScanner(
    private val gateway: CxSignGateway,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val pacer: CxRequestPacer = CxRequestPacer(CX_SIGN_SCAN_GAP_MS),
    private val maxParallel: Int = CX_SIGN_SCAN_MAX_PARALLEL,
    private val throttle: CxSignScanThrottle = CxSignScanThrottle.shared,
) {

    suspend fun scan(excludeActiveIds: Set<String> = emptySet()): List<CxOpenSign> =
        scanResult(excludeActiveIds).open

    /**
     * [excludeActiveIds] 是本账号已签到成功的活动：服务端 activelist 不会因为已签
     * 改 status，仍返回 status=1；不排除的话「进行中」会在签到成功后一直挂着，
     * 用户会以为没签上。历史/台账仍保留，只影响这里展示的进行中列表。
     */
    suspend fun scanResult(excludeActiveIds: Set<String> = emptySet()): CxSignScanResult {
        // 间隔内的手动刷新直接复用上次结果，不给接口增加负担。
        throttle.cached(nowMs())?.let { cached ->
            Log.i(
                TAG,
                "scan throttled: reuse finishedAt=${cached.finishedAtMs} " +
                    "open=${cached.open.size}",
            )
            return cached.copy(open = cached.open.filterNot { it.activeId in excludeActiveIds })
        }
        val courses = gateway.courses()
        Log.i(TAG, "scan start courses=${courses.size}")
        val found = mutableListOf<CxOpenSign>()
        val lock = Mutex()
        val activesSeen = AtomicInteger(0)
        val skippedSeen = AtomicInteger(0)

        suspend fun scanCourse(course: CxCourse) {
            val actives = try {
                gateway.actives(course)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CxSignRestrictedException) {
                // 一旦命中风控，立刻中止本轮：不再请求剩余课程，避免把限制拖长。
                Log.w(TAG, "scan aborted by restriction until=${e.restriction.untilMs}")
                throw e
            } catch (e: CxException) {
                // 登录失效/风控必须让整次扫描停下；其余只是这一门课没读到。
                if (e.code == CX_CODE_AUTH || e.code == CX_CODE_RISK) throw e
                Log.w(TAG, "scan actives failed course=${course.name} code=${e.code}")
                return
            }
            activesSeen.addAndGet(actives.size)

            val checkinCount = actives.count { it.isCheckin }
            val skipped = actives.count { it.id in excludeActiveIds && it.isCheckin }
            skippedSeen.addAndGet(skipped)
            val opened = actives
                .filter {
                    it.isCheckin && it.status == ACTIVE_STATUS_OPEN && it.id.isNotBlank() &&
                        it.id !in excludeActiveIds
                }
                .map { active ->
                    CxOpenSign(
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
            // 每门课一行：没有活动、活动类型不对、状态不对，都能一眼区分。
            Log.i(
                TAG,
                "course=${course.courseId}/${course.clazzId} name=${course.name} " +
                    "actives=${actives.size} checkin=$checkinCount open=${opened.size} " +
                    "skipped=$skipped",
            )
            for (active in actives) {
                Log.d(
                    TAG,
                    "active id=${active.id} name=${brief(active.name)} type=${active.activeType} " +
                        "status=${active.status} otherId=${active.otherIdValue} " +
                        "start=${active.startTime} end=${active.endTime}",
                )
            }
            // 有活动却没有一条 activeType=2：可能是超星改了类型/状态字段口径。
            if (actives.isNotEmpty() && checkinCount == 0) {
                Log.w(
                    TAG,
                    "no checkin(activeType=$ACTIVE_TYPE_CHECKIN) in course=${course.name} " +
                        "raw=${actives.joinToString { "id=${it.id} type=${it.activeType} status=${it.status}" }}",
                )
            }

            if (opened.isNotEmpty()) lock.withLock { found += opened }
        }

        coroutineScope {
            val next = AtomicInteger(0)
            val workers = minOf(maxParallel, courses.size).coerceAtLeast(1)
            repeat(workers) {
                launch {
                    while (true) {
                        val index = next.getAndIncrement()
                        if (index >= courses.size) return@launch
                        pacer.acquire()
                        scanCourse(courses[index])
                    }
                }
            }
        }

        // 结束时间早的先结束，倒计时最紧。解析不出结束时间的排在最后。
        val sorted = found.sortedWith(
            compareBy(nullsLast()) { sign: CxOpenSign -> sign.endsAtMs },
        )
        val finishedAt = nowMs()
        Log.i(
            TAG,
            "scan done courses=${courses.size} actives=${activesSeen.get()} " +
                "skipped=${skippedSeen.get()} open=${sorted.size}",
        )
        return CxSignScanResult(
            open = sorted,
            coursesScanned = courses.size,
            activesSeen = activesSeen.get(),
            finishedAtMs = finishedAt,
        ).also(throttle::record)
    }

    /** 日志里的活动名截断，避免一条活动名刷满一屏。 */
    private fun brief(text: String, max: Int = 40): String =
        if (text.length <= max) text else text.take(max) + "…"

    private companion object {
        const val TAG = "CxSignScanner"

        /** 与 [CxCheckinApi] 的签到开放状态一致：status=1。 */
        const val ACTIVE_STATUS_OPEN = 1

        /** 签到活动的 activeType；与 [CxCheckinApi.CxActive.isCheckin] 一致。 */
        const val ACTIVE_TYPE_CHECKIN = 2
    }
}

/**
 * 倒计时文案：不足一分钟显示秒，不足一小时显示分秒，其余显示时分。
 * 纯函数，UI 与单测共用。
 */
fun cxRemainText(remainingMs: Long): String {
    val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return when {
        hours > 0 -> "${hours} 小时 ${minutes.toString().padStart(2, '0')} 分"
        minutes > 0 -> "${minutes} 分 ${rest.toString().padStart(2, '0')} 秒"
        else -> "${rest} 秒"
    }
}