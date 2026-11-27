package com.sdmu.guanlitong.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxSignStore

/**
 * 开机拉起前台服务被拒后的唯一一次兜底尝试（见 [CxSignBootWork]）。
 *
 * 仍用同一套纯逻辑判断：开关关着 / 未登录就成功结束；启动成功就成功结束；
 * 再次被拒或其它异常一律 `Result.failure()`，不 `retry`，避免后台启动风暴。
 */
class CxSignBootWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = CxSignStore(context)
        val repo = CxRepository(context)
        val outcome = CxSignBootRestore(
            monitorEnabled = { store.settings().monitorEnabled },
            loggedIn = { repo.loggedIn },
            startService = { CxTaskService.startSign(context) },
        ).run()

        return when (outcome) {
            CxSignBootOutcome.STARTED -> {
                Log.i(TAG, "fallback restore: sign loop start requested")
                Result.success()
            }
            CxSignBootOutcome.SKIPPED -> {
                Log.i(TAG, "fallback restore: skipped (logged out or monitor off)")
                Result.success()
            }
            CxSignBootOutcome.START_DENIED, CxSignBootOutcome.FAILED -> {
                Log.w(TAG, "fallback restore failed ($outcome), give up")
                Result.failure()
            }
        }
    }

    private companion object {
        const val TAG = "CxSignBootWorker"
    }
}

/**
 * 兜底任务：开机被拒后，带网络约束的一次性任务。
 *
 * 唯一任务名 + [ExistingWorkPolicy.KEEP]：同一时刻只保留一个未完成的尝试；
 * 下次开机再次被拒时，会重新排一次，但每次开机最多尝试一次。
 */
object CxSignBootWork {

    private const val TAG = "CxSignBootWork"
    private const val UNIQUE_WORK = "cx-sign-boot-restore"

    fun schedule(context: Context) {
        try {
            val request = OneTimeWorkRequestBuilder<CxSignBootWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
            Log.i(TAG, "one-shot boot restore scheduled")
        } catch (e: Throwable) {
            // WorkManager 未初始化或 ROM 限制：放弃即可，不能从接收器崩溃。
            Log.w(TAG, "schedule failed, give up", e)
        }
    }
}