package com.sdmu.guanlitong.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sdmu.guanlitong.MainActivity
import com.sdmu.guanlitong.R

/** 自动认证结果提醒：一次网络切换的结果只通知一次，不含账号、IP 或认证参数。 */
object SrunAutoNotifier {
    const val CHANNEL_ID = "srun_auto_auth"
    private const val NOTIFICATION_ID = 2300

    fun notify(context: Context, success: Boolean, detail: String = "") {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "校园网认证", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "连上校园网后自动认证的结果"
                },
            )
        }
        val title = if (success) "校园网已自动认证" else "校园网自动认证未完成"
        val intent = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java).putExtra("feature_id", "srun.login"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(if (success) "现在可以上网了" else detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (success) "现在可以上网了" else detail))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}
