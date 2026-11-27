package com.sdmu.guanlitong.ui.cx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.sdmu.guanlitong.service.autoStartTargets

/** 「后台保活」区用到的系统状态读取与页面跳转。 */

private const val TAG = "CxKeepAliveActions"

/** 是否已在电池优化白名单里；读不到时按已加入处理，避免反复打扰。 */
fun isBatteryOptimizationIgnored(context: Context): Boolean = try {
    val manager = context.getSystemService(PowerManager::class.java)
    manager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
} catch (e: Throwable) {
    Log.w(TAG, "read battery optimization state failed", e)
    true
}

/** 系统级通知开关（含渠道被关、通知权限被拒）。 */
fun appNotificationsEnabled(context: Context): Boolean = try {
    NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()
} catch (e: Throwable) {
    Log.w(TAG, "read notification state failed", e)
    true
}

/** 申请加入电池优化白名单；系统不认就退到电池优化设置列表。 */
fun openBatteryOptimizationRequest(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val intents = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
    launchFirst(context, intents)
}

/**
 * 打开厂商自启动管理页：按厂商候选逐个尝试，全部失败退到本应用系统详情页。
 * 厂商页属于系统设置，应用无法直接改开关，只能把入口送到用户面前。
 */
fun openVendorAutoStartSettings(context: Context) {
    for (target in autoStartTargets(Build.MANUFACTURER, Build.BRAND)) {
        val intent = Intent().setComponent(
            ComponentName(target.packageName, target.className),
        )
        if (launchIfPossible(context, intent)) return
    }
    openAppDetails(context)
}

/** 打开本应用的通知设置；没有该页面就退到应用详情。 */
fun openNotificationSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val intents = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
    )
    launchFirst(context, intents)
}

fun openAppDetails(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )
    launchIfPossible(context, intent)
}

private fun launchFirst(context: Context, intents: List<Intent>) {
    for (intent in intents) {
        if (launchIfPossible(context, intent)) return
    }
    openAppDetails(context)
}

private fun launchIfPossible(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Throwable) {
    // 厂商组件不存在 / 无权限 / ROM 拦截都在这里，换下一个候选。
    Log.i(TAG, "activity unavailable: ${intent.component?.flattenToShortString()}")
    false
}