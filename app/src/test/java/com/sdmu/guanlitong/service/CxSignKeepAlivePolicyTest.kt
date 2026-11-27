package com.sdmu.guanlitong.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活纯逻辑：是否该拉监控、任务划掉是否排重启、系统状态到界面提示、
 * 厂商自启动候选。
 */
class CxSignKeepAlivePolicyTest {

    @Test
    fun restoreRequiresLoginMonitorAndNotRunning() {
        assertTrue(shouldRestoreSignMonitor(loggedIn = true, monitorEnabled = true, running = false))
        assertFalse("已经 running 不能重复拉起", shouldRestoreSignMonitor(true, true, true))
        assertFalse("未登录不拉", shouldRestoreSignMonitor(false, true, false))
        assertFalse("总开关关闭不拉", shouldRestoreSignMonitor(true, false, false))
        assertFalse(shouldRestoreSignMonitor(false, false, false))
    }

    @Test
    fun taskRemovedSchedulesOnLoginAndMonitorOnly() {
        // 不看 running：服务可能正在被系统拆掉，running 来不及变 false。
        assertTrue(shouldScheduleTaskRemovedRestart(loggedIn = true, monitorEnabled = true))
        assertFalse(shouldScheduleTaskRemovedRestart(loggedIn = false, monitorEnabled = true))
        assertFalse(shouldScheduleTaskRemovedRestart(loggedIn = true, monitorEnabled = false))
    }

    @Test
    fun deniedRetryOnlyWhenExactAlarmsAllowed() {
        assertTrue(
            "有精确闹钟授权：开机拉起被拒后排 30 秒的第二次尝试",
            shouldScheduleDeniedRetry(exactAlarmsAllowed = true),
        )
        assertFalse(
            "没有精确闹钟授权就静默放弃，不排不可预期的非精确闹钟",
            shouldScheduleDeniedRetry(exactAlarmsAllowed = false),
        )
    }

    @Test
    fun deniedRetryDelayIsShortAndOneShot() {
        // 只有一次 30 秒后的重试，不会形成重试风暴；间隔也不该长到等用户自己开 App。
        assertTrue(CxKeepAlive.DENIED_RETRY_DELAY_MS in 1_000..60_000L)
    }

    @Test
    fun batteryHintsFollowSystemState() {
        val notWhitelisted = keepAliveHints(batteryWhitelisted = false, notificationsEnabled = true)
        assertTrue(notWhitelisted.showBatteryWhitelistAction)
        assertTrue(notWhitelisted.batteryStatus.contains("白名单"))
        assertFalse(notWhitelisted.showNotificationSettingsAction)
        assertTrue(notWhitelisted.notificationStatus.contains("已开启"))

        val whitelisted = keepAliveHints(batteryWhitelisted = true, notificationsEnabled = true)
        assertFalse("已在白名单就不再给按钮", whitelisted.showBatteryWhitelistAction)
        assertTrue(whitelisted.batteryStatus.contains("已在"))
    }

    @Test
    fun notificationHintsFollowSystemState() {
        val disabled = keepAliveHints(batteryWhitelisted = true, notificationsEnabled = false)
        assertTrue("通知被关要给入口", disabled.showNotificationSettingsAction)
        assertTrue(disabled.notificationStatus.contains("通知已关闭"))

        val enabled = keepAliveHints(batteryWhitelisted = true, notificationsEnabled = true)
        assertFalse(enabled.showNotificationSettingsAction)
    }

    @Test
    fun vendorTargetsMatchManufacturer() {
        fun firstPackage(manufacturer: String) =
            autoStartTargets(manufacturer, manufacturer).first().packageName

        assertEquals("com.miui.securitycenter", firstPackage("Xiaomi"))
        assertEquals("com.miui.securitycenter", firstPackage("Redmi"))
        assertEquals("com.huawei.systemmanager", firstPackage("HUAWEI"))
        assertEquals("com.hihonor.systemmanager", firstPackage("HONOR"))
        assertEquals("com.coloros.safecenter", firstPackage("OPPO"))
        assertEquals("com.coloros.safecenter", firstPackage("realme"))
        assertEquals("com.vivo.permissionmanager", firstPackage("vivo"))
        assertEquals("com.samsung.android.lool", firstPackage("samsung"))
        assertTrue(
            "未知厂商退到应用详情，不硬猜组件",
            autoStartTargets("unknown", "unknown").isEmpty(),
        )
        assertTrue(autoStartTargets(null, null).isEmpty())
    }

    @Test
    fun vendorTargetsAreWellFormed() {
        val targets = autoStartTargets("Xiaomi", "Redmi")
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.all { it.packageName.isNotBlank() && it.className.isNotBlank() })
    }
}