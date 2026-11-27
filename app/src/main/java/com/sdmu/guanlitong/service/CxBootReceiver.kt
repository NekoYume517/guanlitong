package com.sdmu.guanlitong.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxSignStore

/**
 * 开机完成 / 应用更新后恢复签到监控。
 *
 * 只有「实时提醒开着 + 已登录」才拉起前台服务；拉起被系统拒绝时交给
 * [CxSignBootWork] 再试一次，仍失败就静默放弃，等用户下次打开 App。
 *
 * 接收器只做这一件事，不碰正在运行的轮询、通知文案或设置语义。
 */
class CxBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!CxBootActions.handles(intent.action)) return

        val appContext = context.applicationContext
        val store = CxSignStore(appContext)
        val repo = CxRepository(appContext)
        val outcome = CxSignBootRestore(
            monitorEnabled = { store.settings().monitorEnabled },
            loggedIn = { repo.loggedIn },
            startService = { CxTaskService.startSign(appContext) },
        ).run()

        when (outcome) {
            CxSignBootOutcome.STARTED -> {
                Log.i(TAG, "boot restore: sign loop start requested")
                CxKeepAlive.enable(appContext)
            }
            CxSignBootOutcome.START_DENIED -> {
                Log.w(TAG, "boot restore: foreground start denied, schedule one-shot fallback")
                CxSignBootWork.schedule(appContext)
                // 开机拉不起来也要把心跳和周期任务接上，15 分钟后还有机会恢复。
                CxKeepAlive.enable(appContext)
                // 拒绝后的第二次尝试：有精确闹钟授权时 30 秒后再拉一次；
                // 仍被拒就静默放弃，等用户打开 App（接收器不再重试）。
                if (CxKeepAlive.scheduleDeniedRetry(appContext)) {
                    Log.i(TAG, "boot restore: denied-retry scheduled in ${CxKeepAlive.DENIED_RETRY_DELAY_MS}ms")
                } else {
                    Log.i(TAG, "boot restore: denied-retry unavailable, give up until app opens")
                }
            }
            CxSignBootOutcome.FAILED -> Log.w(TAG, "boot restore: start failed, give up")
            CxSignBootOutcome.SKIPPED -> Unit
        }
    }

    private companion object {
        const val TAG = "CxBootReceiver"
    }
}