package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CxStudyEngineTest {

    private val course = CxCourse("c1", "k1", "p1", "测试课程", "", "", "")

    private fun task(jobid: String): CxStudyTask {
        val card = CxTaskCard(
            chapterId = "ch1",
            jobid = jobid,
            objectId = "obj_$jobid",
            mid = "",
            type = "video",
            name = jobid,
            isPassed = false,
            duration = 300,
            attDuration = 300,
            attDurationEnc = "enc",
            begins = 0,
            ends = 0,
            otherInfo = "",
            reportUrl = "https://mooc1-1.chaoxing.com/log",
        )
        val studyCourse = CxStudyCourse("c1", "k1", "p1", "测试课程", "p1", "enc")
        return CxStudyTask(studyCourse, "ch1", "第一章", card)
    }

    private class FakeGateway(
        private val scans: MutableList<CxScanResult>,
        private val failedJobids: Set<String>,
        private val failFirstOnly: Boolean = false,
        private val thrown: Exception? = null,
    ) : CxStudyGateway {
        var scanCalls = 0
        var reloginCalls = 0
        val brushed = mutableListOf<String>()
        private val alreadyFailed = mutableSetOf<String>()

        override suspend fun scan(course: CxCourse): CxScanResult {
            thrown?.let { if (it is CxException || it is CancellationException) throw it }
            scanCalls++
            return scans.removeFirst()
        }

        override suspend fun brush(
            task: CxStudyTask,
            onProgress: (CxStudyProgress) -> Unit,
        ): CxStudyOutcome {
            thrown?.let { throw it }
            brushed += task.card.jobid
            onProgress(CxStudyProgress(task.card, 60, 300))
            val fails = task.card.jobid in failedJobids &&
                (!failFirstOnly || alreadyFailed.add(task.card.jobid))
            return CxStudyOutcome(!fails, if (fails) "未通过" else "已通过")
        }

        override suspend fun relogin() {
            reloginCalls++
        }
    }

    private fun scanOf(vararg tasks: CxStudyTask) = CxScanResult(tasks.toList())

    private fun partialScan(
        missed: List<String>,
        vararg tasks: CxStudyTask,
    ) = CxScanResult(tasks.toList(), missed)

    private fun audioTask(jobid: String): CxStudyTask {
        val base = task(jobid)
        return base.copy(card = base.card.copy(type = "audio"))
    }

    @Test
    fun allPassedStopsAfterFirstRound() = runBlocking {
        val gateway = FakeGateway(
            scans = mutableListOf(scanOf(task("a"), task("b"))),
            failedJobids = emptySet(),
        )
        val events = mutableListOf<CxStudyEvent>()
        val summary = CxStudyEngine(gateway).run(course) { events += it }

        assertEquals(1, gateway.scanCalls)
        assertEquals(0, gateway.reloginCalls)
        assertEquals(2, summary.total)
        assertEquals(2, summary.passed)
        assertEquals(0, summary.failed)
        assertTrue(summary.allPassed)
        assertTrue(events.last() is CxStudyEvent.Finished)
    }

    @Test
    fun failedTaskIsRescannedAndRetriedOnce() = runBlocking {
        val gateway = FakeGateway(
            scans = mutableListOf(
                scanOf(task("a"), task("b"), task("c")),
                scanOf(task("b")),
            ),
            failedJobids = setOf("b"),
            failFirstOnly = true,
        )
        val summary = CxStudyEngine(gateway).run(course)

        assertEquals(2, gateway.scanCalls)
        assertEquals(listOf("a", "b", "c", "b"), gateway.brushed)
        assertEquals(3, summary.total)
        assertEquals(3, summary.passed)
        assertEquals(0, summary.failed)
    }

    @Test
    fun consecutiveFailuresTriggerOneRelogin() = runBlocking {
        val gateway = FakeGateway(
            scans = mutableListOf(
                scanOf(task("a"), task("b"), task("c")),
                scanOf(task("a"), task("b"), task("c")),
            ),
            failedJobids = setOf("a", "b", "c"),
        )
        val summary = CxStudyEngine(gateway).run(course)

        assertEquals(1, gateway.reloginCalls)
        assertEquals(0, summary.passed)
        assertEquals(3, summary.failed)
        assertFalse(summary.allPassed)
    }

    @Test
    fun antiCrawlStopsRunWithoutRetry() = runBlocking {
        val gateway = FakeGateway(
            scans = mutableListOf(scanOf()),
            failedJobids = emptySet(),
            thrown = CxException("学习通要求验证，请稍后再试", 403),
        )
        val summary = CxStudyEngine(gateway).run(course)

        assertNotNull(summary.fatalError)
        assertEquals(0, gateway.scanCalls)
        assertEquals(0, gateway.reloginCalls)
        assertFalse(summary.cancelled)
    }

    @Test
    fun cancellationReturnsCancelledSummary() = runBlocking {
        val gateway = FakeGateway(
            scans = mutableListOf(scanOf(task("a"))),
            failedJobids = emptySet(),
            thrown = CancellationException("cancel"),
        )
        val summary = CxStudyEngine(gateway).run(course)

        assertTrue(summary.cancelled)
    }

    @Test
    fun audioAndVideoBothEnterQueue() = runBlocking {
        val gateway = FakeGateway(
            scans = mutableListOf(scanOf(task("v1"), audioTask("a1"))),
            failedJobids = emptySet(),
        )
        val events = mutableListOf<CxStudyEvent>()
        val summary = CxStudyEngine(gateway).run(course) { events += it }

        assertEquals(listOf("v1", "a1"), gateway.brushed)
        assertEquals(2, summary.passed)
        val scanned = events.filterIsInstance<CxStudyEvent.Scanned>().first()
        assertEquals(1, scanned.videoCount)
        assertEquals(1, scanned.audioCount)
    }

    @Test
    fun missedChapterMarksPartialAndNeverComplete() = runBlocking {
        val gateway = FakeGateway(
            scans = mutableListOf(
                partialScan(listOf("第二章"), task("a")),
                partialScan(listOf("第二章")),
            ),
            failedJobids = emptySet(),
        )
        val summary = CxStudyEngine(gateway).run(course)

        assertEquals(2, gateway.scanCalls)
        assertTrue(summary.partial)
        assertTrue(summary.allPassed.not())
        assertEquals(listOf("第二章"), summary.missedChapters)
        assertEquals(1, summary.passed)
    }

    @Test
    fun missedChapterRecoveredOnSecondScanIsComplete() = runBlocking {
        val gateway = FakeGateway(
            scans = mutableListOf(
                partialScan(listOf("第二章")),
                scanOf(task("a")),
            ),
            failedJobids = emptySet(),
        )
        val summary = CxStudyEngine(gateway).run(course)

        assertEquals(2, gateway.scanCalls)
        assertEquals(listOf("a"), gateway.brushed)
        assertEquals(0, summary.failed)
        assertEquals(1, summary.total)
        assertEquals(1, summary.passed)
        assertTrue(summary.allPassed)
    }
}