package com.sdmu.guanlitong.service

import java.util.Locale

/**
 * 此刻是否应启动签到监控：已登录 + 总开关开 + 当前没在跑。
 *
 * 心跳、WorkManager 周期任务、STICKY 重启都共用这一条判断；已经 running 时绝不重复
 * 拉起，避免多条轮询协程。
 */
fun shouldRestoreSignMonitor(
    loggedIn: Boolean,
    monitorEnabled: Boolean,
    running: Boolean,
): Boolean = loggedIn && monitorEnabled && !running

/**
 * 任务列表被划掉时是否要排一次延迟重启。
 *
 * 不看 [CxSignStatus.state]：服务可能正在被系统拆掉，running 还来不及变 false；
 * 只要用户还登录着且开关开着，就值得 5 秒后再确认一次。
 */
fun shouldScheduleTaskRemovedRestart(loggedIn: Boolean, monitorEnabled: Boolean): Boolean =
    loggedIn && monitorEnabled

/**
 * 开机 / 更新后拉起被拒，是否还要排一次 30 秒后的第二次尝试。
 *
 * 只在系统明确允许精确闹钟时才排：没有精确闹钟授权时，非精确闹钟可能被拖到几十分钟
 * 后，等于把「开机恢复」变成一次不可预期的后台打扰，不如直接放弃、等用户打开 App。
 * 第二次仍被拒就不再重试，避免形成重试风暴。
 */
fun shouldScheduleDeniedRetry(exactAlarmsAllowed: Boolean): Boolean = exactAlarmsAllowed

/**
 * 「后台保活」区要展示什么：把系统状态映射成界面提示，纯逻辑可测。
 */
data class CxKeepAliveHints(
    /** 未加入电池优化白名单时，显示「加入白名单」按钮。 */
    val showBatteryWhitelistAction: Boolean,
    val batteryStatus: String,
    /** 通知被关闭时，显示「去开启通知」按钮。 */
    val showNotificationSettingsAction: Boolean,
    val notificationStatus: String,
)

fun keepAliveHints(
    batteryWhitelisted: Boolean,
    notificationsEnabled: Boolean,
): CxKeepAliveHints = CxKeepAliveHints(
    showBatteryWhitelistAction = !batteryWhitelisted,
    batteryStatus = if (batteryWhitelisted) {
        "已在电池优化白名单：系统空闲时更不容易中断监控"
    } else {
        "未加入白名单：系统空闲压缩时可能中断监控，建议加入「忽略电池优化」"
    },
    showNotificationSettingsAction = !notificationsEnabled,
    notificationStatus = if (notificationsEnabled) {
        "通知权限已开启，签到提醒会以横幅出现"
    } else {
        "通知已关闭：签到的横幅和常驻通知都看不到，请去系统设置开启"
    },
)

/** 厂商自启动管理页的一个候选入口。 */
data class CxAutoStartTarget(val packageName: String, val className: String)

/**
 * 按厂商给出常见自启动管理页的候选组件；UI 逐个尝试，全部失败再退到
 * 本应用的系统「应用详情」页。
 *
 * 这里只返回纯数据，不碰 Intent/ComponentName，便于 JVM 单测。厂商私有组件在
 * 不同 ROM 版本上会改包名/类名，所以每个厂商给多个候选，按常见程度排序。
 */
fun autoStartTargets(manufacturer: String?, brand: String?): List<CxAutoStartTarget> {
    val key = listOfNotNull(manufacturer, brand)
        .joinToString(" ")
        .lowercase(Locale.ROOT)

    fun has(vararg words: String) = words.any { key.contains(it) }

    return when {
        has("xiaomi", "redmi", "poco") -> listOf(
            CxAutoStartTarget(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )

        has("honor", "hihonor") -> listOf(
            CxAutoStartTarget(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            CxAutoStartTarget(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            CxAutoStartTarget(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )

        has("huawei") -> listOf(
            CxAutoStartTarget(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            CxAutoStartTarget(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )

        has("oppo", "realme", "oneplus") -> listOf(
            CxAutoStartTarget(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            CxAutoStartTarget(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            CxAutoStartTarget(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        )

        has("vivo", "iqoo") -> listOf(
            CxAutoStartTarget(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            CxAutoStartTarget(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
            CxAutoStartTarget(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
        )

        has("samsung") -> listOf(
            CxAutoStartTarget(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
            CxAutoStartTarget(
                "com.samsung.android.sm",
                "com.samsung.android.sm.ui.appmanagement.AppManagementActivity",
            ),
            CxAutoStartTarget(
                "com.samsung.android.sm_cn",
                "com.samsung.android.sm.ui.appmanagement.AppManagementActivity",
            ),
        )

        else -> emptyList()
    }
}