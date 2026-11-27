package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 位置签到/签退的数值与流程测试，全部对照老项目
 * `chaoxing-fenbushi/server/app/cx_api.py:204-416` 的公式。
 */
class CxSignLocatorTest {

    /** 教师点放在种子附近（1km 内）：局部平面投影误差在这个尺度下 < 1m。 */
    private val teacher = 36.005 to 117.005
    private val seed = 36.0 to 117.0

    @Test
    fun offsetAndHaversineAreInverse() {
        for (bearing in listOf(0.0, 90.0, 120.0, 240.0, 359.0)) {
            val probe = CxSignGeo.offsetPoint(seed.first, seed.second, 3000.0, bearing)
            val distance = CxSignGeo.haversineM(seed.first, seed.second, probe.first, probe.second)
            assertEquals("方位角 $bearing 往返距离", 3000.0, distance, 0.5)
        }
    }

    @Test
    fun trilaterateRecoversTeacherWithinOneMeter() {
        val probes = CxSignGeo.PROBE_BEARINGS.map {
            CxSignGeo.offsetPoint(seed.first, seed.second, CxSignGeo.PROBE_OFFSET_M, it)
        }
        val distances = probes.map {
            CxSignGeo.haversineM(it.first, it.second, teacher.first, teacher.second)
        }

        val solved = CxSignGeo.trilaterate(
            probes[0], distances[0],
            probes[1], distances[1],
            probes[2], distances[2],
        )
        val error = CxSignGeo.haversineM(solved.first, solved.second, teacher.first, teacher.second)
        assertTrue("误差 $error 米应小于 1 米", error < 1.0)
    }

    @Test
    fun trilaterateFallsBackToCentroidWhenCollinear() {
        val p1 = 36.00 to 117.0
        val p2 = 36.01 to 117.0
        val p3 = 36.02 to 117.0

        val solved = CxSignGeo.trilaterate(p1, 1000.0, p2, 2000.0, p3, 3000.0)

        assertEquals(36.01, solved.first, 1e-9)
        assertEquals(117.0, solved.second, 1e-9)
    }

    @Test
    fun distanceRegexParsesPlainAndScientific() {
        assertEquals(
            1234.0,
            CxSignFeedback.distanceM("距教师指定签到地点1234米")!!,
            1e-9,
        )
        assertEquals(
            12515441.0,
            CxSignFeedback.distanceM("距签退地点1.2515441E7米")!!,
            1e-9,
        )
        assertNull(CxSignFeedback.distanceM("success"))
        assertNull(CxSignFeedback.distanceM("distance: 12"))
    }

    @Test
    fun feedbackStrings() {
        assertTrue(CxSignFeedback.succeeded("success"))
        assertTrue(CxSignFeedback.succeeded("签到成功"))
        assertTrue(CxSignFeedback.succeeded("您已签到"))
        assertTrue(CxSignFeedback.succeeded("已退"))
        assertFalse(CxSignFeedback.succeeded("fail"))

        assertTrue(CxSignFeedback.outOfRange("不在签到范围"))
        assertTrue(CxSignFeedback.outOfRange("超出范围"))

        for (hint in listOf("未提交位置信息", "经纬度", "距教师指定地点", "距签退地点")) {
            assertTrue("$hint 应触发位置回退", CxSignFeedback.needsLocation(hint))
        }
        assertFalse(CxSignFeedback.needsLocation("签到成功"))
    }

    @Test
    fun locationPayloadHasResultAndEscapedAddress() {
        val (loc, result) = CxLocPayload.fields(36.005, 117.005, "教学楼\"A\"")
        assertEquals(loc, result)
        assertTrue(loc.contains("\"result\":1"))
        assertTrue(loc.contains("\"latitude\":36.005"))
        assertTrue(loc.contains("教学楼\\\"A\\\""))
    }

    @Test
    fun addressPriorityIsActiveThenSettingsThenDefault() {
        assertEquals("活动地址", signAddress("活动地址", "设置地址"))
        assertEquals("设置地址", signAddress("  ", "设置地址"))
        assertEquals("教师指定地点", signAddress("", ""))
    }

    @Test
    fun plannedModeFollowsOldProjectRules() {
        // 手势签到图案因活动而异：即使以前能配密码，现在也永远走手动。
        assertEquals(CxSignMode.MANUAL, plannedSignMode(3))
        assertEquals(CxSignMode.MANUAL, plannedSignMode(2))
        assertEquals(CxSignMode.MANUAL, plannedSignMode(5))
        assertEquals(CxSignMode.LOCATION, plannedSignMode(4))
        assertEquals(CxSignMode.NORMAL, plannedSignMode(0))
        assertEquals(CxSignMode.NORMAL, plannedSignMode(1))
    }

    @Test
    fun manualReasonNamesTheUnsupportedTypeWithoutPassword() {
        assertTrue(manualSignReason(2, CxSignKind.CHECKIN).contains("二维码"))
        assertTrue(manualSignReason(3, CxSignKind.CHECKIN).contains("手势"))
        assertTrue(manualSignReason(5, CxSignKind.CHECKOUT).contains("拍照"))
        for (otherId in listOf(0, 2, 3, 4, 5)) {
            assertTrue(
                "不再提密码",
                !manualSignReason(otherId, CxSignKind.CHECKIN).contains("密码"),
            )
        }
    }

    // ── 定位链：探针顺序、间隔、短路、越界回退 ──────────────────────────

    private fun distanceBody(fromLat: Double, fromLng: Double): String {
        val meters = CxSignGeo.haversineM(fromLat, fromLng, teacher.first, teacher.second)
        return "距教师指定签到地点${meters}米"
    }

    @Test(timeout = 10_000)
    fun probesAndFinalSubmitUseHalfSecondGaps() = runBlocking {
        val calls = mutableListOf<Pair<Double, Double>>()
        val waits = mutableListOf<Long>()

        val chain = CxLocateChain(
            submit = { lat, lng, _ ->
                calls += lat to lng
                if (calls.size <= CxSignGeo.PROBE_BEARINGS.size) distanceBody(lat, lng) else "success"
            },
            pause = { waits += it },
            random = Random(7),
        )

        val result = chain.run(seed.first, seed.second, "教师指定地点", CxSignKind.CHECKIN)

        assertTrue(result.success)
        assertEquals("三个探针 + 一次最终提交", 4, calls.size)
        CxSignGeo.PROBE_BEARINGS.forEachIndexed { index, bearing ->
            val expected = CxSignGeo.offsetPoint(
                seed.first, seed.second, CxSignGeo.PROBE_OFFSET_M, bearing,
            )
            assertEquals("第 ${index + 1} 个探针纬度", expected.first, calls[index].first, 1e-9)
            assertEquals("第 ${index + 1} 个探针经度", expected.second, calls[index].second, 1e-9)
        }
        assertEquals("三个探针之间 + 最终提交之前各等 500ms（老项目每个探针后都 sleep(0.5)）", listOf(500L, 500L, 500L), waits)
    }

    @Test(timeout = 10_000)
    fun successOnFirstProbeShortCircuits() = runBlocking {
        val calls = mutableListOf<Pair<Double, Double>>()
        val waits = mutableListOf<Long>()

        val chain = CxLocateChain(
            submit = { lat, lng, _ ->
                calls += lat to lng
                "success"
            },
            pause = { waits += it },
        )

        val result = chain.run(seed.first, seed.second, "教师指定地点", CxSignKind.CHECKIN)

        assertTrue(result.success)
        assertEquals("探测成功必须立刻停，不能再发第二个", 1, calls.size)
        assertTrue("成功即短路，不再等待", waits.isEmpty())
    }

    @Test(timeout = 10_000)
    fun unparseableProbeDistanceFailsFast() = runBlocking {
        var calls = 0
        val chain = CxLocateChain(
            submit = { _, _, _ ->
                calls++
                "场景ID错误"
            },
            pause = { },
        )

        val result = chain.run(seed.first, seed.second, "教师指定地点", CxSignKind.CHECKIN)

        assertFalse(result.success)
        assertTrue(result.message.contains("无法解析距离"))
        assertEquals("解析不到距离立即失败，不瞎猜", 1, calls)
    }

    @Test(timeout = 10_000)
    fun outOfRangeRetriesExactTeacherPointOnce() = runBlocking {
        val calls = mutableListOf<Pair<Double, Double>>()

        val chain = CxLocateChain(
            submit = { lat, lng, _ ->
                calls += lat to lng
                when (calls.size) {
                    1, 2, 3 -> distanceBody(lat, lng)
                    4 -> "不在签到范围"
                    else -> "success"
                }
            },
            pause = { },
            random = Random(11),
        )

        val result = chain.run(seed.first, seed.second, "教师指定地点", CxSignKind.CHECKIN)

        assertTrue(result.success)
        assertTrue(result.message.contains("精确坐标"))
        assertEquals("三探针 + 抖动提交 + 精确坐标回退", 5, calls.size)
        assertEquals("回退提交的就是解算出的教师坐标", result.teacherLat!!, calls.last().first, 1e-9)
        assertEquals(result.teacherLng!!, calls.last().second, 1e-9)
        assertTrue(
            "解算点与真实教师点误差应 <1m",
            CxSignGeo.haversineM(result.teacherLat!!, result.teacherLng!!, teacher.first, teacher.second) < 1.0,
        )
    }

    @Test(timeout = 10_000)
    fun outOfRangeFailsAfterOneRetry() = runBlocking {
        var calls = 0

        val chain = CxLocateChain(
            submit = { lat, lng, _ ->
                calls++
                if (calls <= 3) distanceBody(lat, lng) else "不在范围"
            },
            pause = { },
            random = Random(11),
        )

        val result = chain.run(seed.first, seed.second, "教师指定地点", CxSignKind.CHECKOUT)

        assertFalse(result.success)
        assertTrue(result.message.contains("超出范围"))
        assertEquals("越界只回退一次，不能重试风暴", 5, calls)
    }

    @Test(timeout = 10_000)
    fun finalJitterStaysWithinFiveToFiftyMeters() = runBlocking {
        val calls = mutableListOf<Pair<Double, Double>>()

        val chain = CxLocateChain(
            submit = { lat, lng, _ ->
                calls += lat to lng
                if (calls.size <= 3) distanceBody(lat, lng) else "success"
            },
            pause = { },
            random = Random(3),
        )

        val result = chain.run(seed.first, seed.second, "教师指定地点", CxSignKind.CHECKIN)

        assertTrue(result.success)
        val final = calls[3]
        val offset = CxSignGeo.haversineM(final.first, final.second, teacher.first, teacher.second)
        assertTrue("抖动 $offset 米应在 5–50 米", offset in 4.5..50.5)
        assertNotNull(result.teacherLat)
        assertTrue(
            CxSignGeo.haversineM(result.teacherLat!!, result.teacherLng!!, teacher.first, teacher.second) < 1.0,
        )
        assertFalse(abs(result.teacherLat!! - teacher.first) > 0.0001)
    }
}