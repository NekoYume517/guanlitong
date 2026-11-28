package com.sdmu.guanlitong.data.zhs

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 定时刷课的可观察任务状态，字段与参考实现 `tbs_states` 一一对应：
 * `running` / `course_name` / `target` / `gained` / `current` / `current_name` /
 * `current_progress` / `lessons_done` / `total_lessons` / `speed` / `captcha` /
 * `log`（最多 30 条，带 HH:mm:ss）/ `lessons`（lessonId → 每节实时进度）。
 *
 * 旧项目是「后台线程写字典 + 前端 3 秒轮询」；这里换成进程内 [StateFlow]，
 * 界面直接订阅，通知服务也读同一份状态，不再需要轮询接口。
 */
enum class ZhsTaskMode { NONE, BATCH, ITEM }

/** 一节在任务列表里的状态，对应旧项目的 running / done / captcha。 */
enum class ZhsLessonStatus { RUNNING, DONE, CAPTCHA }

/** 某节任务的实时进度（旧项目 `tbs_states.lessons[lessonId]`）。 */
data class ZhsLessonTask(
    val name: String = "",
    val total: Int = 0,
    val progress: Int = 0,
    val status: ZhsLessonStatus? = null,
    /** 阶段 A 用开始顺序的序号；阶段 B 用 `R{轮次}`，排序规则与旧前端一致。 */
    val index: String = "",
    val gained: Int? = null,
    val speed: String = "",
    val answered: Int = 0,
)

data class ZhsTaskState(
    val mode: ZhsTaskMode = ZhsTaskMode.NONE,
    val running: Boolean = false,
    /** 本次任务开始时间，浮球展开列表按它排序。 */
    val startedAt: Long = 0,
    val courseId: Long = 0,
    val courseName: String = "",
    /** 本次目标新增秒数；单节刷课为 0。 */
    val target: Int = 0,
    val gained: Int = 0,
    /** 「i/n」或「第N轮」。 */
    val current: String = "",
    val currentName: String = "",
    val currentProgress: Int = 0,
    val lessonsDone: Int = 0,
    val totalLessons: Int = 0,
    val speed: ZhsSpeedChoice = ZhsSpeedChoice.DEFAULT,
    val captcha: Boolean = false,
    val log: List<String> = emptyList(),
    val lessons: Map<Long, ZhsLessonTask> = emptyMap(),
    /** 章节页每行视频的实时进度，键为 (lessonId, lessonVideoId)。 */
    val items: Map<Pair<Long, Long>, ZhsStudyProgress> = emptyMap(),
    /** 结束后的结果文案（达标 / 结束 / 已停止）。 */
    val summary: String? = null,
    val error: String? = null,
    val expired: Boolean = false,
    /** 最近一次状态变化的时间，日志里用来判断任务是否还在推进。 */
    val updatedAt: Long = 0,
) {
    val speedText: String get() = speedLabel(speed)

    /** 达标进度 0..100；没有目标时恒为 0。 */
    val percent: Int
        get() = if (target > 0) min(100, gained * 100 / target) else 0

    /**
     * 本轮是否已经有终态结果。
     *
     * 区分「还没开始」（mode 仍是 NONE）与「已经结束」：前台服务只有在后者时
     * 才发终态通知并停掉自己，避免启动瞬间把「未开始」误判成「已结束」。
     */
    val hasResult: Boolean
        get() = mode != ZhsTaskMode.NONE && !running
}

/** 前台服务读到某个状态时该做什么。 */
enum class ZhsServicePhase {
    /** 任务还没开始：保持前台继续等状态变化，不发终态、不停服务。 */
    WAIT,

    /** 任务在跑：续报进度并持有唤醒锁。 */
    RUNNING,

    /** 已有终态结果：发终态通知、释放唤醒锁、停服务。 */
    FINISHED,
}

fun ZhsTaskState.servicePhase(): ZhsServicePhase = when {
    running -> ZhsServicePhase.RUNNING
    hasResult -> ZhsServicePhase.FINISHED
    else -> ZhsServicePhase.WAIT
}

/** 倍速展示文案，与旧前端 `brushSpeed.label()` 一致。 */
fun speedLabel(choice: ZhsSpeedChoice): String =
    if (choice.custom) "自定义 ${ZhsBrushPlanner.formatG(choice.rate)}x" else "默认"

/** 秒数 →「X分YY秒」（旧项目 `fmSec`）。 */
fun formatSpan(sec: Int): String {
    val safe = max(0, sec)
    return "${safe / 60}分${(safe % 60).toString().padStart(2, '0')}秒"
}

/** 秒数 →「MM:SS」（旧项目 `fmClock`）。 */
fun formatClock(sec: Int): String {
    val safe = max(0, sec)
    return "${(safe / 60).toString().padStart(2, '0')}:${(safe % 60).toString().padStart(2, '0')}"
}

/**
 * 任务状态机：纯逻辑，不碰网络与 Android，单测直接喂事件断言状态。
 * 网络任务由 [ZhsTaskController] 驱动，回调映射到这里。
 */
class ZhsTaskStateMachine(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val stamp: (Long) -> String = { time ->
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
    },
) {

    private val _state = MutableStateFlow(ZhsTaskState())
    val state: StateFlow<ZhsTaskState> = _state.asStateFlow()

    fun beginBatch(courseId: Long, courseName: String, targetSec: Int, speed: ZhsSpeedChoice) {
        emit(
            ZhsTaskState(
                mode = ZhsTaskMode.BATCH,
                running = true,
                startedAt = clock(),
                courseId = courseId,
                courseName = courseName,
                target = targetSec,
                speed = speed,
                log = listOf("准备开始..."),
            ),
        )
    }

    fun beginItem(courseId: Long, courseName: String, item: ZhsFlatItem, speed: ZhsSpeedChoice) {
        emit(
            ZhsTaskState(
                mode = ZhsTaskMode.ITEM,
                running = true,
                startedAt = clock(),
                courseId = courseId,
                courseName = courseName,
                target = 0,
                current = "1/1",
                currentName = item.name,
                totalLessons = 1,
                speed = speed,
                lessons = mapOf(
                    item.lessonId to ZhsLessonTask(
                        name = item.name,
                        total = item.sec,
                        status = ZhsLessonStatus.RUNNING,
                        index = "1",
                    ),
                ),
                log = listOf("准备开始..."),
            ),
        )
    }

    fun onProgress(progress: ZhsStudyProgress) {
        val st = _state.value
        if (!st.running) return
        val lessonName = if (progress.round > 0) "[最短] ${progress.name}" else progress.name
        val indexLabel = if (progress.round > 0) "R${progress.round}" else progress.index.toString()
        val prev = st.lessons[progress.lessonId] ?: ZhsLessonTask()
        val lesson = when (progress.status) {
            ZhsStudyStatus.RUNNING -> prev.copy(
                name = lessonName,
                total = if (progress.videoSec > 0) progress.videoSec else prev.total,
                progress = progress.percent.coerceIn(0, 100),
                status = ZhsLessonStatus.RUNNING,
                index = indexLabel,
                speed = progress.speedLabel,
            )

            ZhsStudyStatus.DONE -> prev.copy(
                name = lessonName,
                status = ZhsLessonStatus.DONE,
                progress = 100,
                gained = progress.addedSec,
                answered = prev.answered + progress.answered,
            )

            ZhsStudyStatus.CAPTCHA -> prev.copy(
                name = lessonName,
                status = ZhsLessonStatus.CAPTCHA,
            )
        }
        var log = st.log
        var totalLessons = st.totalLessons
        if (st.mode == ZhsTaskMode.BATCH && totalLessons == 0 && progress.round == 0) {
            totalLessons = progress.total
            log = append(log, "共 ${progress.total} 个视频, 目标 ${st.target / 60} 分钟")
        }
        val done = progress.status == ZhsStudyStatus.DONE
        emit(
            st.copy(
                gained = if (done) st.gained + progress.addedSec else st.gained,
                lessonsDone = if (done) st.lessonsDone + 1 else st.lessonsDone,
                totalLessons = totalLessons,
                current = if (progress.round > 0) "第${progress.round}轮" else "${progress.index}/${progress.total}",
                currentName = lessonName,
                currentProgress = when {
                    done -> 100
                    progress.round == 0 -> progress.percent
                    else -> st.currentProgress
                },
                captcha = st.captcha || progress.status == ZhsStudyStatus.CAPTCHA,
                lessons = st.lessons + (progress.lessonId to lesson),
                items = st.items + ((progress.lessonId to progress.lessonVideoId) to progress),
                log = log,
            ),
        )
    }

    fun onLog(message: String) {
        val st = _state.value
        emit(st.copy(log = append(st.log, message)))
    }

    fun chooseSpeed(choice: ZhsSpeedChoice) {
        val st = _state.value
        emit(
            st.copy(
                speed = choice,
                log = append(st.log, "切换倍速: ${speedLabel(choice)}"),
            ),
        )
    }

    /** 与旧项目一致：只发出停止信号，当前这节刷完才停。 */
    fun stop() {
        val st = _state.value
        if (!st.running) return
        emit(st.copy(log = append(st.log, "用户手动停止")))
    }

    fun finish(outcome: ZhsStudyOutcome) {
        val st = _state.value
        val targetMet = outcome.targetSec > 0 && outcome.addedSec >= outcome.targetSec
        val summary = when {
            outcome.captcha -> "触发滑块验证，刷课已停止"
            outcome.cancelled -> "已停止：本次累计新增 ${formatSpan(outcome.addedSec)}"
            targetMet -> "达标：本次累计新增 ${formatSpan(outcome.addedSec)} / 目标 ${formatSpan(outcome.targetSec)}"
            outcome.targetSec > 0 ->
                "结束：本次累计新增 ${formatSpan(outcome.addedSec)} / 目标 ${formatSpan(outcome.targetSec)}"

            else -> "完成：本次累计新增 ${formatSpan(outcome.addedSec)}，刷了 ${outcome.brushedCount} 节"
        }
        var log = st.log
        if (!outcome.captcha) {
            log = append(
                log,
                if (targetMet) "🎯 达标: 累计新增 ${formatSpan(outcome.addedSec)}"
                else "结束: 累计新增 ${formatSpan(outcome.addedSec)}",
            )
        }
        emit(
            st.copy(
                running = false,
                gained = max(st.gained, outcome.addedSec),
                current = "",
                currentName = "",
                captcha = st.captcha || outcome.captcha,
                summary = summary,
                log = log,
            ),
        )
    }

    fun fail(message: String, expired: Boolean = false) {
        val st = _state.value
        emit(
            st.copy(
                running = false,
                current = "",
                currentName = "",
                error = message,
                expired = expired,
                log = append(st.log, "异常: $message"),
            ),
        )
    }

    /** 所有状态写入的唯一出口：顺手盖上最近更新时间。 */
    private fun emit(state: ZhsTaskState) {
        _state.value = state.copy(updatedAt = clock())
    }

    private fun append(log: List<String>, message: String): List<String> =
        (log + "${stamp(clock())} $message").takeLast(LOG_LIMIT)

    private companion object {
        const val LOG_LIMIT = 30
    }
}