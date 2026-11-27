package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签到页状态机：未登录优先、有数据优先于加载、扫描失败落到空态文案、
 * 实时提醒开关只影响状态条，以及自动签到时的动作文案。
 */
class CxSignPageStateTest {

    private fun state(
        loggedIn: Boolean = true,
        monitorEnabled: Boolean = true,
        scanning: Boolean = false,
        openCount: Int = 0,
        scanFailed: Boolean = false,
        historyCount: Int = 0,
    ) = cxSignPageState(
        loggedIn = loggedIn,
        monitorEnabled = monitorEnabled,
        scanning = scanning,
        openCount = openCount,
        scanFailed = scanFailed,
        historyCount = historyCount,
    )

    @Test
    fun loggedOutWinsOverEverything() {
        val s = state(loggedIn = false, scanning = true, openCount = 3, historyCount = 5)

        assertEquals(CxSignListState.NEEDS_LOGIN, s.listState)
        assertTrue(s.showLoginRequired)
        assertFalse("未登录不显示实时提醒状态条", s.showMonitorRow)
        assertNull("未登录不叠空态文案", s.openEmptyText)
        assertFalse("未登录不显示「还没有签到记录」", s.historyEmpty)
    }

    @Test
    fun openItemsBeatLoading() {
        val s = state(scanning = true, openCount = 2, historyCount = 1)

        assertEquals("下拉刷新时旧数据继续展示", CxSignListState.LIST, s.listState)
        assertNull(s.openEmptyText)
    }

    @Test
    fun loadingOnlyWhenNothingToShowYet() {
        assertEquals(CxSignListState.LOADING, state(scanning = true).listState)
    }

    @Test
    fun failedScanUsesEmptyTextInsteadOfExtraErrorLine() {
        assertEquals(CxSignListState.EMPTY, state(scanFailed = true).listState)
        assertEquals("签到扫描失败，下拉重试", state(scanFailed = true).openEmptyText)
    }

    @Test
    fun plainEmptyTextAndMonitorOffHints() {
        val s = state()

        assertEquals(CxSignListState.EMPTY, s.listState)
        assertEquals("暂无进行中的签到", s.openEmptyText)
        assertTrue("已登录就显示状态条", s.showMonitorRow)

        val off = state(monitorEnabled = false)
        assertTrue(off.showMonitorOffAction)
        assertEquals("实时提醒关着也照常扫描本页", CxSignListState.EMPTY, off.listState)
    }

    @Test
    fun historyEmptyIsSeparateFromOpenEmpty() {
        val s = state()
        assertTrue(s.historyEmpty)

        val withHistory = state(historyCount = 1)
        assertFalse(withHistory.historyEmpty)
    }

    @Test
    fun actionLabelPrefersGestureAndAutomation() {
        assertEquals(
            "手势活动永远不自动提交",
            "点击手划签到",
            cxSignActionLabel(gesture = true, autoEnabled = true, recordStatus = null),
        )
        assertEquals(
            "自动关闭时提示手点",
            "点击去处理",
            cxSignActionLabel(gesture = false, autoEnabled = false, recordStatus = null),
        )
        assertEquals(
            "自动开着但还没结果",
            "自动处理中",
            cxSignActionLabel(gesture = false, autoEnabled = true, recordStatus = null),
        )
        assertEquals(
            "自动成功过就不要求再点",
            "已自动签到",
            cxSignActionLabel(
                gesture = false,
                autoEnabled = true,
                recordStatus = CxSignRecordStatus.SUCCESS,
            ),
        )
        assertEquals(
            "自动失败要能手动补",
            "自动失败，点击手动处理",
            cxSignActionLabel(
                gesture = false,
                autoEnabled = true,
                recordStatus = CxSignRecordStatus.GAVE_UP,
            ),
        )
    }
}