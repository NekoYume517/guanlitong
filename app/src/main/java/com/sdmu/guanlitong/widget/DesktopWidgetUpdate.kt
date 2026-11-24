package com.sdmu.guanlitong.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 数据变更后主动刷新；静态广播也允许 App 启动时恢复所有桌面实例。 */
class DesktopWidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = android.appwidget.AppWidgetManager.getInstance(context)
                runCatching {
                listOf(
                    SmallDesktopWidgetReceiver::class.java to DesktopSize.Small,
                    MediumDesktopWidgetReceiver::class.java to DesktopSize.Medium,
                    LargeDesktopWidgetReceiver::class.java to DesktopSize.Large,
                ).forEach { (receiver, size) ->
                    manager.getAppWidgetIds(android.content.ComponentName(context, receiver)).forEach { id ->
                        DesktopAppWidget(size).update(context, androidx.glance.appwidget.AppWidgetId(id))
                    }
                }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

fun refreshDesktopWidgets(context: Context) {
    context.sendBroadcast(Intent(ACTION_DESKTOP_WIDGET_REFRESH).setPackage(context.packageName))
}
