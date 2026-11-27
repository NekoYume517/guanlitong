package com.sdmu.guanlitong.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxSignStore

/**
 * 心跳闹钟 / 任务划掉延迟重启的接收器。
 *
 * 两种情况都只做「检查 + 试着拉起」：
 * - 已登录且总开关开着，且 [CxSignStatus] 显示轮询没在跑，才 `startSign`；
 * - 心跳每次触发都重新排下一次（闹钟是一次性的）；登录失效或开关关闭则停掉整条链；
 * - 拉起失败（Android 12+ / ROM 限制）只记日志，不在这里重试。
 */
class CxKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != CxKeepAlive.ACTION_HEARTBEAT &&
            action != CxKeepAlive.ACTION_RESTART_SIGN &&
            action != CxKeepAlive.ACTION_RETRY_SIGN
        ) {
            return
        }

        val appContext = context.applicationContext
        val monitorEnabled: Boolean
        val loggedIn: Boolean
        try {
            monitorEnabled = CxSignStore(appContext).settings().monitorEnabled
            loggedIn = CxRepository(appContext).loggedIn
        } catch (e: Throwable) {
            // 存储/Keystore 异常不能把接收器带崩，也不能误判后一直拉活。
            Log.w(TAG, "state read failed ($action)", e)
            return
        }

        if (!loggedIn || !monitorEnabled) {
            Log.i(TAG, "keep-alive disabled (loggedIn=$loggedIn, monitor=$monitorEnabled)")
            // 登录失效或用户关掉总开关：不再唤醒，等 App 下次打开/重新登录再恢复。
            CxKeepAlive.disable(appContext)
            return
        }

        // 心跳链自己续上：这次拉活失败，15 分钟后再试；不依赖进程是否还活着。
        if (action == CxKeepAlive.ACTION_HEARTBEAT) CxKeepAlive.scheduleHeartbeat(appContext)

        val running = CxSignStatus.state.value.running
        if (!shouldRestoreSignMonitor(loggedIn, monitorEnabled, running)) {
            Log.i(TAG, "restore skipped ($action), running=$running")
            return
        }

        try {
            CxTaskService.startSign(appContext)
            Log.i(TAG, "restore requested ($action)")
        } catch (e: Throwable) {
            Log.w(TAG, "restore failed ($action)", e)
        }
    }

    private companion object {
        const val TAG = "CxKeepAliveReceiver"
    }
}