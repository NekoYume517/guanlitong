package com.sdmu.guanlitong.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sdmu.guanlitong.MainActivity
import com.sdmu.guanlitong.R
import com.sdmu.guanlitong.data.cx.CX_CODE_AUTH
import com.sdmu.guanlitong.data.cx.CX_CODE_RISK
import com.sdmu.guanlitong.data.cx.cxSignPollIntervalMs
import com.sdmu.guanlitong.data.cx.CxCourse
import com.sdmu.guanlitong.data.cx.CxException
import com.sdmu.guanlitong.data.cx.CxRemoteSignGateway
import com.sdmu.guanlitong.data.cx.CxRemoteStudyGateway
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxSignLoopGuard
import com.sdmu.guanlitong.data.cx.CxSignNotice
import com.sdmu.guanlitong.data.cx.CxSignNoticeItem
import com.sdmu.guanlitong.data.cx.CxSignRunner
import com.sdmu.guanlitong.data.cx.CxSignStore
import com.sdmu.guanlitong.data.cx.CxStudyEngine
import com.sdmu.guanlitong.data.cx.CxStudyEvent
import com.sdmu.guanlitong.data.cx.cxSignModeLabel
import com.sdmu.guanlitong.data.cx.displayName
import com.sdmu.guanlitong.data.cx.signAccountKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 学习通前台任务服务：刷课（以及后续签到）共用。
 *
 * 常驻通知显示课程、当前任务点与百分比，可点通知里的「取消」终止；任务结束后
 * 自动摘掉前台通知并停止服务。风控/登录失效等致命错误直接写进终态通知。
 */
class CxTaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var signJob: Job? = null

    /** 签到轮询启停去重：App 启动/课程页/设置页都会请求「确保在跑」。 */
    private val signLoop = CxSignLoopGuard()

    /** 刷课通知节流：上次发出去的进度值（节数或百分比）、文案与时间。 */
    private var lastPostedProgress: Int? = null
    private var lastPostedText: String? = null
    private var lastPostedAtMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        when (intent?.action) {
            // START_STICKY 被系统重新拉起时 intent 为 null：只恢复签到监控。
            null -> return restoreOnStickyRestart()

            ACTION_CANCEL -> {
                job?.cancel()
                CxTaskStatus.finish("已取消")
                notify("已取消")
                // 签到监控还开着就继续跑：摘掉刷课那条通知，把前台交还签到，
                // 不能把服务和签到监控一起停掉。
                return if (signJob?.isActive == true) {
                    cancelStudyNotification()
                    startSignForeground(SIGN_FOREGROUND_TEXT)
                    START_STICKY
                } else {
                    stopForegroundAndSelf()
                    START_NOT_STICKY
                }
            }

            ACTION_START_SIGN -> {
                // startForegroundService 必须在前 5s 内进入前台；刷课在跑时服务已经
                // 是前台，不必再发签到常驻通知（两者的通知 id 相互独立，不会互顶）。
                if (!studyRunning()) startSignForeground(SIGN_FOREGROUND_TEXT)
                // 已经在轮询就不重启：App 启动、课程页、设置页都会来「确保在跑」。
                if (!signLoop.begin()) {
                    Log.i(TAG, "sign loop already running, ignore start")
                    return START_STICKY
                }
                signJob?.cancel()
                signJob = scope.launch {
                    try {
                        runSignLoop()
                    } finally {
                        signLoop.end()
                    }
                }
            }

            ACTION_STOP_SIGN -> {
                signLoop.end()
                signJob?.cancel()
                signJob = null
                CxSignStatus.finish("已停止签到监控")
                if (job?.isActive != true) stopForegroundAndSelf()
                return START_NOT_STICKY
            }

            ACTION_START_STUDY, ACTION_START_TASK -> {
                val course = courseFrom(intent)
                if (course == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val onlyJobid = intent.getStringExtra(EXTRA_JOBID)?.takeIf { it.isNotBlank() }
                startForegroundNotification("${course.name} · 准备中")
                job?.cancel()
                job = scope.launch { runStudy(course, onlyJobid) }
            }
        }
        // 默认粘性：签到监控或刷课被系统杀掉后由系统重新拉起，null intent 分支
        // 会按当前登录/开关状态决定恢复签到还是退出。
        return START_STICKY
    }

    /**
     * 任务列表被划掉：不立刻 startForegroundService（系统正在清理时容易被拒），
     * 5 秒后用闹钟广播再确认一次。登录失效或开关关闭就不排。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val appContext = applicationContext
            val monitorEnabled = CxSignStore(appContext).settings().monitorEnabled
            val loggedIn = CxRepository(appContext).loggedIn
            if (shouldScheduleTaskRemovedRestart(loggedIn, monitorEnabled)) {
                CxKeepAlive.scheduleRestart(appContext)
                Log.i(TAG, "task removed: schedule sign restart in 5s")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "task removed: schedule failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    /** START_STICKY 重启（intent=null）：应开监控就重新进前台并拉起轮询。 */
    private fun restoreOnStickyRestart(): Int {
        val appContext = applicationContext
        val monitorEnabled: Boolean
        val loggedIn: Boolean
        try {
            monitorEnabled = CxSignStore(appContext).settings().monitorEnabled
            loggedIn = CxRepository(appContext).loggedIn
        } catch (e: Throwable) {
            Log.w(TAG, "sticky restart: state read failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!loggedIn || !monitorEnabled) {
            Log.i(TAG, "sticky restart: no monitor, stop")
            stopSelf()
            return START_NOT_STICKY
        }

        // 重启后刷课 job 已经没了，前台通知归签到用。
        startSignForeground(SIGN_FOREGROUND_TEXT)
        if (signLoop.begin()) {
            signJob?.cancel()
            signJob = scope.launch {
                try {
                    runSignLoop()
                } finally {
                    signLoop.end()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        signLoop.end()
        job?.cancel()
        signJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runStudy(course: CxCourse, onlyJobid: String? = null) {
        Log.i(TAG, "start course=${course.name} onlyJobid=$onlyJobid")
        CxTaskStatus.start(course.name, "扫描章节")
        notify("${course.name} · 扫描章节")

        val gateway = CxRemoteStudyGateway(CxRepository(applicationContext))
        val summary = try {
            CxStudyEngine(gateway).run(course, onlyJobid) { handleEvent(it) }
        } catch (e: CancellationException) {
            CxTaskStatus.finish("已取消")
            notify("已取消")
            finishStudyForeground()
            throw e
        }

        val error = summary.fatalError
        val missed = summary.missedChapters
        val missedText = if (missed.size <= 3) {
            missed.joinToString("、")
        } else {
            missed.take(3).joinToString("、") + " 等 ${missed.size} 章"
        }
        val text = when {
            error != null && missed.isNotEmpty() ->
                "部分失败：通过 ${summary.passed}/${summary.total}，$missedText 未取到" +
                    "（未计入完成）；重扫中止：$error"
            error != null -> "已中止：$error"
            summary.cancelled -> "已取消"
            missed.isNotEmpty() ->
                "部分失败：通过 ${summary.passed}/${summary.total}，$missedText 未取到，未计入完成"
            summary.total == 0 -> "没有未通过的音视频任务点"
            summary.allPassed -> "完成：${summary.total} 个音视频任务点全部通过"
            else -> "结束：通过 ${summary.passed}/${summary.total}，未通过 ${summary.failed}"
        }
        Log.i(TAG, "finish course=${course.name} total=${summary.total} " +
            "passed=${summary.passed} failed=${summary.failed} " +
            "missed=${missed.size} error=$error text=$text")
        if (missed.isNotEmpty()) Log.w(TAG, "partial missed=${missed.joinToString("、")}")
        CxTaskStatus.finish(
            message = text,
            fatalError = error,
            incomplete = missed.takeIf { it.isNotEmpty() }?.let { "${it.size} 章未取到" },
        )
        notify(text, final = true)
        finishStudyForeground()
    }

    /**
 * 自动签到轮询：有开放活动时 90s 一轮，没有时 5 分钟一轮；每轮最多扫 4 门课，
 * 只拉活动列表；与刷课共用一个服务，但不共用任务状态。
 * 登录失效 / 风控直接停止并在通知里说明，不重试风暴。
 */
private suspend fun runSignLoop() {
    val store = CxSignStore(applicationContext)
    if (!store.settings().monitorEnabled) {
        CxSignStatus.finish("未开启签到实时提醒")
        notifySignStop("签到实时提醒未开启，监控已停止")
        stopIfIdle()
        return
    }
    val repo = CxRepository(applicationContext)
    if (!repo.loggedIn) {
        CxSignStatus.finish("未登录学习通")
        notifySignStop("未登录学习通，签到监控未启动")
        stopIfIdle()
        return
    }

    val gateway = CxRemoteSignGateway(repo)
    CxSignStatus.start()
    // 只要轮询真的跑起来，就把心跳 / 周期兜底接上（重复调用幂等）。
    CxKeepAlive.enable(applicationContext)
    Log.i(TAG, "sign loop start")
    while (currentCoroutineContext().isActive) {
        val accountKey = try {
            signAccountKey(repo.withSession { it })
        } catch (e: CancellationException) {
            throw e
        } catch (e: CxException) {
            stopSignLoopWith(
                if (e.code == CX_CODE_AUTH) "登录已失效，签到监控已停止" else "学习通要求验证，签到监控已停止",
            )
            return
        }

        val runner = CxSignRunner(gateway, store, accountKey, { store.settings() })
        try {
            val notices = runner.poll()
            notices.forEach(::notifySignResult)
            CxSignStatus.pulse(notices.size)
            // 刷课在跑时前台通知归刷课（独立 id），签到先不在每轮重复发常驻通知。
            if (!studyRunning()) notifySignForeground(SIGN_FOREGROUND_TEXT)
        } catch (e: CancellationException) {
            CxSignStatus.finish("已停止签到监控")
            throw e
        } catch (e: CxException) {
            stopSignLoopWith(
                if (e.code == CX_CODE_AUTH) "登录已失效，签到监控已停止" else "学习通要求验证，签到监控已停止",
            )
            return
        } catch (e: Exception) {
            Log.w(TAG, "sign poll failed ${e.javaClass.simpleName}")
        }
        // 自适应节奏：上一轮扫到开放活动才用 90s 高频档，否则 5 分钟一轮。
        val nextMs = cxSignPollIntervalMs(runner.lastPollHadOpenActivity)
        Log.i(
            TAG,
            "sign poll next in ${nextMs}ms open=${runner.lastPollHadOpenActivity}",
        )
        delay(nextMs)
    }
}

private fun stopSignLoopWith(message: String) {
    Log.w(TAG, "sign loop stopped: $message")
    CxSignStatus.finish(message)
    notifySignStop(message)
    stopIfIdle()
}

/** 刷课还在跑就不摘前台通知，只更新签到状态。 */
private fun stopIfIdle() {
    if (!studyRunning()) stopForegroundAndSelf()
}

/** 刷课 job 是否在跑；它在跑时前台通知归它用，签到只发结果通知。 */
private fun studyRunning(): Boolean = job?.isActive == true

/**
 * 前台服务类型：Android 14+ 用 specialUse（manifest 里带 subtype 说明），更老系统
 * 没有这个类型，传 0。specialUse 不在 Android 15「BOOT_COMPLETED 禁止启动」的
 * dataSync/camera 等名单里，开机恢复才拉得起来。
 */
private val signForegroundType: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
        0
    }

/** 签到模式的前台通知（id 与刷课独立，谁在跑都不互相顶掉）。 */
private fun startSignForeground(text: String) {
    ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        signNotification(text, ongoing = true),
        signForegroundType,
    )
}

private fun notifySignForeground(text: String) {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    manager.notify(NOTIFICATION_ID, signNotification(text, ongoing = true))
}

private fun signNotification(text: String, ongoing: Boolean): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("学习通 · 签到监控")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .setContentIntent(openAppIntent())
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

/** 签到停摆（未开启/未登录/登录失效/风控）单独发一条，别被前台通知摘掉。 */
private fun notifySignStop(message: String) {
    ensureSignChannel()
    val manager = getSystemService(NotificationManager::class.java) ?: return
    manager.notify(
        SIG_NOTIFICATION_BASE,
        NotificationCompat.Builder(this, SIGN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("签到监控已停止")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build(),
    )
}

/**
 * 每条活动只发一条结果通知；重复的 key 用活动 id 覆盖，不会刷屏。
 *
 * 签到/签退成功与需要手动处理走「重要提醒」渠道（横幅 + 声音）：手势签到要
 * 用户当场划图案，静默通知会被直接错过。只有最终失败仍留在普通签到渠道。
 */
private fun notifySignResult(item: CxSignNoticeItem) {
    when (item.notice) {
        CxSignNotice.SUCCESS -> {
            CxSignManualNotify.notifySuccess(
                this,
                "${item.kind.label}成功 - ${item.courseName}",
                "${item.activeName} · ${item.message}",
            )
            return
        }
        CxSignNotice.MANUAL -> {
            CxSignManualNotify.notifyManual(
                this,
                activeId = item.activeId,
                courseId = item.courseId,
                classId = item.classId,
                courseName = item.courseName,
                reason = item.message,
                // 手势带活动参数直达九宫格；二维码/拍照只能进收件箱。
                gesture = item.otherId == 3,
            )
            return
        }
        CxSignNotice.PROMPT -> {
            CxSignManualNotify.notifyPrompt(
                this,
                activeId = item.activeId,
                title = "发现${item.kind.label} · 点击去处理",
                body = "${item.courseName} · ${item.activeName}" +
                    "（${cxSignModeLabel(item.otherId)}）\n${item.message}",
            )
            return
        }
        CxSignNotice.GAVE_UP -> Unit
        CxSignNotice.NONE -> return
    }

    ensureSignChannel()
    val manager = getSystemService(NotificationManager::class.java) ?: return
    manager.notify(
        SIG_NOTIFICATION_BASE + 1 + (item.activeId.hashCode() and 0xFFFF),
        NotificationCompat.Builder(this, SIGN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${item.courseName} · ${item.activeName} 失败")
            .setContentText(item.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build(),
    )
}

private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
    this,
    2,
    Intent(this, MainActivity::class.java),
    PENDING_FLAGS,
)

private fun ensureSignChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(SIGN_CHANNEL_ID) != null) return
    manager.createNotificationChannel(
        NotificationChannel(SIGN_CHANNEL_ID, "签到提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "自动签到/签退的结果"
        },
    )
}

private fun handleEvent(event: CxStudyEvent) {
        when (event) {
            is CxStudyEvent.Scanned -> {
                val text = buildString {
                    append("待刷 ${event.total} 个任务点")
                    if (event.videoCount > 0 || event.audioCount > 0) {
                        append("（视频 ${event.videoCount} / 音频 ${event.audioCount}）")
                    }
                    if (event.missedChapters.isNotEmpty()) {
                        append("，${event.missedChapters.size} 章未取到")
                    }
                }
                CxTaskStatus.update {
                    it.copy(
                        phase = if (event.round == 1) "第 1 轮" else "第 2 轮重刷",
                        total = event.total,
                        currentIndex = 0,
                        message = text,
                    )
                }
                notify(text)
            }

            is CxStudyEvent.TaskStarted -> {
                CxTaskStatus.update {
                    it.copy(
                        phase = if (event.round == 1) "第 1 轮" else "第 2 轮重刷",
                        currentIndex = event.index,
                        total = event.total,
                        currentTask = event.task.card.displayName,
                        position = 0,
                        duration = 0,
                    )
                }
                notify("${event.index}/${event.total} ${event.task.card.displayName}")
            }

            is CxStudyEvent.TaskProgress -> {
                CxTaskStatus.update {
                    it.copy(
                        currentIndex = event.index,
                        total = event.total,
                        position = event.progress.position,
                        duration = event.progress.duration,
                    )
                }
                notify(
                    "${event.index}/${event.total} ${event.task.card.displayName} " +
                        "${event.progress.percent}%",
                )
            }

            is CxStudyEvent.TaskFinished -> {
                CxTaskStatus.update { it.copy(message = event.message) }
            }

            is CxStudyEvent.Relogin -> {
                CxTaskStatus.update { it.copy(message = event.reason) }
                notify(event.reason)
            }

            is CxStudyEvent.Finished -> Unit // 终态由 runStudy 统一处理
        }
    }

    private fun startForegroundNotification(text: String) {
        // 新一轮刷课重新计时：第一条真实进度通知立刻发，之后才走 10s 节流。
        lastPostedProgress = null
        lastPostedText = null
        lastPostedAtMillis = 0L
        ServiceCompat.startForeground(
            this,
            STUDY_NOTIFICATION_ID,
            buildNotification(text),
            signForegroundType,
        )
    }

    /**
     * 刷课通知：终态必发；进度更新要「进度值或文案变了」且距上次 ≥10s，
     * 避免高频 notify 被系统限流。被节流时直接 return，状态本身已写进
     * [CxTaskStatus]，下一次允许发的时候自然带上最新文案。
     */
    private fun notify(text: String, final: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val state = CxTaskStatus.state.value
        val progress = CxStudyNoticeProgress.scale(state.currentIndex, state.total, state.taskPercent)
        val now = SystemClock.elapsedRealtime()
        val changed = text != lastPostedText || progress.value != lastPostedProgress
        val shouldPost = CxStudyNoticeProgress.shouldPost(
            final = final,
            changed = changed,
            lastPostAtMillis = lastPostedText?.let { lastPostedAtMillis },
            nowMillis = now,
        )
        if (!shouldPost) return
        lastPostedProgress = progress.value
        lastPostedText = text
        lastPostedAtMillis = now
        val builder = notificationBuilder(text)
        if (final) builder.setOngoing(false)
        manager.notify(STUDY_NOTIFICATION_ID, builder.build())
    }

    private fun buildNotification(text: String): Notification = notificationBuilder(text).build()

    private fun notificationBuilder(text: String): NotificationCompat.Builder {
        val state = CxTaskStatus.state.value
        val cancelIntent = Intent(this, CxTaskService::class.java).setAction(ACTION_CANCEL)
        val cancel = PendingIntent.getService(this, 1, cancelIntent, PENDING_FLAGS)
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PENDING_FLAGS,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("学习通 · ${state.courseName.ifEmpty { "刷课" }}")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(state.running)
            .setContentIntent(open)
            .addAction(0, "取消", cancel)
            .apply {
                if (state.running) {
                    // 有总节数按节数刻度；拿不到就退化为百分比刻度。
                    val progress = CxStudyNoticeProgress.scale(
                        state.currentIndex,
                        state.total,
                        state.taskPercent,
                    )
                    setProgress(progress.max, progress.value, false)
                } else {
                    // 结束/失败/取消：明确清掉进度条。
                    setProgress(0, 0, false)
                }
            }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 刷课收尾：摘掉刷课常驻通知；签到还在跑就把前台通知交还签到，否则停服务。
     * 不能直接用 [stopForegroundAndSelf]，那会把签到监控一起停掉。
     */
    private fun finishStudyForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (signJob?.isActive == true) {
            startSignForeground(SIGN_FOREGROUND_TEXT)
        } else {
            stopSelf()
        }
    }

    /** 只摘掉刷课那条（id 与签到相互独立），签到通知不受影响。 */
    private fun cancelStudyNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(STUDY_NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "学习通任务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "刷课与签到任务进度"
                setShowBadge(false)
            },
        )
    }

    private fun courseFrom(intent: Intent): CxCourse? {
        val courseId = intent.getStringExtra(EXTRA_COURSE_ID) ?: return null
        return CxCourse(
            courseId = courseId,
            clazzId = intent.getStringExtra(EXTRA_CLAZZ_ID).orEmpty(),
            personId = intent.getStringExtra(EXTRA_PERSON_ID).orEmpty(),
            name = intent.getStringExtra(EXTRA_COURSE_NAME).orEmpty().ifEmpty { "学习通课程" },
            teacher = "",
            coverUrl = "",
            link = "",
        )
    }

    companion object {
        private const val TAG = "CxTaskService"
        private const val CHANNEL_ID = "cx_task"
        private const val NOTIFICATION_ID = 2001

        /** 刷课常驻通知独立 id：与签到的 [NOTIFICATION_ID] 互不顶掉。 */
        private const val STUDY_NOTIFICATION_ID = 2002

        /** 前台常驻通知的静默文案（渠道本身是 LOW，不发声）。 */
        private const val SIGN_FOREGROUND_TEXT = "签到实时监控中"
        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        const val ACTION_START_STUDY = "com.sdmu.guanlitong.cx.START_STUDY"
        const val ACTION_START_TASK = "com.sdmu.guanlitong.cx.START_TASK"
        const val ACTION_CANCEL = "com.sdmu.guanlitong.cx.CANCEL"
        const val ACTION_START_SIGN = "com.sdmu.guanlitong.cx.START_SIGN"
        const val ACTION_STOP_SIGN = "com.sdmu.guanlitong.cx.STOP_SIGN"

        /** 签到结果通知的 id 段：每条活动一个，重复提醒会覆盖同一条。 */
        private const val SIG_NOTIFICATION_BASE = 2100
        private const val SIGN_CHANNEL_ID = "cx_sign"

        private const val EXTRA_COURSE_ID = "courseId"
        private const val EXTRA_CLAZZ_ID = "clazzId"
        private const val EXTRA_PERSON_ID = "personId"
        private const val EXTRA_COURSE_NAME = "courseName"
        private const val EXTRA_JOBID = "jobid"

        /** @param jobid 非空时只刷这个任务点。 */
        fun startStudy(context: Context, course: CxCourse, jobid: String? = null) {
            val intent = Intent(context, CxTaskService::class.java)
                .setAction(if (jobid == null) ACTION_START_STUDY else ACTION_START_TASK)
                .putExtra(EXTRA_COURSE_ID, course.courseId)
                .putExtra(EXTRA_CLAZZ_ID, course.clazzId)
                .putExtra(EXTRA_PERSON_ID, course.personId)
                .putExtra(EXTRA_COURSE_NAME, course.name)
                .putExtra(EXTRA_JOBID, jobid)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, CxTaskService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }

        /** 开启自动签到后调用；开关关闭时调 [stopSign]。 */
        fun startSign(context: Context) {
            val intent = Intent(context, CxTaskService::class.java).setAction(ACTION_START_SIGN)
            context.startForegroundService(intent)
        }

        fun stopSign(context: Context) {
            val intent = Intent(context, CxTaskService::class.java).setAction(ACTION_STOP_SIGN)
            context.startService(intent)
        }
    }
}