package com.sdmu.guanlitong.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxSignStore
import java.util.concurrent.TimeUnit

/**
 * 第二层兜底（WorkManager 15 分钟周期）：只检查与拉活，不轮询活动。
 *
 * 与闹钟心跳同一判断；失败只记日志并 `Result.success()`，不让 WorkManager 重试
 * 风暴。登录失效/开关关闭时顺手撤掉周期任务，等 App 下次打开再按需恢复。
 */
class CxSignHeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val monitorEnabled: Boolean
        val loggedIn: Boolean
        try {
            monitorEnabled = CxSignStore(context).settings().monitorEnabled
            loggedIn = CxRepository(context).loggedIn
        } catch (e: Throwable) {
            Log.w(TAG, "state read failed", e)
            return Result.success()
        }

        if (!loggedIn || !monitorEnabled) {
            Log.i(TAG, "disabled (loggedIn=$loggedIn, monitor=$monitorEnabled), cancel period")
            CxSignHeartbeatWork.cancel(context)
            return Result.success()
        }

        val running = CxSignStatus.state.value.running
        if (!shouldRestoreSignMonitor(loggedIn, monitorEnabled, running)) {
            Log.i(TAG, "restore skipped, running=$running")
            return Result.success()
        }

        try {
            CxTaskService.startSign(context)
            Log.i(TAG, "restore requested")
        } catch (e: Throwable) {
            Log.w(TAG, "restore failed", e)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "CxSignHeartbeatWorker"
    }
}

/**
 * 15 分钟一次的唯一周期任务；重复 schedule 不会叠加（KEEP）。
 *
 * 不设置网络约束：拉活本身不需要网络，服务起来后会按自己的节奏重试签到请求。
 */
object CxSignHeartbeatWork {

    private const val TAG = "CxSignHeartbeatWork"
    private const val UNIQUE_WORK = "cx-sign-heartbeat"
    private const val INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<CxSignHeartbeatWorker>(
                INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "schedule failed", e)
        }
    }

    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
        } catch (e: Throwable) {
            Log.w(TAG, "cancel failed", e)
        }
    }
}