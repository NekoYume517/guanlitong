package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 定时刷课状态机：字段与旧项目 `tbs_states` 一一对应。 */
class ZhsTaskStateMachineTest {

    private fun machine() = ZhsTaskStateMachine(clock = { 1_000L }, stamp = { "00:00:00" })

    private fun progress(
        index: Int = 1,
        total: Int = 3,
        name: String = "第一节",
        percent: Int = 0,
        speedLabel: String = "",
        addedSec: Int = 0,
        status: ZhsStudyStatus = ZhsStudyStatus.RUNNING,
        lessonId: Long = 11,
        lessonVideoId: Long = 22,
        round: Int = 0,
        videoSec: Int = 300,
        answered: Int = 0,
    ) = ZhsStudyProgress(
        index, total, name, percent, speedLabel, addedSec, status,
        lessonId, lessonVideoId, round, videoSec, answered,
    )

    private fun item(lessonId: Long = 11, name: String = "单节视频", sec: Int = 200) = ZhsFlatItem(
        chapterId = 1,
        lessonId = lessonId,
        lessonVideoId = 22,
        videoId = 33,
        sec = sec,
        name = name,
        studyTime = 0,
        watched = false,
    )

    @Test
    fun batchProgressFillsCurrentAndLessons() {
        val m = machine()
        m.beginBatch(courseId = 7, courseName = "形势与政策", targetSec = 1500, speed = ZhsSpeedChoice.DEFAULT)

        m.onProgress(progress(percent = 50, speedLabel = "默认 · 3个请求"))

        val st = m.state.value
        assertTrue(st.running)
        assertEquals("1/3", st.current)
        assertEquals("第一节", st.currentName)
        assertEquals(3, st.totalLessons)
        assertEquals(0, st.lessonsDone)
        assertEquals(50, st.currentProgress)
        assertEquals(50, st.lessons.getValue(11).progress)
        assertEquals(ZhsLessonStatus.RUNNING, st.lessons.getValue(11).status)
        assertEquals("00:00:00 共 3 个视频, 目标 25 分钟", st.log.last())
    }

    @Test
    fun doneAccumulatesGainedAndAnswered() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.onProgress(progress(percent = 100, status = ZhsStudyStatus.DONE, addedSec = 120, answered = 2))

        val st = m.state.value
        assertEquals(120, st.gained)
        assertEquals(1, st.lessonsDone)
        assertEquals(100, st.currentProgress)
        assertEquals(120, st.lessons.getValue(11).gained)
        assertEquals(2, st.lessons.getValue(11).answered)
        assertEquals(ZhsLessonStatus.DONE, st.lessons.getValue(11).status)
    }

    @Test
    fun secondRoundUsesRoundWording() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.onProgress(progress(percent = 30, round = 2, name = "最短视频"))

        val st = m.state.value
        assertEquals("第2轮", st.current)
        assertEquals("[最短] 最短视频", st.currentName)
        assertEquals("R2", st.lessons.getValue(11).index)
    }

    @Test
    fun finishMarksTargetMet() {
        val m = machine()
        m.beginBatch(7, "课程", 600, ZhsSpeedChoice.DEFAULT)
        m.onProgress(progress(status = ZhsStudyStatus.DONE, addedSec = 600))

        m.finish(ZhsStudyOutcome(brushedCount = 2, addedSec = 600, targetSec = 600, captcha = false, answered = 0))

        val st = m.state.value
        assertFalse(st.running)
        assertEquals(600, st.gained)
        assertEquals("达标：本次累计新增 10分00秒 / 目标 10分00秒", st.summary)
        assertTrue(st.log.last().endsWith("🎯 达标: 累计新增 10分00秒"))
    }

    @Test
    fun finishUnmetAndCancelledHaveDistinctSummary() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.finish(ZhsStudyOutcome(2, 300, 1500, captcha = false, answered = 0))
        assertEquals("结束：本次累计新增 5分00秒 / 目标 25分00秒", m.state.value.summary)

        val m2 = machine()
        m2.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m2.finish(ZhsStudyOutcome(1, 60, 1500, captcha = false, answered = 0, cancelled = true))
        assertEquals("已停止：本次累计新增 1分00秒", m2.state.value.summary)
        assertTrue(m2.state.value.log.last().endsWith("结束: 累计新增 1分00秒"))
    }

    @Test
    fun captchaStopsAndKeepsSummary() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.onProgress(progress(status = ZhsStudyStatus.CAPTCHA, lessonId = 12))
        assertEquals(ZhsLessonStatus.CAPTCHA, m.state.value.lessons.getValue(12).status)

        m.finish(ZhsStudyOutcome(0, 0, 1500, captcha = true, answered = 0))
        val st = m.state.value
        assertFalse(st.running)
        assertTrue(st.captcha)
        assertEquals("触发滑块验证，刷课已停止", st.summary)
    }

    @Test
    fun itemModeHasSingleLesson() {
        val m = machine()
        m.beginItem(7, "课程", item(), ZhsSpeedChoice.custom(8.0))
        val st = m.state.value
        assertEquals(ZhsTaskMode.ITEM, st.mode)
        assertEquals("1/1", st.current)
        assertEquals(1, st.totalLessons)
        assertEquals("自定义 8x", st.speedText)
        assertEquals(200, st.lessons.getValue(11).total)
    }

    @Test
    fun stopOnlyLogsAndFinishClearsRunning() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.stop()
        assertTrue(m.state.value.running)
        assertTrue(m.state.value.log.last().endsWith("用户手动停止"))
        m.finish(ZhsStudyOutcome(0, 0, 1500, captcha = false, answered = 0, cancelled = true))
        assertFalse(m.state.value.running)
    }

    @Test
    fun chooseSpeedUpdatesLabel() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.chooseSpeed(ZhsSpeedChoice.custom(16.0))
        assertEquals("自定义 16x", m.state.value.speedText)
        assertTrue(m.state.value.log.last().endsWith("切换倍速: 自定义 16x"))
    }

    @Test
    fun failSetsErrorAndKeepsLog() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        m.fail("登录已过期，请重新登录", expired = true)
        val st = m.state.value
        assertFalse(st.running)
        assertEquals("登录已过期，请重新登录", st.error)
        assertTrue(st.expired)
        assertTrue(st.log.last().endsWith("异常: 登录已过期，请重新登录"))
    }

    @Test
    fun logKeepsLastThirtyEntries() {
        val m = machine()
        m.beginBatch(7, "课程", 1500, ZhsSpeedChoice.DEFAULT)
        repeat(40) { m.onLog("line-$it") }
        val logs = m.state.value.log
        assertEquals(30, logs.size)
        assertTrue(logs.last().endsWith("line-39"))
    }
}