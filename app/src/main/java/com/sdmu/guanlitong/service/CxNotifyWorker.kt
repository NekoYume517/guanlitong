package com.sdmu.guanlitong.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sdmu.guanlitong.data.cx.CX_CODE_AUTH
import com.sdmu.guanlitong.data.cx.CX_CODE_RISK
import com.sdmu.guanlitong.data.cx.CxException
import com.sdmu.guanlitong.data.cx.CxNoticeRunner
import com.sdmu.guanlitong.data.cx.CxNoticeStore
import com.sdmu.guanlitong.data.cx.CxRemoteNoticeGateway
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.cxMessage
import com.sdmu.guanlitong.data.cx.signAccountKey
import kotlinx.coroutines.CancellationException

/**
 * 收件箱 30 分钟后台同步（见 [CxNotifySchedule]）。
 *
 * - 未登录：直接成功返回，等用户登录；
 * - 新增条目：发一条静默摘要通知；没有新增不发；
 * - 401/403：记日志并中止本轮，`Result.success`（不重试风暴）；
 * - 网络类错误：最多 `Result.retry` 两次（WorkManager 指数退避）。
 */
class CxNotifyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = CxNoticeStore(context)
        val repo = CxRepository(context)
        if (!repo.loggedIn) {
            Log.i(TAG, "未登录学习通，跳过收件箱同步")
            return Result.success()
        }

        return try {
            val accountKey = repo.withSession { signAccountKey(it) }
            val runner = CxNoticeRunner(
                gateway = CxRemoteNoticeGateway(repo),
                notices = store,
                accountKey = accountKey,
            )
            val added = runner.poll()
            val unread = added.filter { !it.localRead }
            if (unread.isNotEmpty()) {
                CxNotifyNotifier.inboxSummary(
                    context,
                    unread.size,
                    unread.maxByOrNull { it.timeMs }?.title.orEmpty(),
                )
            }
            Result.success()
        } catch (e: CxException) {
            when (e.code) {
                CX_CODE_AUTH, CX_CODE_RISK -> {
                    Log.w(TAG, "收件箱同步中止：${e.cxMessage()}")
                    Result.success()
                }
                else -> {
                    Log.w(TAG, "收件箱同步失败：${e.cxMessage()}")
                    if (runAttemptCount < MAX_NETWORK_RETRIES) Result.retry() else Result.success()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "收件箱同步异常", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "CxNotifyWorker"
        private const val MAX_NETWORK_RETRIES = 2
    }
}