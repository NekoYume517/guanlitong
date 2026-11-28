package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台服务的收尾判定：只有「已有终态结果」才允许发终态通知并停服务，
 * 「还没开始」必须保持等待，否则启动瞬间会把任务误杀成「已结束」。
 */
class ZhsServicePhaseTest {

    private var now = 1_000L
    private fun machine() = ZhsTaskStateMachine(clock = { now }, stamp = { "00:00:00" })

    private fun outcome(captcha: Boolean = false, cancelled: Boolean = false) = ZhsStudyOutcome(
        brushedCount = 1,
        addedSec = 60,
        targetSec = 600,
        captcha = captcha,
        answered = 0,
        cancelled = cancelled,
    )

    private fun item() = ZhsFlatItem(
        chapterId = 1,
        lessonId = 2,
        lessonVideoId = 3,
        videoId = 4,
        sec = 300,
        name = "第一节",
        studyTime = 0,
        watched = false,
    )

    @Test
    fun notStartedKeepsServiceWaiting() {
        val st = machine().state.value
        assertEquals(ZhsServicePhase.WAIT, st.servicePhase())
        assertFalse(st.hasResult)
        assertFalse(st.running)
    }

    @Test
    fun beginBatchMovesToRunning() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        assertEquals(ZhsServicePhase.RUNNING, m.state.value.servicePhase())
        assertFalse(m.state.value.hasResult)
    }

    @Test
    fun beginItemMovesToRunning() {
        val m = machine()
        m.beginItem(7, "课程", item(), ZhsSpeedChoice.DEFAULT)
        assertEquals(ZhsServicePhase.RUNNING, m.state.value.servicePhase())
    }

    @Test
    fun finishedTaskAllowsFinalNotification() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.finish(outcome())
        val st = m.state.value
        assertEquals(ZhsServicePhase.FINISHED, st.servicePhase())
        assertTrue(st.hasResult)
    }

    @Test
    fun failedTaskIsTerminalToo() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.fail("网络异常")
        val st = m.state.value
        assertEquals(ZhsServicePhase.FINISHED, st.servicePhase())
        assertTrue(st.hasResult)
        assertEquals("网络异常", st.error)
    }

    @Test
    fun manualStopKeepsTaskRunningUntilThisSectionEnds() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.stop()
        val st = m.state.value
        assertEquals(ZhsServicePhase.RUNNING, st.servicePhase())
        assertFalse(st.hasResult)
        assertTrue(st.running)
    }

    @Test
    fun stoppedOutcomeIsTerminal() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.stop()
        m.finish(outcome(cancelled = true))
        assertEquals(ZhsServicePhase.FINISHED, m.state.value.servicePhase())
    }

    @Test
    fun updatedAtTracksEveryStateChange() {
        now = 1_000L
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        assertEquals(1_000L, m.state.value.updatedAt)

        now = 2_000L
        m.onLog("继续")
        assertEquals(2_000L, m.state.value.updatedAt)

        now = 3_000L
        m.finish(outcome())
        assertEquals(3_000L, m.state.value.updatedAt)
    }
}