package com.sdmu.guanlitong.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开机恢复判定：只有「实时提醒开着 + 已登录」才拉起服务；后台启动被拒时
 * 只回报 START_DENIED 供调用方排一次兜底，不在这里重试。
 */
class CxSignBootRestoreTest {

    @Test
    fun monitorOffSkips() {
        var starts = 0
        val outcome = restore(monitor = false, loggedIn = true) { starts++ }.run()

        assertEquals(CxSignBootOutcome.SKIPPED, outcome)
        assertEquals(0, starts)
    }

    @Test
    fun loggedOutSkips() {
        var starts = 0
        val outcome = restore(monitor = true, loggedIn = false) { starts++ }.run()

        assertEquals(CxSignBootOutcome.SKIPPED, outcome)
        assertEquals(0, starts)
    }

    @Test
    fun eligibleStartsServiceOnce() {
        var starts = 0
        val outcome = restore(monitor = true, loggedIn = true) { starts++ }.run()

        assertEquals(CxSignBootOutcome.STARTED, outcome)
        assertEquals("只拉起一次，不重试", 1, starts)
    }

    @Test
    fun deniedForegroundStartAsksForFallback() {
        val outcome = restore(monitor = true, loggedIn = true) {
            throw IllegalStateException("ForegroundServiceStartNotAllowedException")
        }.run()

        assertEquals(CxSignBootOutcome.START_DENIED, outcome)
    }

    @Test
    fun otherFailureDoesNotAskForFallback() {
        val outcome = restore(monitor = true, loggedIn = true) {
            throw SecurityException("no permission")
        }.run()

        assertEquals(CxSignBootOutcome.FAILED, outcome)
    }

    @Test
    fun readingGateNeverThrows() {
        var starts = 0
        val outcome = CxSignBootRestore(
            monitorEnabled = { throw IllegalStateException("prefs broken") },
            loggedIn = { true },
            startService = { starts++ },
        ).run()

        assertEquals(CxSignBootOutcome.FAILED, outcome)
        assertEquals(0, starts)
    }

    @Test
    fun customDenialPredicateIsRespected() {
        val outcome = CxSignBootRestore(
            monitorEnabled = { true },
            loggedIn = { true },
            startService = { throw SecurityException("rom says no") },
            isStartDenied = { it is SecurityException },
        ).run()

        assertEquals(CxSignBootOutcome.START_DENIED, outcome)
    }

    @Test
    fun onlyBootAndPackageReplacedActions() {
        assertTrue(CxBootActions.handles("android.intent.action.BOOT_COMPLETED"))
        assertTrue(CxBootActions.handles("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse("不接厂商私有重启广播", CxBootActions.handles("android.intent.action.QUICKBOOT_POWERON"))
        assertFalse(CxBootActions.handles("android.intent.action.TIME_SET"))
        assertFalse(CxBootActions.handles(null))
    }

    private fun restore(
        monitor: Boolean,
        loggedIn: Boolean,
        start: () -> Unit,
    ) = CxSignBootRestore(
        monitorEnabled = { monitor },
        loggedIn = { loggedIn },
        startService = start,
    )
}