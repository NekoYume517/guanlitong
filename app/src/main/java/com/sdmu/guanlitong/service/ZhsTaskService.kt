package com.sdmu.guanlitong.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sdmu.guanlitong.MainActivity
import com.sdmu.guanlitong.R
import com.sdmu.guanlitong.data.zhs.ZhsServicePhase
import com.sdmu.guanlitong.data.zhs.ZhsTaskController
import com.sdmu.guanlitong.data.zhs.ZhsTaskMode
import com.sdmu.guanlitong.data.zhs.ZhsTaskNoticeBuilder
import com.sdmu.guanlitong.data.zhs.ZhsTaskState
import com.sdmu.guanlitong.data.zhs.formatSpan
import com.sdmu.guanlitong.data.zhs.servicePhase
import com.sdmu.guanlitong.ui.ZhsDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 知到刷课的常驻通知服务，结构照搬 [CxTaskService] 的刷课部分：
 *
 * - 任务由 [ZhsTaskController] 在进程内跑，本服务只负责前台通知与「停止」按钮；
 *   前台服务把进程保活，切到后台/锁屏时刷课不中断；
 * - 进度通知按「文案或进度变化」+ [CxStudyNoticeProgress.MIN_INTERVAL_MS] 节流，
 *   避免高频 notify 被系统限流；
 * - 结束时发一条可清除的结果通知（独立 id），再摘掉前台通知并停服务。
 */
class ZhsTaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    private var lastPostedProgress: Int? = null
    private var lastPostedText: String? = null
    private var lastPostedAtMillis = 0L

    /** 任务运行期间持锁；服务没跑任务时完全不创建它。 */
    private var wakeLock: BrushWakeLock? = null
    private var lastPhase: ZhsServicePhase? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "start action=${intent?.action}")
        return when (intent?.action) {
            ACTION_STOP -> {
                ZhsTaskController.stop()
                START_NOT_STICKY
            }

            else -> {
                startForegroundNotification()
                observe()
                START_NOT_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划卡不主动停任务：前台服务在，任务照常跑；这里只留痕，便于事后定位。
        Log.i(TAG, "task removed, running=${ZhsTaskController.state.value.running}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed, running=${ZhsTaskController.state.value.running}")
        wakeLock?.release()
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        if (observer != null) return
        observer = scope.launch {
            ZhsTaskController.state.collect { state ->
                when (state.servicePhase()) {
                    ZhsServicePhase.RUNNING -> {
                        acquireWakeLock()
                        notify(state, textOf(state), final = false)
                    }

                    ZhsServicePhase.FINISHED -> {
                        notifyFinal(state)
                        finishForeground()
                    }

                    // 还没开始：保持前台等状态变化。启动竞态已消除，这里不应出现，
                    // 但绝不能把它当成「已结束」而收尾，否则任务会裸跑。
                    ZhsServicePhase.WAIT -> Unit
                }
                logPhase(state)
            }
        }
    }

    private fun logPhase(state: ZhsTaskState) {
        val phase = state.servicePhase()
        if (phase == lastPhase) return
        lastPhase = phase
        Log.i(
            TAG,
            "phase $phase mode=${state.mode} updatedAt=${state.updatedAt} " +
                "error=${state.error?.let { "yes" } ?: "no"}",
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val lock = wakeLock ?: BrushWakeLock.create(this, WAKE_LOCK_TAG).also {
            wakeLock = it
        }
        if (lock.acquire()) Log.i(TAG, "wake lock acquired") else Log.w(TAG, "wake lock unavailable")
    }

    private fun startForegroundNotification() {
        ensureChannel()
        lastPostedProgress = null
        lastPostedText = null
        lastPostedAtMillis = 0L
        val state = ZhsTaskController.state.value
        val text = if (state.servicePhase() == ZhsServicePhase.WAIT) {
            "准备启动任务"
        } else {
            textOf(state)
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            builder(state, text).build(),
            foregroundType,
        )
    }

    private fun notify(state: ZhsTaskState, text: String, final: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val progress = state.percent
        val now = SystemClock.elapsedRealtime()
        val changed = text != lastPostedText || progress != lastPostedProgress
        val shouldPost = CxStudyNoticeProgress.shouldPost(
            final = final,
            changed = changed,
            lastPostAtMillis = lastPostedText?.let { lastPostedAtMillis },
            nowMillis = now,
        )
        if (!shouldPost) return
        lastPostedProgress = progress
        lastPostedText = text
        lastPostedAtMillis = now
        manager.notify(NOTIFICATION_ID, builder(state, text).build())
    }

    /** 终态单独一条（社交频道），[finishForeground] 摘前台通知时不会把它一起清掉。 */
    private fun notifyFinal(state: ZhsTaskState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notice = ZhsTaskNoticeBuilder.complete(state)
        manager.notify(
            FINAL_NOTIFICATION_ID,
            NotificationCompat.Builder(this, notice.channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
                .setCategory(notice.category)
                .setPriority(notice.priority)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    private fun builder(state: ZhsTaskState, text: String): NotificationCompat.Builder {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ZhsTaskService::class.java).setAction(ACTION_STOP),
            PENDING_FLAGS,
        )
        return NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("知到 · ${state.courseName.ifEmpty { "刷课" }}")
            .setContentText(text)
            .setSubText(
                listOf(state.current, state.speedText)
                    .filter { it.isNotEmpty() }
                    .joinToString(" · "),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(state.running)
            .setContentIntent(openAppIntent())
            .addAction(0, "停止", stop)
            .apply {
                if (state.running) {
                    if (state.target > 0) {
                        setProgress(state.target, state.gained.coerceIn(0, state.target), false)
                    } else {
                        setProgress(0, 0, true)
                    }
                } else {
                    setProgress(0, 0, false)
                }
            }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    private fun textOf(state: ZhsTaskState): String {
        val gained = if (state.target > 0) {
            "本次新增 ${formatSpan(state.gained)} / ${formatSpan(state.target)}"
        } else {
            "本次新增 ${formatSpan(state.gained)}"
        }
        val where = when {
            state.running && state.currentName.isNotEmpty() -> state.currentName
            state.running -> "准备中"
            state.mode == ZhsTaskMode.NONE -> "空闲"
            else -> state.summary ?: "已结束"
        }
        return "$gained · $where"
    }

    private fun finishForeground() {
        wakeLock?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(this, MainActivity::class.java).putExtra(ZhsDeepLink.EXTRA_PAGE, ZhsDeepLink.PAGE_STUDY),
        PENDING_FLAGS,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(PROGRESS_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    PROGRESS_CHANNEL_ID,
                    "知到任务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "知到定时刷课进度"
                    setShowBadge(false)
                },
            )
        }
        // 终态提醒独立社交频道：高重要性 + 振动 + 默认提示音。
        if (manager.getNotificationChannel(ZhsTaskNoticeBuilder.SOCIAL_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ZhsTaskNoticeBuilder.SOCIAL_CHANNEL_ID,
                    "知到刷课提醒（社交）",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "知到刷课达标、结束或需要滑块验证时的提醒"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setShowBadge(true)
                },
            )
        }
    }

    private val foregroundType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    companion object {
        private const val TAG = "ZhsTaskService"
        private const val WAKE_LOCK_TAG = "guanlitong:zhs-study"
        private const val PROGRESS_CHANNEL_ID = "zhs_task"
        private const val NOTIFICATION_ID = 2003
        private const val FINAL_NOTIFICATION_ID = 2301
        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        const val ACTION_START = "com.sdmu.guanlitong.zhs.START"
        const val ACTION_STOP = "com.sdmu.guanlitong.zhs.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ZhsTaskService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ZhsTaskService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}