package com.sdmu.guanlitong.data.cx

/**
 * 签到/签退的本地状态，按「账号指纹 + activeId + kind」一条记录，存在
 * SharedPreferences 里（与 CxCredStore / AccountStore 同一套约定，不引 Room）。
 *
 * 去重口径（对照老项目 AutoTask）：
 * - 成功就永久去重，不因为重启/换轮次再提交；
 * - 失败不冷却轮，下一轮 90s（有开放活动时的轮询档）就再试，同一活动最多 [CX_SIGN_MAX_ATTEMPTS] 次，
 *   两次之间至少 [CX_SIGN_RETRY_GAP_MS]；签到窗口通常只有 1–5 分钟，冷却会直接
 *   错过；
 * - 活动 status 已结束就不再尝试；
 * - 通知每条活动只发一次：成功一条，或者最后一次失败（放弃）一条；需要手动的
 *   类型也只提醒一次。
 */
const val CX_SIGN_MAX_ATTEMPTS = 5

/** 同一活动两次尝试的最小间隔。 */
const val CX_SIGN_RETRY_GAP_MS = 60_000L

/**
 * RUNNING 记录的逃生阈值：协程在异常路径上死掉而进程没重启时，不能让这条活动
 * 永远等下去；超过这个时间就允许重新尝试（次数已在 runningRecord 里计过）。
 */
const val CX_SIGN_RUNNING_TIMEOUT_MS = 180_000L

/** SharedPreferences 里一条签到记录的状态。 */
enum class CxSignRecordStatus {
    /** 提交中（进程被杀后可能残留，由调度层在启动时视为 FAILED）。 */
    RUNNING,

    /** 已成功，永久去重。 */
    SUCCESS,

    /** 失败过，还可以再试。 */
    FAILED,

    /** 已经试满 [CX_SIGN_MAX_ATTEMPTS]，不再尝试。 */
    GAVE_UP,

    /** 二维码/拍照等只能手动，已经提醒过一次。 */
    MANUAL,

    /**
     * 实时提醒已经提示过一次「发现签到，需要你手动」。
     *
     * 与 [MANUAL] 的区别：自动签到后来打开时，这条记录仍可自动补签（[planSign]
     * 返回 ATTEMPT），不能因为提醒过就把补签挡掉。
     */
    PROMPTED,
}

/** 一条签到/签退记录。 */
data class CxSignRecord(
    val status: CxSignRecordStatus,
    val attempts: Int = 0,
    val atMs: Long = 0,
)

/** 本轮对这条活动的处理决定。 */
enum class CxSignPlan {
    /** 可以提交。 */
    ATTEMPT,

    /** 冷却中或上一次还没跑完，下一轮再看。 */
    WAIT,

    /** 永久跳过：已成功、已放弃、已提醒过手动、或活动已结束。 */
    SKIP,
}

/**
 * 决定本轮是否提交。
 *
 * @param activeEnded 活动 status 已结束（窗口关闭）。
 */
fun planSign(
    record: CxSignRecord?,
    nowMs: Long,
    activeEnded: Boolean,
): CxSignPlan = when {
    record?.status == CxSignRecordStatus.SUCCESS -> CxSignPlan.SKIP
    record?.status == CxSignRecordStatus.GAVE_UP -> CxSignPlan.SKIP
    record?.status == CxSignRecordStatus.MANUAL -> CxSignPlan.SKIP
    activeEnded -> CxSignPlan.SKIP
    record == null -> CxSignPlan.ATTEMPT
    // 实时提醒过不等于放弃：用户后来打开自动签到，这条活动就该补签。
    record.status == CxSignRecordStatus.PROMPTED -> CxSignPlan.ATTEMPT
    record.status == CxSignRecordStatus.RUNNING ->
        if (nowMs - record.atMs >= CX_SIGN_RUNNING_TIMEOUT_MS) CxSignPlan.ATTEMPT
        else CxSignPlan.WAIT
    record.attempts >= CX_SIGN_MAX_ATTEMPTS -> CxSignPlan.SKIP
    nowMs - record.atMs < CX_SIGN_RETRY_GAP_MS -> CxSignPlan.WAIT
    else -> CxSignPlan.ATTEMPT
}

/**
 * 启动清理：进程被杀留下的 RUNNING 记录转成 FAILED（这一轮不会再重复提交，下一轮
 * 按 60s 间隔正常重试）。未超时的 RUNNING 保留，交给 [planSign] 的逃生阈值。
 */
fun recoverStaleRecord(
    record: CxSignRecord,
    nowMs: Long,
    timeoutMs: Long = CX_SIGN_RUNNING_TIMEOUT_MS,
): CxSignRecord = if (
    record.status == CxSignRecordStatus.RUNNING &&
    nowMs - record.atMs >= timeoutMs
) {
    record.copy(status = CxSignRecordStatus.FAILED)
} else {
    record
}

/** 提交前先落这条，进程被杀也能看出「试过几次」。 */
fun runningRecord(previous: CxSignRecord?, nowMs: Long): CxSignRecord =
    CxSignRecord(
        status = CxSignRecordStatus.RUNNING,
        attempts = (previous?.attempts ?: 0) + 1,
        atMs = nowMs,
    )

/**
 * 提交后落结果：成功永久去重，第 [CX_SIGN_MAX_ATTEMPTS] 次失败转 GAVE_UP。
 *
 * 次数只在 [runningRecord] 里加一；如果上一条就是 RUNNING（正常流程），这里沿用
 * 它记下的次数，不能重复计数。
 */
fun attemptedRecord(
    previous: CxSignRecord?,
    nowMs: Long,
    success: Boolean,
): CxSignRecord {
    val attempts = when {
        previous == null -> 1
        previous.status == CxSignRecordStatus.RUNNING -> previous.attempts.coerceAtLeast(1)
        else -> previous.attempts + 1
    }
    val status = when {
        success -> CxSignRecordStatus.SUCCESS
        attempts >= CX_SIGN_MAX_ATTEMPTS -> CxSignRecordStatus.GAVE_UP
        else -> CxSignRecordStatus.FAILED
    }
    return CxSignRecord(status = status, attempts = attempts, atMs = nowMs)
}

/** 需要手动的类型：记一次，之后不再重复提醒。 */
fun manualRecord(previous: CxSignRecord?, nowMs: Long): CxSignRecord =
    CxSignRecord(
        status = CxSignRecordStatus.MANUAL,
        attempts = previous?.attempts ?: 0,
        atMs = nowMs,
    )

/**
 * 实时提醒记录：只标记「提醒过」，不改已有状态与尝试次数。
 *
 * 如果之前是 SUCCESS / MANUAL / GAVE_UP，调用方根本不会走到这里；FAILED 则保留
 * 次数，等用户后来打开自动签到还能接着按 [planSign] 补签。
 */
fun promptRecord(previous: CxSignRecord?, nowMs: Long): CxSignRecord =
    CxSignRecord(
        status = CxSignRecordStatus.PROMPTED,
        attempts = previous?.attempts ?: 0,
        atMs = nowMs,
    )

/** 该发哪条通知；每条活动最多一条结果通知。 */
enum class CxSignNotice { NONE, SUCCESS, GAVE_UP, MANUAL, PROMPT }

fun signNotice(previous: CxSignRecord?, next: CxSignRecord): CxSignNotice = when {
    next.status == CxSignRecordStatus.SUCCESS && previous?.status != CxSignRecordStatus.SUCCESS ->
        CxSignNotice.SUCCESS
    next.status == CxSignRecordStatus.GAVE_UP && previous?.status != CxSignRecordStatus.GAVE_UP ->
        CxSignNotice.GAVE_UP
    next.status == CxSignRecordStatus.MANUAL && previous?.status != CxSignRecordStatus.MANUAL ->
        CxSignNotice.MANUAL
    next.status == CxSignRecordStatus.PROMPTED && previous?.status != CxSignRecordStatus.PROMPTED ->
        CxSignNotice.PROMPT
    else -> CxSignNotice.NONE
}

/**
 * SharedPreferences 的 key。必须带账号指纹：不同账号看同一个 activeId 时不能
 * 互相把对方判成「已签」。
 */
fun signRecordKey(accountKey: String, activeId: String, kind: CxSignKind): String =
    "$accountKey:$activeId:${kind.name.lowercase()}"

/**
 * 3×3 手势九宫格的纯算法：1–9 按阅读顺序编号（左上 1、右上 3、右下 9）。
 *
 * 手势活动的图案每个都不一样，App 只负责把用户当场划出的点序列转成 signCode，
 * 不做任何猜测。放在这里而不是 UI 文件里，是为了让序列生成能直接被 JVM 单测
 * 覆盖，不依赖 Compose。
 */
object CxGesturePattern {

    /** 每行/每列的格数。 */
    const val COLUMNS = 3

    /** 点号上限。 */
    const val POINTS = COLUMNS * COLUMNS

    /**
     * 命中半径 = 格边长 × 这个比例。取 0.35 是让相邻两个点的命中圈不相交
     * （2 × 0.35 < 1），手指划过点与点之间的空隙时不会误加点。
     */
    const val HIT_RATIO = 0.35f

    /**
     * 一次触摸命中的点号（1-based）；超出棋盘或不在任何点的命中圈内返回 null。
     *
     * @param boardSize 棋盘的边长（像素），横竖相等。
     */
    fun hit(x: Float, y: Float, boardSize: Float): Int? {
        if (boardSize <= 0f) return null
        if (x < 0f || y < 0f || x >= boardSize || y >= boardSize) return null
        val cell = boardSize / COLUMNS
        val radius = cell * HIT_RATIO
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        for (index in 1..POINTS) {
            val column = (index - 1) % COLUMNS
            val row = (index - 1) / COLUMNS
            val centerX = (column + 0.5f) * cell
            val centerY = (row + 0.5f) * cell
            val dx = x - centerX
            val dy = y - centerY
            val distance = dx * dx + dy * dy
            if (distance <= radius * radius && distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    /** 追加一点：越界点忽略，重复经过同一点也忽略（手势不允许重点）。 */
    fun append(sequence: List<Int>, point: Int?): List<Int> = when {
        point == null || point !in 1..POINTS -> sequence
        point in sequence -> sequence
        else -> sequence + point
    }

    /** 抬手指纹串，如 [1, 2, 3, 6, 9] → "12369"；空序列得到空串。 */
    fun code(sequence: List<Int>): String = sequence.joinToString("")
}