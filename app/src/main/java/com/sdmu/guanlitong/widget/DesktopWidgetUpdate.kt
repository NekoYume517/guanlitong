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
        val renderOnly = intent.getBooleanExtra(EXTRA_DESKTOP_WIDGET_RENDER_ONLY, false)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 先请求一次刷新（缓存新鲜、冷却中或已在跑时立即返回），
                // 不在这里等网络，避免广播超时；完成者会再发一次只重绘的广播。
                if (!renderOnly) {
                    DesktopWidgetRefresher.request(context)
                }
                renderDesktopWidgetsNow(context)
            } finally {
                pending.finish()
            }
        }
    }
}

internal const val EXTRA_DESKTOP_WIDGET_RENDER_ONLY = "desktop_widget_render_only"

/** 触发一次「按需刷新 + 重绘」；缓存仍新鲜时不会发网络请求。 */
fun refreshDesktopWidgets(context: Context) {
    sendWidgetBroadcast(context, renderOnly = false)
}

/** 只重绘，不再触发刷新（刷新完成、数据变化后使用）。 */
fun renderDesktopWidgets(context: Context) {
    sendWidgetBroadcast(context, renderOnly = true)
}

private fun sendWidgetBroadcast(context: Context, renderOnly: Boolean) {
    context.sendBroadcast(
        Intent(ACTION_DESKTOP_WIDGET_REFRESH)
            .setPackage(context.packageName)
            .putExtra(EXTRA_DESKTOP_WIDGET_RENDER_ONLY, renderOnly),
    )
}

private suspend fun renderDesktopWidgetsNow(context: Context) {
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
}