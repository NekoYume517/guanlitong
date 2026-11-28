package com.sdmu.guanlitong.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 刷课状态每次都带更新时间，日志/界面据此区分「慢」与「卡住」。 */
class CxTaskStatusUpdatedAtTest {

    @Before
    fun setUp() {
        BrushTaskRegistry.clear()
        CxTaskStatus.reset()
    }

    @After
    fun tearDown() {
        CxTaskStatus.reset()
        BrushTaskRegistry.clear()
    }

    @Test
    fun startStampsUpdatedAt() {
        CxTaskStatus.start("高等数学", "刷课")
        assertTrue(CxTaskStatus.state.value.updatedAt > 0)
    }

    @Test
    fun updatesRefreshTimestampAndKeepItMonotonic() {
        CxTaskStatus.start("高等数学", "刷课")
        val first = CxTaskStatus.state.value.updatedAt

        CxTaskStatus.update { it.copy(message = "第一节") }
        val second = CxTaskStatus.state.value.updatedAt
        assertTrue(second >= first)

        CxTaskStatus.update { it.copy(message = "第二节") }
        assertTrue(CxTaskStatus.state.value.updatedAt >= second)
    }

    @Test
    fun finishStampsUpdatedAtAndStopsTask() {
        CxTaskStatus.start("高等数学", "刷课")
        CxTaskStatus.finish(message = "完成")
        val state = CxTaskStatus.state.value
        assertFalse(state.running)
        assertTrue(state.updatedAt > 0)
    }
}