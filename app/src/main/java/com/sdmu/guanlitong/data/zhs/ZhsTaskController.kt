package com.sdmu.guanlitong.data.zhs

import android.content.Context
import com.sdmu.guanlitong.service.BrushTaskRegistry
import com.sdmu.guanlitong.service.BrushTaskRoutes
import com.sdmu.guanlitong.service.BrushTaskSnapshot
import com.sdmu.guanlitong.service.BrushTaskSource
import com.sdmu.guanlitong.service.ZhsTaskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 知到刷课任务的进程内单例：持有状态机、倍速控制器与后台协程。
 *
 * 对应旧项目的「`tbs_states` 字典 + 后台线程」：
 * - 界面订阅 [state] 拿实时进度，不再轮询；
 * - [startBatch] / [startItem] 起任务并拉起 [ZhsTaskService]（常驻通知）；
 * - [stop] 只发停止信号，当前这节刷完才停（旧项目 `/api/brush-time-stop` 语义）；
 * - [chooseSpeed] 运行中立即改档，下一次上报即生效（旧项目 `/api/brush-speed`）。
 *
 * 同一时刻只跑一个刷课任务；任务结束后状态保留，供结果页回看。
 */
object ZhsTaskController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val machine = ZhsTaskStateMachine()
    private val speed = ZhsSpeedController()

    @Volatile
    private var stopRequested = false
    private var job: Job? = null
    private var repository: ZhsRepository? = null

    val state: StateFlow<ZhsTaskState> = machine.state

    /** 运行中的任务直接读它改倍速；界面上的档位选中状态也从这里取。 */
    val speedController: ZhsSpeedController get() = speed

    val speedChoice: ZhsSpeedChoice get() = speed.choice

    /** 注册到共享任务表：浮球据此显示知到任务；结束时移除，终态通知不受影响。 */
    init {
        scope.launch {
            machine.state.collect { st ->
                when {
                    st.mode == ZhsTaskMode.NONE -> Unit
                    st.running -> BrushTaskRegistry.register(snapshot(st))
                    else -> BrushTaskRegistry.unregister(TASK_ID)
                }
            }
        }
    }

    private fun snapshot(st: ZhsTaskState): BrushTaskSnapshot = BrushTaskSnapshot(
        id = TASK_ID,
        source = BrushTaskSource.ZHS_STUDY,
        title = st.courseName,
        subtitle = if (st.mode == ZhsTaskMode.ITEM) "单节刷课" else "定时刷课",
        progress = if (st.target > 0) st.percent / 100f else st.currentProgress / 100f,
        statusText = if (st.target > 0) {
            "新增 ${formatSpan(st.gained)} / ${formatSpan(st.target)} · ${st.current}"
        } else {
            "本节 ${st.currentProgress}%"
        },
        running = st.running,
        startedAt = st.startedAt,
        route = BrushTaskRoutes.ZHS_STUDY,
    )

    fun startBatch(
        context: Context,
        course: ZhsCourse,
        chapters: List<ZhsChapter>,
        minutes: Int,
        choice: ZhsSpeedChoice = speed.choice,
    ) {
        if (state.value.running) return
        start(context, choice) { repo ->
            machine.beginBatch(course.courseId, course.name, minutes * 60, choice)
            val outcome = repo.brush(
                course = course,
                chapters = chapters,
                speed = speed,
                targetSec = minutes * 60,
                onProgress = machine::onProgress,
                onLog = machine::onLog,
                shouldStop = { stopRequested },
            )
            machine.finish(outcome)
        }
    }

    fun startItem(
        context: Context,
        course: ZhsCourse,
        item: ZhsFlatItem,
        choice: ZhsSpeedChoice = speed.choice,
    ) {
        if (state.value.running) return
        start(context, choice) { repo ->
            machine.beginItem(course.courseId, course.name, item, choice)
            val outcome = repo.brushItem(
                course = course,
                item = item,
                speed = speed,
                onProgress = machine::onProgress,
                onLog = machine::onLog,
            )
            machine.finish(outcome)
        }
    }

    /** 发停止信号；正在跑的任务在当前这节结束后收尾。 */
    fun stop() {
        if (!state.value.running) return
        stopRequested = true
        machine.stop()
    }

    /** 运行中改倍速：下一次上报前生效。 */
    fun chooseSpeed(choice: ZhsSpeedChoice) {
        speed.choice = choice
        machine.chooseSpeed(choice)
    }

    private fun start(
        context: Context,
        choice: ZhsSpeedChoice,
        block: suspend (ZhsRepository) -> Unit,
    ) {
        stopRequested = false
        speed.choice = choice
        val appContext = context.applicationContext
        job = scope.launch {
            try {
                val repo = repository ?: ZhsRepository(appContext).also { repository = it }
                block(repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                machine.fail(e.zhsMessage(), expired = (e as? ZhsException)?.kind == ZhsErrorKind.EXPIRED)
            }
        }
        ZhsTaskService.start(appContext)
    }

    private const val TASK_ID = "zhs.study"
}