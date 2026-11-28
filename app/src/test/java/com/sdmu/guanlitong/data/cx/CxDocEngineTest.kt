package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷时长执行器：起始/收尾心跳、时间与高度公式、meta 兜底、认证与连错中止。
 *
 * 循环本身是开放式的（老项目 Ctrl+C 结束），所以所有用例都靠 [pause] 抛
 * CancellationException 收尾；取消后必须补一次 `h=满分` 的心跳。
 */
class CxDocEngineTest {

    private val course = CxDocCourse(
        courseId = "100",
        clazzId = "200",
        personId = "300",
        cpi = "cpi1",
        enc = "enc1",
        name = "测试课程",
    )

    private val item = CxDocItem(
        name = "讲义.pdf",
        type = "doc",
        id = "data1",
        objectId = "oid1",
    )

    private class FakeGateway : CxDocGateway {
        val beats = mutableListOf<CxDocBeat>()
        val updates = mutableListOf<String>()
        var meta = CxDocMeta(objectId = "oid-from-meta", pageNum = 20, wordCount = 100)
        var metaError: CxException? = null
        var beatStatus = 200

        override suspend fun params(courseId: String, clazzId: String, personId: String) =
            CxDocParams()

        override suspend fun list(course: CxDocCourse, folder: CxDocFolder?) =
            CxDocPage(emptyList())

        override suspend fun meta(objectId: String): CxDocMeta {
            metaError?.let { throw it }
            return meta
        }

        override suspend fun updateReadCount(course: CxDocCourse, dataId: String) {
            updates += dataId
        }

        override suspend fun beat(course: CxDocCourse, beat: CxDocBeat): Int {
            beats += beat
            return beatStatus
        }
    }

    /** 第 [stopAfter] 次暂停时抛出取消，模拟用户停止。 */
    private fun cancellingPause(stopAfter: Int): suspend (Long) -> Unit {
        var count = 0
        return {
            count++
            if (count >= stopAfter) throw CancellationException("用户停止")
        }
    }

    @Test
    fun startsAndStopsWithFullProgress() = runBlocking {
        val gateway = FakeGateway()
        val progress = mutableListOf<CxDocProgress>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                CxDocEngine(
                    gateway,
                    pause = cancellingPause(2),
                    jitter = { 0 },
                    nowMs = { 1_000L },
                ).run(course, item, speed = 10.0) { progress += it }
            }
        }

        assertEquals(listOf("data1"), gateway.updates)
        assertEquals(4, gateway.beats.size)

        val start = gateway.beats[0]
        assertEquals(1, start.state)
        assertEquals(0, start.height)
        assertEquals(1_000L, start.timestampMs)
        assertEquals("oid-from-meta", start.objectId)
        assertEquals(100, start.wordCount)
        assertEquals(20, start.pageNum)
        assertEquals("enc1", start.fileEnc)

        // 20 页 → 满高度 2000；第 1/2 次按线性 27/55，收尾直接满。
        assertEquals(27, gateway.beats[1].height)
        assertEquals(55, gateway.beats[2].height)
        assertEquals(2_000, gateway.beats[3].height)
        assertEquals(2, gateway.beats[3].state)
        assertEquals(101_000L, gateway.beats[2].timestampMs)
        assertEquals(101_000L, gateway.beats[3].timestampMs)

        assertEquals(2, progress.size)
        assertEquals(100L, progress.last().reportedSeconds)
        assertEquals(2_000, progress.last().maxHeight)
    }

    @Test
    fun metaFailureFallsBackToDefaults() = runBlocking {
        val gateway = FakeGateway().apply {
            metaError = CxException("meta 挂了", 502)
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                CxDocEngine(gateway, pause = cancellingPause(1), jitter = { 0 }).run(
                    course,
                    item.copy(objectId = ""),
                    speed = 1.0,
                )
            }
        }

        assertTrue(gateway.beats.isNotEmpty())
        val first = gateway.beats[0]
        assertEquals("data1", first.objectId)
        assertEquals(1659, first.wordCount)
        assertEquals(100, first.pageNum)
        // 100 页 × 100 的收尾心跳。
        assertEquals(10_000, gateway.beats.last().height)
    }

    @Test
    fun authFailureOnMetaAbortsWithoutHeartbeats() {
        val gateway = FakeGateway().apply {
            metaError = CxException("登录已失效", CX_CODE_AUTH)
        }
        val error = assertThrows(CxException::class.java) {
            runBlocking {
                CxDocEngine(gateway, pause = cancellingPause(1)).run(course, item, 1.0)
            }
        }
        assertEquals(CX_CODE_AUTH, error.code)
        assertTrue(gateway.beats.isEmpty())
    }

    @Test
    fun expiredSessionOnFirstBeatStops() {
        val gateway = FakeGateway().apply { beatStatus = 302 }
        val error = assertThrows(CxException::class.java) {
            runBlocking {
                CxDocEngine(gateway, pause = cancellingPause(1)).run(course, item, 1.0)
            }
        }
        assertEquals(CX_CODE_AUTH, error.code)
        // 起始心跳已经发出，失败后不再补「满进度」收尾。
        assertEquals(1, gateway.beats.size)
    }

    @Test
    fun threeConsecutiveFailuresStop() {
        val gateway = FakeGateway().apply { beatStatus = 500 }
        val error = assertThrows(CxException::class.java) {
            runBlocking {
                CxDocEngine(gateway, pause = { }, jitter = { 0 }).run(
                    course,
                    item,
                    1.0,
                )
            }
        }
        assertEquals(502, error.code)
        assertEquals(4, gateway.beats.size)
        assertTrue(gateway.beats.drop(1).all { it.state == 2 })
    }

    @Test
    fun timestampFollowsSpeed() {
        assertEquals(1_000L, CxDocHeight.timestampMs(1_000, 0, 1.0))
        assertEquals(51_000L, CxDocHeight.timestampMs(1_000, 1, 10.0))
        assertEquals(2_001_000L, CxDocHeight.timestampMs(1_000, 1, 400.0))
    }

    @Test
    fun heightIsLinearThenJittersInsideCap() {
        assertEquals(2, CxDocHeight.height(1, 1.0, 2_000, 0) { 0 })
        // 满后不再按线性涨，只在当前高度上抖动，并夹在 0..max 之间。
        assertEquals(1_930, CxDocHeight.height(3600, 1.0, 2_000, 1_900) { 30 })
        assertEquals(0, CxDocHeight.height(3600, 1.0, 2_000, 10) { -50 })
        assertEquals(2_000, CxDocHeight.height(3600, 1.0, 2_000, 1_990) { 30 })
        assertEquals(0, CxDocHeight.height(1, 1.0, 0, 0) { 0 })
    }
}