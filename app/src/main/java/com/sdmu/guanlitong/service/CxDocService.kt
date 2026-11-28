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
import com.sdmu.guanlitong.data.cx.CX_CODE_AUTH
import com.sdmu.guanlitong.data.cx.CxDocCourse
import com.sdmu.guanlitong.data.cx.CxDocEngine
import com.sdmu.guanlitong.data.cx.CxDocFinishReason
import com.sdmu.guanlitong.data.cx.CxDocItem
import com.sdmu.guanlitong.data.cx.CxDocNoticeBuilder
import com.sdmu.guanlitong.data.cx.CxDocParams
import com.sdmu.guanlitong.data.cx.CxDocProgress
import com.sdmu.guanlitong.data.cx.CxDocTaskStatus
import com.sdmu.guanlitong.data.cx.CxException
import com.sdmu.guanlitong.data.cx.CxRemoteDocGateway
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxCourse
import com.sdmu.guanlitong.data.cx.cxMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 资料刷时长的前台服务。
 *
 * 只对「一个文件」负责：进度写 [CxDocTaskStatus]，常驻通知显示资料名、已上报分钟与
 * 进度条，通知里的「停止」走 [ACTION_STOP]；停止时引擎会补一次满进度收尾心跳
 * （与老项目 Ctrl+C 语义一致），服务再把终态写回状态流并发一条普通通知。
 *
 * 前台类型用 specialUse（与刷课/签到同一理由：Android 14+ 的 dataSync 受开机恢复
 * 限制），任务只在 App 进程内跑，进程被杀不自动续刷（START_NOT_STICKY）。
 */
class CxDocService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private var lastPostedText: String? = null
    private var lastPostedAtMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        when (intent?.action) {
            ACTION_STOP -> {
                CxDocTaskStatus.finish("已停止刷时长")
                // 交给 runDoc 的取消分支补收尾心跳与终态通知；没有任务在跑就直接收工。
                if (job?.isActive == true) {
                    job?.cancel()
                } else {
                    stopForegroundAndSelf()
                }
                job = null
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val args = Args.from(intent)
                if (args == null) {
                    Log.w(TAG, "缺少资料参数，停止")
                    stopSelf()
                    return START_NOT_STICKY
                }
                CxDocTaskStatus.start(args.toCourse(), args.toItem(), args.speed, pageNum = 0)
                startForegroundNotification("${args.docName} · 准备中")
                job?.cancel()
                job = scope.launch { runDoc(args) }
            }

            else -> {
                // 系统重启进程（intent=null）或未知动作：不续刷，直接退出。
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runDoc(args: Args) {
        val repo = CxRepository(applicationContext)
        if (!repo.loggedIn) {
            CxDocTaskStatus.finish("未登录学习通", fatalError = "未登录学习通")
            postFinal(CxDocFinishReason.FAILED)
            stopForegroundAndSelf()
            return
        }

        val course = args.toCourse()
        val item = args.toItem()
        Log.i(TAG, "start course=${args.courseName} file=${args.docName} speed=${args.speed}")

        try {
            CxDocEngine(CxRemoteDocGateway(repo)).run(
                course = course,
                item = item,
                speed = args.speed,
                onProgress = { progress -> handleProgress(progress) },
            )
        } catch (e: CancellationException) {
            CxDocTaskStatus.finish("已停止刷时长")
            postFinal(CxDocFinishReason.STOPPED)
        } catch (e: Exception) {
            val message = e.cxMessage()
            val code = (e as? CxException)?.code
            Log.w(TAG, "stopped with error code=$code", e)
            CxDocTaskStatus.finish(message, fatalError = message)
            postFinal(CxDocFinishReason.FAILED)
        } finally {
            stopForegroundAndSelf()
        }
    }

    private fun handleProgress(progress: CxDocProgress) {
        CxDocTaskStatus.update {
            it.copy(
                reportedSeconds = progress.reportedSeconds,
                pageNum = progress.pageNum,
                height = progress.height,
                maxHeight = progress.maxHeight,
                message = "已上报 ${formatMinutes(progress.reportedSeconds)} 分钟",
            )
        }
        notifyProgress()
    }

    private fun startForegroundNotification(text: String) {
        lastPostedText = text
        lastPostedAtMillis = SystemClock.elapsedRealtime()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(text, ongoing = true),
            foregroundType,
        )
    }

    /** 每 5 秒一次心跳时通知不必逐条刷：文案变了且距上次 ≥10s 才更新。 */
    private fun notifyProgress() {
        val state = CxDocTaskStatus.state.value
        val text = "${state.fileName} · 已上报 ${formatMinutes(state.reportedSeconds)} 分钟"
        val now = SystemClock.elapsedRealtime()
        if (now - lastPostedAtMillis < NOTIFY_THROTTLE_MS) return
        lastPostedText = text
        lastPostedAtMillis = now
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification(text, ongoing = true))
    }

    /**
     * 终态通知：只在完成 / 停止 / 失败时发一次。走社交频道拿最强提醒，
     * 与 5 秒一次的低打扰进度通知分开，避免反复响铃。
     */
    private fun postFinal(reason: CxDocFinishReason) {
        val notice = CxDocNoticeBuilder.complete(CxDocTaskStatus.state.value, reason)
        getSystemService(NotificationManager::class.java)?.notify(
            FINAL_NOTIFICATION_ID,
            NotificationCompat.Builder(this, notice.channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setCategory(notice.category)
                .setPriority(notice.priority)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    private fun notification(text: String, ongoing: Boolean): Notification {
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, CxDocService::class.java).setAction(ACTION_STOP),
            PENDING_FLAGS,
        )
        val state = CxDocTaskStatus.state.value
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("学习通 · ${state.courseName.ifBlank { "资料刷时长" }}")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setContentIntent(openAppIntent())
            .addAction(0, "停止", stop)
            .setProgress(100, state.heightPercent, false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        12,
        Intent(this, MainActivity::class.java),
        PENDING_FLAGS,
    )

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "资料刷时长", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "资料阅读进度与停止入口"
                        setShowBadge(false)
                    },
            )
        }
        // 终态提醒独立社交频道：高重要性 + 振动 + 默认提示音（与知到口径一致，频道名独立）。
        if (manager.getNotificationChannel(CxDocNoticeBuilder.SOCIAL_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CxDocNoticeBuilder.SOCIAL_CHANNEL_ID,
                    "学习通资料刷时长提醒（社交）",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "资料刷时长完成、停止或失败时的提醒"
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

    private data class Args(
        val courseId: String,
        val clazzId: String,
        val personId: String,
        val courseName: String,
        val cpi: String,
        val enc: String,
        val dataId: String,
        val objectId: String,
        val type: String,
        val docName: String,
        val speed: Double,
    ) {
        fun toCourse() = CxDocCourse(
            courseId = courseId,
            clazzId = clazzId,
            personId = personId,
            cpi = cpi,
            enc = enc,
            name = courseName,
        )

        fun toItem() = CxDocItem(
            name = docName,
            type = type.ifBlank { "doc" },
            id = dataId,
            objectId = objectId,
        )

        companion object {
            fun from(intent: Intent): Args? {
                val courseId = intent.getStringExtra(CxDocService.EXTRA_COURSE_ID)
                    ?.takeIf { it.isNotBlank() } ?: return null
                val dataId = intent.getStringExtra(CxDocService.EXTRA_DATA_ID)
                    ?.takeIf { it.isNotBlank() } ?: return null
                return Args(
                    courseId = courseId,
                    clazzId = intent.getStringExtra(CxDocService.EXTRA_CLAZZ_ID).orEmpty(),
                    personId = intent.getStringExtra(CxDocService.EXTRA_PERSON_ID).orEmpty(),
                    courseName = intent.getStringExtra(CxDocService.EXTRA_COURSE_NAME).orEmpty(),
                    cpi = intent.getStringExtra(CxDocService.EXTRA_CPI).orEmpty(),
                    enc = intent.getStringExtra(CxDocService.EXTRA_ENC).orEmpty(),
                    dataId = dataId,
                    objectId = intent.getStringExtra(CxDocService.EXTRA_OBJECT_ID).orEmpty(),
                    type = intent.getStringExtra(CxDocService.EXTRA_DOC_TYPE).orEmpty(),
                    docName = intent.getStringExtra(CxDocService.EXTRA_DOC_NAME)
                        .orEmpty().ifEmpty { "资料" },
                    speed = intent.getDoubleExtra(CxDocService.EXTRA_SPEED, 1.0),
                )
            }
        }
    }

    companion object {
        private const val TAG = "CxDocService"
        private const val CHANNEL_ID = CxDocNoticeBuilder.PROGRESS_CHANNEL_ID
        private const val NOTIFICATION_ID = 2305
        private const val FINAL_NOTIFICATION_ID = 2306
        private const val NOTIFY_THROTTLE_MS = 10_000L
        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        const val ACTION_START = "com.sdmu.guanlitong.cx.START_DOC"
        const val ACTION_STOP = "com.sdmu.guanlitong.cx.STOP_DOC"

        private const val EXTRA_COURSE_ID = "courseId"
        private const val EXTRA_CLAZZ_ID = "clazzId"
        private const val EXTRA_PERSON_ID = "personId"
        private const val EXTRA_COURSE_NAME = "courseName"
        private const val EXTRA_CPI = "cpi"
        private const val EXTRA_ENC = "enc"
        private const val EXTRA_DATA_ID = "dataId"
        private const val EXTRA_OBJECT_ID = "objectId"
        private const val EXTRA_DOC_TYPE = "docType"
        private const val EXTRA_DOC_NAME = "docName"
        private const val EXTRA_SPEED = "speed"

        fun start(
            context: Context,
            course: CxCourse,
            params: CxDocParams,
            cpi: String,
            enc: String,
            item: CxDocItem,
            speed: Double,
        ) {
            val intent = Intent(context, CxDocService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_COURSE_ID, course.courseId)
                .putExtra(EXTRA_CLAZZ_ID, course.clazzId)
                .putExtra(EXTRA_PERSON_ID, course.personId)
                .putExtra(EXTRA_COURSE_NAME, course.name)
                .putExtra(EXTRA_CPI, cpi.ifBlank { params.cpi })
                .putExtra(EXTRA_ENC, enc.ifBlank { params.enc })
                .putExtra(EXTRA_DATA_ID, item.id)
                .putExtra(EXTRA_OBJECT_ID, item.objectId)
                .putExtra(EXTRA_DOC_TYPE, item.type)
                .putExtra(EXTRA_DOC_NAME, item.name)
                .putExtra(EXTRA_SPEED, speed)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CxDocService::class.java).setAction(ACTION_STOP),
            )
        }

        private fun formatMinutes(seconds: Long): String =
            String.format(Locale.CHINA, "%.1f", seconds / 60.0)
    }
}