package com.sdmu.guanlitong.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sdmu.guanlitong.data.cx.CX_NOTICE_REFRESH_INTERVAL_MINUTES
import java.util.concurrent.TimeUnit

/**
 * 收件箱后台同步：30 分钟一次唯一周期任务，需要网络。
 *
 * 收件箱是常驻功能（没有开关），不进前台服务；WorkManager 自己持久化，重启后系统
 * 会恢复。手动刷新由收件箱页面直接同步，不走这里。
 */
object CxNotifySchedule {

    private const val UNIQUE_PERIODIC = "cx-notify"

    /** App 启动/进入学习通页面时调用；已入队则保持（KEEP），不会重置计时。 */
    fun ensure(context: Context) {
        val request = PeriodicWorkRequestBuilder<CxNotifyWorker>(
            CX_NOTICE_REFRESH_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
    }
}