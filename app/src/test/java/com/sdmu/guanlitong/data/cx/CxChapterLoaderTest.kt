package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 并发上限、全局间隔与 403 降级：都是写死的常量，必须有测试盯着。 */
class CxChapterLoaderTest {

    private fun chapters(count: Int) =
        (1..count).map { CxChapterChild("ch$it", "第 $it 章") }

    /** 测试里把间隔压到 0，只验证调度逻辑，不测真实等待。 */
    private fun loader(maxParallel: Int = CxChapterLoader.MAX_PARALLEL) =
        CxChapterLoader(
            maxParallel = maxParallel,
            gapMs = 0,
            serialGapMs = 0,
            pacer = CxRequestPacer(0),
        )

    @Test(timeout = 10_000)
    fun parallelIsCappedAtConstant() = runBlocking {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()

        loader().load(
            chapters = chapters(9),
            fetch = {
                val now = inFlight.incrementAndGet()
                maxSeen.updateAndGet { maxOf(it, now) }
                delay(60)
                inFlight.decrementAndGet()
                emptyList()
            },
        )

        assertEquals(CxChapterLoader.MAX_PARALLEL, maxSeen.get())
    }

    @Test(timeout = 15_000)
    fun riskResponseDegradesToSerial() = runBlocking {
        val preDegrade = setOf("ch1", "ch2", "ch3")
        val riskPassed = CompletableDeferred<Unit>()
        val inFlight = AtomicInteger()
        val maxAfterDegrade = AtomicInteger()

        val degraded = loader().load(
            chapters = chapters(9),
            fetch = { chapter ->
                if (chapter.chapterId == "ch1") {
                    riskPassed.complete(Unit)
                    throw CxException("学习通要求验证", CxChapterLoader.RISK_CODE)
                }
                riskPassed.await()
                if (chapter.chapterId !in preDegrade) {
                    val now = inFlight.incrementAndGet()
                    maxAfterDegrade.updateAndGet { maxOf(it, now) }
                    delay(40)
                    inFlight.decrementAndGet()
                }
                emptyList()
            },
        )

        assertTrue("遇到 403 必须降级", degraded)
        assertEquals("降级后同一时刻只允许一个请求", 1, maxAfterDegrade.get())
    }

    @Test(timeout = 10_000)
    fun networkFailureMarksOnlyThatChapter() = runBlocking {
        val results = mutableMapOf<Int, CxChildContent>()

        val degraded = loader().load(
            chapters = chapters(3),
            fetch = { chapter ->
                if (chapter.chapterId == "ch2") {
                    throw CxException("网络请求失败（SocketTimeoutException）", CX_CODE_NETWORK)
                }
                emptyList()
            },
            onResult = { index, content -> results[index] = content },
        )

        assertFalse(degraded)
        assertEquals(3, results.size)
        assertNull(results[0]?.error)
        assertNotNull(results[1]?.error)
        assertNull(results[2]?.error)
    }

    /** 502（mArg 解析失败）只坏这一章，整页必须继续加载。 */
    @Test(timeout = 10_000)
    fun parseFailureMarksOnlyThatChapter() = runBlocking {
        val results = mutableMapOf<Int, CxChildContent>()

        val degraded = loader().load(
            chapters = chapters(4),
            fetch = { chapter ->
                if (chapter.chapterId == "ch3") {
                    throw CxException("学习通章节数据解析失败", 502)
                }
                emptyList()
            },
            onResult = { index, content -> results[index] = content },
        )

        assertFalse(degraded)
        assertEquals("坏章节不能中断其余章节", 4, results.size)
        assertNull(results[0]?.error)
        assertNull(results[1]?.error)
        assertNotNull(results[2]?.error)
        assertNull(results[3]?.error)
    }

    /** 401 是登录失效，必须整页上抛，而不是每章标错。 */
    @Test(timeout = 10_000)
    fun authFailureIsRethrown() = runBlocking {
        var otherChaptersRan = 0

        val thrown = try {
            loader(maxParallel = 1).load(
                chapters = chapters(4),
                fetch = { chapter ->
                    if (chapter.chapterId == "ch2") {
                        throw CxException("登录已失效，请重新登录", CX_CODE_AUTH)
                    }
                    otherChaptersRan++
                    emptyList()
                },
            )
            null
        } catch (e: CxException) {
            e
        }

        assertNotNull("401 必须上抛", thrown)
        assertEquals(CX_CODE_AUTH, thrown?.code)
        assertEquals("第一轮的第一章读到后就要停，不能再往后刷", 1, otherChaptersRan)
    }
}

/** 全局节流：相邻请求的开始时间必须拉开，且是跨协程共享的间隔。 */
class CxRequestPacerTest {

    @Test
    fun spacesSequentialRequests() = runBlocking {
        var now = 0L
        val waits = mutableListOf<Long>()
        val pacer = CxRequestPacer(
            defaultGapMs = 250,
            nowMs = { now },
            wait = { millis -> waits += millis; now += millis },
        )

        pacer.acquire()
        pacer.acquire()
        pacer.acquire(gapMs = 500)

        assertEquals(listOf(250L, 250L), waits)
    }

    @Test
    fun spacesConcurrentRequestsGlobally() = runBlocking {
        var now = 0L
        var totalWait = 0L
        val pacer = CxRequestPacer(
            defaultGapMs = 100,
            nowMs = { now },
            wait = { millis -> totalWait += millis; now += millis },
        )

        val jobs = listOf(1, 2, 3).map { launch { pacer.acquire() } }
        jobs.forEach { it.join() }

        assertEquals("并发请求要共享全局间隔：第一个不等，后两个各等 100", 200L, totalWait)
    }
}