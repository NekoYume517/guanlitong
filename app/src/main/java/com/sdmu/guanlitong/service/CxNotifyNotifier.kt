package com.sdmu.guanlitong.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sdmu.guanlitong.MainActivity
import com.sdmu.guanlitong.R

/**
 * 收件箱的后台新消息通知：静默渠道（LOW，无声音/无横幅），只在 30 分钟后台同步
 * 新增条目时发一条摘要；手动刷新不发通知。
 *
 * 渠道用新 id `cx_notice_bg`：旧版本可能已创建过 `cx_notice`，渠道一旦创建就不能
 * 降低重要性，必须新建。
 */
object CxNotifyNotifier {

    const val CHANNEL_ID = "cx_notice_bg"
    private const val CHANNEL_NAME = "学习通收件箱"

    private const val SUMMARY_ID = 2200
    private const val PAGE_INBOX = "inbox"

    private val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun inboxSummary(context: Context, count: Int, latestTitle: String) {
        if (count <= 0) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("学习通收件箱 · 新增 $count 条")
            .setContentText(latestTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(latestTitle))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openInbox(context))
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // 没有通知权限时 notify 会被系统丢弃，不抛异常；数据仍已入库。
        runCatching { manager.notify(SUMMARY_ID, notification) }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "学习通收件箱有新消息时静默提醒"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun openInbox(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        SUMMARY_ID,
        Intent(context, MainActivity::class.java).putExtra("cx_page", PAGE_INBOX),
        PENDING_FLAGS,
    )
}