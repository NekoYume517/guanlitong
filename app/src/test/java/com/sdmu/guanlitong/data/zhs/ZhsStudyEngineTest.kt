package com.sdmu.guanlitong.data.zhs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷课推进策略：跳过已看完、按新增学时累计、滑块验证立即中止、
 * 目标学时阶段 B 反复刷最短视频，以及「一轮零新增就停」的防死循环。
 */
class ZhsStudyEngineTest {

    private val course = ZhsCourse(
        courseId = 1101,
        recruitId = 2201,
        name = "课程",
        teacher = "老师",
        school = "学校",
        progress = null,
        shared = true,
        smart = false,
        secret = "",
    )

    private fun flat(
        lessonId: Long,
        sec: Int = 100,
        studyTime: Int = 0,
        watched: Boolean = false,
        name: String = "视频$lessonId",
    ) = ZhsFlatItem(
        chapterId = 11,
        lessonId = lessonId,
        lessonVideoId = lessonId + 1000,
        videoId = lessonId + 5000,
        sec = sec,
        name = name,
        studyTime = studyTime,
        watched = watched,
    )

    private class FakeGateway(var items: List<ZhsFlatItem>) : ZhsStudyGateway {
        val times = mutableMapOf<Long, Int>()
        val brushed = mutableListOf<Long>()
        val progress = mutableListOf<ZhsStudyProgress>()
        var gain = 60
        var captchaOn: Long? = null

        override suspend fun flatItems(course: ZhsCourse, chapters: List<ZhsChapter>): List<ZhsFlatItem> = items

        override suspend fun studyTime(course: ZhsCourse, item: ZhsFlatItem): Int =
            times[item.lessonId] ?: item.studyTime

        override suspend fun brushItem(
            course: ZhsCourse,
            item: ZhsFlatItem,
            speed: ZhsSpeedController,
            onProgress: (Int, String) -> Unit,
            onLog: (String) -> Unit,
        ): Int {
            if (item.lessonId == captchaOn) throw ZhsCaptchaException()
            brushed += item.lessonId
            onProgress(50, "默认 · 2个请求")
            times[item.lessonId] = (times[item.lessonId] ?: item.studyTime) + gain
            return 1
        }
    }

    private fun engine(gateway: FakeGateway) = ZhsStudyEngine(gateway)

    @Test
    fun skipsFinishedItems() = runBlocking {
        val gateway = FakeGateway(
            listOf(
                flat(1, watched = true),
                flat(2, sec = 100, studyTime = 100),
                flat(3, sec = 100, studyTime = 0),
            ),
        )

        val outcome = engine(gateway).run(course, emptyList(), ZhsSpeedController())

        assertEquals(listOf(3L), gateway.brushed)
        assertEquals(1, outcome.brushedCount)
        assertEquals(60, outcome.addedSec)
        assertEquals(1, outcome.answered)
        assertFalse(outcome.captcha)
    }

    @Test
    fun singleItemBrushesEvenWhenFinished() = runBlocking {
        val item = flat(1, watched = true)
        val gateway = FakeGateway(listOf(item))

        val outcome = engine(gateway).runItem(course, item, ZhsSpeedController())

        assertEquals(listOf(1L), gateway.brushed)
        assertEquals(1, outcome.brushedCount)
        assertEquals(60, outcome.addedSec)
    }

    @Test
    fun captchaStopsImmediately() = runBlocking {
        val gateway = FakeGateway(listOf(flat(1), flat(2)))
        gateway.captchaOn = 1L

        val outcome = engine(gateway).run(course, emptyList(), ZhsSpeedController())

        assertTrue(gateway.brushed.isEmpty())
        assertTrue(outcome.captcha)
        assertEquals(0, outcome.brushedCount)
    }

    @Test
    fun targetMinutesRepeatsShortestUntilReached() = runBlocking {
        val gateway = FakeGateway(listOf(flat(1, sec = 100), flat(2, sec = 300)))

        val outcome = engine(gateway).run(course, emptyList(), ZhsSpeedController(), targetSec = 240)

        // 阶段 A 各刷一遍（120），阶段 B 再按最短视频优先刷到 240。
        assertEquals(listOf(1L, 2L, 1L, 2L), gateway.brushed)
        assertEquals(240, outcome.addedSec)
        assertEquals(4, outcome.brushedCount)
        assertFalse(outcome.captcha)
    }

    @Test
    fun stopsWhenFullCycleAddsNothing() = runBlocking {
        val gateway = FakeGateway(listOf(flat(1), flat(2)))
        gateway.gain = 0

        val outcome = engine(gateway).run(course, emptyList(), ZhsSpeedController(), targetSec = 60)

        // 阶段 A 两节 + 阶段 B 完整一轮两节后判定空转。
        assertEquals(listOf(1L, 2L, 1L, 2L), gateway.brushed)
        assertEquals(0, outcome.addedSec)
        assertEquals(4, outcome.brushedCount)
    }

    @Test
    fun targetReachedDuringPhaseAStopsEarly() = runBlocking {
        val gateway = FakeGateway(listOf(flat(1), flat(2)))

        val outcome = engine(gateway).run(course, emptyList(), ZhsSpeedController(), targetSec = 60)

        assertEquals(listOf(1L), gateway.brushed)
        assertEquals(60, outcome.addedSec)
        assertFalse(outcome.cancelled)
    }

    @Test
    fun stopRequestFinishesCurrentItemThenStops() = runBlocking {
        val gateway = FakeGateway(listOf(flat(1), flat(2)))

        // 第一节刷完后用户点停止：当前节不打断，第二节不再开始。
        val outcome = engine(gateway).run(
            course,
            emptyList(),
            ZhsSpeedController(),
            shouldStop = { gateway.brushed.isNotEmpty() },
        )

        assertEquals(listOf(1L), gateway.brushed)
        assertEquals(1, outcome.brushedCount)
        assertEquals(60, outcome.addedSec)
        assertTrue(outcome.cancelled)
    }

    @Test
    fun emitsProgressEventsForEachItem() = runBlocking {
        val gateway = FakeGateway(listOf(flat(1), flat(2)))
        val events = mutableListOf<ZhsStudyProgress>()

        engine(gateway).run(course, emptyList(), ZhsSpeedController(), onProgress = { events += it })

        assertEquals(6, events.size)
        assertEquals(1, events[0].index)
        assertEquals(2, events[0].total)
        assertEquals(0, events[0].percent)
        assertFalse(events[0].done)
        assertEquals(50, events[1].percent)
        assertEquals(100, events[2].percent)
        assertTrue(events[2].done)
    }
}