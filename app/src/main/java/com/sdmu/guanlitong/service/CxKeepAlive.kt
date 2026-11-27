package com.sdmu.guanlitong.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 签到监控的唤醒兜底：心跳闹钟 + 任务划掉后的延迟重启。
 *
 * 只用系统允许的正规手段：
 * - Android 12+ 优先 `setExactAndAllowWhileIdle`，没有精确闹钟授权就退化
 *   `setAndAllowWhileIdle`；
 * - 闹钟只发本应用内的显式广播，接收器再判断「应开但没在跑」才拉服务；
 * - 拉起失败只记日志，不循环重试。
 */
object CxKeepAlive {

    const val ACTION_HEARTBEAT = "com.sdmu.guanlitong.action.SIGN_HEARTBEAT"
    const val ACTION_RESTART_SIGN = "com.sdmu.guanlitong.action.SIGN_RESTART"

    /** 开机拉起被拒后的第二次尝试（拒绝后的重试，不是新的保活链）。 */
    const val ACTION_RETRY_SIGN = "com.sdmu.guanlitong.action.SIGN_RETRY"

    /** 心跳间隔：15 分钟，和 WorkManager 周期任务同一节奏。 */
    const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L

    /** 任务列表被划掉后等 5 秒再试，避开系统正在清理的瞬间。 */
    const val TASK_REMOVED_RESTART_MS = 5_000L

    /** 开机拉起被拒后等 30 秒再试一次，给系统清理开机的忙期让路。 */
    const val DENIED_RETRY_DELAY_MS = 30_000L

    private const val TAG = "CxKeepAlive"
    private const val REQUEST_HEARTBEAT = 4101
    private const val REQUEST_RESTART = 4102
    private const val REQUEST_RETRY = 4103
    private const val PENDING_FLAGS =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** 开关开着时调用：心跳闹钟 + WorkManager 周期兜底。重复调用是幂等的。 */
    fun enable(context: Context) {
        scheduleHeartbeat(context)
        CxSignHeartbeatWork.schedule(context)
    }

    /** 关闭监控 / 退出登录后调用：撤掉闹钟和周期任务。 */
    fun disable(context: Context) {
        cancelAlarm(context, heartbeatPendingIntent(context))
        cancelAlarm(context, restartPendingIntent(context))
        cancelAlarm(context, retryPendingIntent(context))
        CxSignHeartbeatWork.cancel(context)
    }

    fun scheduleHeartbeat(context: Context, delayMs: Long = HEARTBEAT_INTERVAL_MS) {
        schedule(context, System.currentTimeMillis() + delayMs, heartbeatPendingIntent(context), "heartbeat")
    }

    fun scheduleRestart(context: Context) {
        schedule(context, System.currentTimeMillis() + TASK_REMOVED_RESTART_MS, restartPendingIntent(context), "restart")
    }

    /** 精确闹钟授权是否可用；Android 12 以下没有这个限制。 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            manager.canScheduleExactAlarms()
        } catch (e: Throwable) {
            Log.w(TAG, "canScheduleExactAlarms failed", e)
            false
        }
    }

    /**
     * 开机 / 应用更新后拉起被拒时的「拒绝后的第二次尝试」：只有精确闹钟可用才排，
     * 30 秒后发一次 [ACTION_RETRY_SIGN]。排不上（无授权、系统异常）就返回 false，
     * 由调用方静默放弃，等用户自己打开 App。
     */
    fun scheduleDeniedRetry(context: Context): Boolean {
        if (!shouldScheduleDeniedRetry(canScheduleExactAlarms(context))) {
            Log.i(TAG, "denied-retry skipped: exact alarms unavailable")
            return false
        }
        return schedule(
            context,
            System.currentTimeMillis() + DENIED_RETRY_DELAY_MS,
            retryPendingIntent(context),
            "denied-retry",
        )
    }

    /** 返回是否真的排上了闹钟。 */
    private fun schedule(context: Context, triggerAt: Long, pending: PendingIntent, tag: String): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            Log.i(TAG, "$tag scheduled at $triggerAt exact=$exactAllowed")
            return true
        } catch (e: SecurityException) {
            // 精确闹钟权限在系统设置里被撤销时会走到这里，退化一次。
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                Log.i(TAG, "$tag scheduled inexact after SecurityException")
                return true
            } catch (e2: Throwable) {
                Log.w(TAG, "$tag schedule failed", e2)
                return false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "$tag schedule failed", e)
            return false
        }
    }

    private fun cancelAlarm(context: Context, pending: PendingIntent) {
        try {
            context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        } catch (e: Throwable) {
            Log.w(TAG, "alarm cancel failed", e)
        }
    }

    private fun heartbeatPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_HEARTBEAT,
            Intent(context, CxKeepAliveReceiver::class.java).setAction(ACTION_HEARTBEAT),
            PENDING_FLAGS,
        )

    private fun restartPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_RESTART,
            Intent(context, CxKeepAliveReceiver::class.java).setAction(ACTION_RESTART_SIGN),
            PENDING_FLAGS,
        )

    private fun retryPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_RETRY,
            Intent(context, CxKeepAliveReceiver::class.java).setAction(ACTION_RETRY_SIGN),
            PENDING_FLAGS,
        )
}