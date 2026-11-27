package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收件箱签到扫描：只返回开放中的签到、按结束时间升序、并发纪律下 401/403 整次停下。
 */
class CxSignScannerTest {

    private val courseA = CxCourse("c1", "k1", "p1", "高等数学", "", "", "")
    private val courseB = CxCourse("c2", "k2", "p1", "大学英语", "", "", "")

    private fun active(
        id: String,
        type: Int = 2,
        status: Int = 1,
        otherId: String = "0",
        endTime: String = "",
        name: String = "课堂签到",
    ) = CxActive(
        id = id,
        name = name,
        activeType = type,
        status = status,
        otherId = otherId,
        startTime = "",
        endTime = endTime,
        address = "",
    )

    private class FakeGateway(
        private val courses: List<CxCourse>,
        private val actives: Map<String, List<CxActive>>,
        private val error: Exception? = null,
        private val restrictedCourseId: String? = null,
    ) : CxSignGateway {
        var courseCalls = 0
        var activeCalls = 0

        override suspend fun courses(): List<CxCourse> {
            courseCalls++
            return courses
        }

        override suspend fun actives(course: CxCourse): List<CxActive> {
            activeCalls++
            error?.let { throw it }
            if (course.courseId == restrictedCourseId) {
                throw CxSignRestrictedException(
                    CxSignRestriction(
                        CxDateTime.parseMs("2026-11-27 13:00:00", 0L)!!,
                        "您已被限制访问，解除时间 2026-11-27 13:00:00",
                    ),
                )
            }
            return actives[course.courseId].orEmpty()
        }

        override suspend fun sign(
            active: CxActive,
            course: CxCourse,
            kind: CxSignKind,
            settings: CxSignSettings,
        ): CxSignResult = throw IllegalStateException("扫描器不应该提交")
    }

    /** 测试里不等待：间隔 0、时钟固定。 */
    private fun noWaitPacer() = CxRequestPacer(
        defaultGapMs = 0L,
        nowMs = { 0L },
        wait = {},
    )

    private val now = CxDateTime.parseMs("2026-11-27 12:00:00", 0L)!!

    /** 每个用例独立节流器（gap=0 等于关闭），避免共享单例在测试间串状态。 */
    private fun scanner(
        gateway: CxSignGateway,
        maxParallel: Int = CX_SIGN_SCAN_MAX_PARALLEL,
        throttle: CxSignScanThrottle = CxSignScanThrottle(gapMs = 0),
        clock: () -> Long = { now },
    ) = CxSignScanner(
        gateway = gateway,
        nowMs = clock,
        pacer = noWaitPacer(),
        maxParallel = maxParallel,
        throttle = throttle,
    )

    @Test(timeout = 10_000)
    fun onlyOpenCheckinsSortedByEndTime() = runBlocking {
        val gateway = FakeGateway(
            listOf(courseA, courseB),
            mapOf(
                "c1" to listOf(
                    active("a-late", endTime = "2026-11-27 12:30:00"),
                    active("a-closed", status = 2, endTime = "2026-11-27 12:05:00"),
                    active("a-checkout", type = 74, endTime = "2026-11-27 12:06:00"),
                    active(id = "", endTime = "2026-11-27 12:04:00"),
                ),
                "c2" to listOf(
                    active("b-soon", otherId = "3", endTime = "2026-11-27 12:10:00"),
                ),
            ),
        )

        val signs = scanner(gateway).scan()

        assertEquals(listOf("b-soon", "a-late"), signs.map { it.activeId })
        assertEquals("大学英语", signs.first().courseName)
        assertEquals("手势", signs.first().modeLabel)
        assertTrue(signs.first().gesture)
        assertEquals(
            CxDateTime.parseMs("2026-11-27 12:10:00", now),
            signs.first().endsAtMs,
        )
        assertEquals(1, gateway.courseCalls)
        assertEquals("两门课都要拉一次活动", 2, gateway.activeCalls)
    }

    @Test(timeout = 10_000)
    fun succeededActiveIdsAreExcludedFromOpen() = runBlocking {
        val gateway = FakeGateway(
            listOf(courseA, courseB),
            mapOf(
                "c1" to listOf(
                    active("a-open"),
                    active("a-signed", endTime = "2026-11-27 12:05:00"),
                ),
                "c2" to listOf(active("b-open", endTime = "2026-11-27 12:20:00")),
            ),
        )

        val signs = scanner(gateway)
            .scan(excludeActiveIds = setOf("a-signed"))

        assertEquals(
            "服务端已签仍回 status=1，但本地成功过的不能算进行中；有结束时间的排前面",
            listOf("b-open", "a-open"),
            signs.map { it.activeId },
        )
    }

    @Test(timeout = 10_000)
    fun scanResultReportsCourseAndActiveCounts() = runBlocking {
        val gateway = FakeGateway(
            listOf(courseA, courseB),
            mapOf(
                "c1" to listOf(
                    active("a-open", endTime = "2026-11-27 12:10:00"),
                    active("a-closed", status = 2),
                    active("a-checkout", type = 74),
                ),
                "c2" to listOf(active("b-open", endTime = "2026-11-27 12:20:00")),
            ),
        )

        val result = scanner(gateway).scanResult()

        assertEquals("诊断统计：扫了两门课、看到四条活动", 2, result.coursesScanned)
        assertEquals(4, result.activesSeen)
        assertEquals(listOf("a-open", "b-open"), result.open.map { it.activeId })
        assertEquals("完成时间取扫描结束时的时钟", now, result.finishedAtMs)
        assertEquals("scan() 与 scanResult().open 口径一致", result.open.size, 2)
    }

    @Test(timeout = 10_000)
    fun unknownEndTimeSortsLast() = runBlocking {
        val gateway = FakeGateway(
            listOf(courseA),
            mapOf(
                "c1" to listOf(
                    active("no-end", endTime = ""),
                    active("has-end", endTime = "2026-11-27 12:20:00"),
                ),
            ),
        )

        val signs = scanner(gateway).scan()

        assertEquals(listOf("has-end", "no-end"), signs.map { it.activeId })
        assertEquals(null, signs.last().endsAtMs)
    }

    @Test(timeout = 10_000)
    fun restrictionAbortsRemainingCourses() = runBlocking {
        val gateway = FakeGateway(
            listOf(courseA, courseB),
            mapOf(
                "c1" to listOf(active("a1")),
                "c2" to listOf(active("b1")),
            ),
            restrictedCourseId = "c1",
        )

        val thrown = try {
            scanner(gateway, maxParallel = 1).scan()
            null
        } catch (e: CxSignRestrictedException) {
            e
        }

        assertTrue("命中风控必须上抛，界面据此显示横幅", thrown != null)
        assertEquals("只发了第一门课的 activelist", 1, gateway.activeCalls)
        assertEquals("受限后不再请求后续课程", 1, gateway.courseCalls)
    }

    @Test(timeout = 10_000)
    fun scansWithinGapReuseLastResult() = runBlocking {
        var clock = now
        val gateway = FakeGateway(
            listOf(courseA),
            mapOf("c1" to listOf(active("a-open", endTime = "2026-11-27 12:20:00"))),
        )
        val throttle = CxSignScanThrottle(gapMs = 60_000)
        val scanner = scanner(gateway, throttle = throttle, clock = { clock })

        val first = scanner.scanResult()
        assertEquals(1, gateway.courseCalls)
        assertEquals(1, gateway.activeCalls)

        // 间隔内（30s）手动刷新：直接复用上次结果，不再发请求。
        clock += 30_000
        val second = scanner.scanResult(excludeActiveIds = setOf("a-open"))
        assertEquals("60s 内的刷新不给接口加负担", 1, gateway.courseCalls)
        assertEquals(now, second.finishedAtMs)
        assertTrue("复用结果仍按新排除集合重筛", second.open.isEmpty())

        // 超过间隔（61s）：重新完整扫描。
        clock += 31_000
        val third = scanner.scanResult()
        assertEquals(2, gateway.courseCalls)
        assertEquals(now + 61_000, third.finishedAtMs)
        assertEquals(listOf("a-open"), third.open.map { it.activeId })
    }

    @Test(timeout = 10_000)
    fun authErrorStopsWholeScan() = runBlocking {
        val gateway = FakeGateway(
            listOf(courseA),
            mapOf("c1" to listOf(active("a1"))),
            error = CxException("登录失效", CX_CODE_AUTH),
        )

        val thrown = try {
            scanner(gateway).scan()
            null
        } catch (e: CxException) {
            e
        }

        assertEquals(CX_CODE_AUTH, thrown?.code)
    }

    @Test
    fun remainTextDegradesByUnit() {
        assertEquals("45 秒", cxRemainText(45_000))
        assertEquals("1 分 05 秒", cxRemainText(65_000))
        assertEquals("1 小时 01 分", cxRemainText(3_660_000))
        assertEquals("0 秒", cxRemainText(-1))
    }
}