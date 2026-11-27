package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签到轮询：实时提醒总开关默认开、自动开关只决定提交、只提交一次、失败按 60s
 * 间隔重试且最多 5 次、手动类型只通知一次、401/403 上抛、换账号不串记录。
 */
class CxSignRunnerTest {

    private val course = CxCourse(
        courseId = "c1",
        clazzId = "k1",
        personId = "p1",
        name = "高等数学",
        teacher = "",
        coverUrl = "",
        link = "",
    )

    private fun active(
        id: String = "a1",
        type: Int = 2,
        status: Int = 1,
        otherId: String = "0",
        name: String = "课堂签到",
    ) = CxActive(
        id = id,
        name = name,
        activeType = type,
        status = status,
        otherId = otherId,
        startTime = "",
        endTime = "",
        address = "",
    )

    private class FakeLedger : CxSignLedger {
        val records = mutableMapOf<String, CxSignRecord>()

        override fun record(accountKey: String, activeId: String, kind: CxSignKind): CxSignRecord? =
            records[key(accountKey, activeId, kind)]

        override fun save(
            accountKey: String,
            activeId: String,
            kind: CxSignKind,
            record: CxSignRecord,
        ) {
            records[key(accountKey, activeId, kind)] = record
        }

        var recoverCalls = 0

        override fun recoverStaleRunning(accountKey: String, nowMs: Long) {
            recoverCalls++
        }

        val history = mutableListOf<Pair<String, CxSignHistoryEntry>>()

        override fun appendHistory(accountKey: String, entry: CxSignHistoryEntry) {
            history += accountKey to entry
        }

        var restriction: CxSignRestriction? = null

        override fun restriction(accountKey: String, nowMs: Long): CxSignRestriction? =
            restriction?.takeIf { it.untilMs > nowMs }

        override fun saveRestriction(accountKey: String, restriction: CxSignRestriction) {
            this.restriction = restriction
        }

        override fun clearRestriction(accountKey: String) {
            restriction = null
        }

        private fun key(accountKey: String, activeId: String, kind: CxSignKind) =
            signRecordKey(accountKey, activeId, kind)
    }

    private class FakeGateway(
        var actives: List<CxActive> = emptyList(),
        private val courseList: List<CxCourse> = listOf(
            CxCourse("c1", "k1", "p1", "高等数学", "", "", ""),
        ),
        private val activesByCourse: Map<String, List<CxActive>> = emptyMap(),
    ) : CxSignGateway {
        var courseCalls = 0
        var activeCalls = 0
        val activeCourseIds = mutableListOf<String>()
        val signCalls = mutableListOf<Pair<CxActive, CxSignKind>>()
        var lastSettings: CxSignSettings? = null
        var result: CxSignResult = CxSignResult(true, "签到成功")
        var error: Exception? = null
        var activesError: Exception? = null

        override suspend fun courses(): List<CxCourse> {
            courseCalls++
            return courseList
        }

        override suspend fun actives(course: CxCourse): List<CxActive> {
            activeCalls++
            activeCourseIds += course.courseId
            activesError?.let { throw it }
            return activesByCourse[course.courseId] ?: actives
        }

        override suspend fun sign(
            active: CxActive,
            course: CxCourse,
            kind: CxSignKind,
            settings: CxSignSettings,
        ): CxSignResult {
            error?.let { throw it }
            signCalls += active to kind
            lastSettings = settings
            return result
        }
    }

    private val settings = CxSignSettings(
        checkinEnabled = true,
        checkoutEnabled = true,
        seedLat = 36.5,
        seedLng = 117.5,
        address = "教学楼A",
    )

    private fun runner(
        gateway: FakeGateway,
        ledger: FakeLedger = FakeLedger(),
        settings: () -> CxSignSettings = { this.settings },
        account: String = "acct-a",
        // 每轮新建 runner，轮转状态要显式传实例，避免共享单例在测试间串状态。
        rotation: CxSignCourseRotation = CxSignCourseRotation(),
        clock: () -> Long,
    ) = CxSignRunner(gateway, ledger, account, settings, clock, rotation)

    @Test(timeout = 10_000)
    fun monitorOffDoesNothing() = runBlocking {
        val gateway = FakeGateway(listOf(active()))
        val notices = runner(
            gateway,
            settings = { CxSignSettings(monitorEnabled = false) },
            clock = { 0 },
        ).poll()

        assertTrue(notices.isEmpty())
        assertEquals("实时提醒总开关关闭，连课程列表都不该拉", 0, gateway.courseCalls)
    }

    @Test(timeout = 10_000)
    fun monitorOnAutoOffPromptsOnceWithoutSubmitting() = runBlocking {
        val gateway = FakeGateway(listOf(active()))
        val ledger = FakeLedger()
        val runner = runner(
            gateway,
            ledger,
            settings = { CxSignSettings(monitorEnabled = true) },
            clock = { 1_000 },
        )

        val first = runner.poll()
        assertEquals("自动签到没开，绝不能提交", 0, gateway.signCalls.size)
        assertEquals(1, first.size)
        assertEquals(CxSignNotice.PROMPT, first.single().notice)
        assertEquals(CxSignKind.CHECKIN, first.single().kind)
        assertTrue(first.single().message.contains("手动"))
        assertEquals(CxSignRecordStatus.PROMPTED, ledger.records.values.single().status)

        val second = runner.poll()
        assertTrue("同一活动每账号只提醒一次", second.isEmpty())
        assertEquals(0, gateway.signCalls.size)
        assertEquals("两轮各扫一次课程列表（缓存由网关负责）", 2, gateway.courseCalls)

        assertEquals("发现签到只写一条「需手动」记录", 1, ledger.history.size)
        assertEquals("acct-a", ledger.history.single().first)
        with(ledger.history.single().second) {
            assertEquals(CxSignHistoryResult.MANUAL, result)
            assertEquals(CxSignKind.CHECKIN, kind)
            assertEquals("a1", activeId)
            assertEquals("高等数学", courseName)
            assertEquals("课堂签到", activeName)
            assertEquals("c1", courseId)
            assertEquals("k1", clazzId)
            assertEquals(1_000L, atMs)
        }
    }

    @Test(timeout = 10_000)
    fun promptDoesNotBlockMakeUpSignWhenAutoTurnsOn() = runBlocking {
        var current = CxSignSettings(monitorEnabled = true)
        val gateway = FakeGateway(listOf(active()))
        val ledger = FakeLedger()
        val runner = runner(gateway, ledger, settings = { current }, clock = { 0 })

        assertEquals(CxSignNotice.PROMPT, runner.poll().single().notice)
        assertEquals(0, gateway.signCalls.size)
        assertEquals(CxSignRecordStatus.PROMPTED, ledger.records.values.single().status)

        // 用户随后打开自动签到：提醒记录不能把补签吃掉。
        current = current.copy(checkinEnabled = true)
        val notices = runner.poll()
        assertEquals("提醒过不影响补签", 1, gateway.signCalls.size)
        assertEquals(CxSignNotice.SUCCESS, notices.single().notice)
        assertEquals(CxSignRecordStatus.SUCCESS, ledger.records.values.single().status)
    }

    @Test(timeout = 10_000)
    fun promptNeverOverwritesSuccess() = runBlocking {
        val gateway = FakeGateway(listOf(active()))
        val ledger = FakeLedger()
        val key = signRecordKey("acct-a", "a1", CxSignKind.CHECKIN)
        ledger.records[key] = CxSignRecord(CxSignRecordStatus.SUCCESS, 1, 0)

        val notices = runner(
            gateway,
            ledger,
            settings = { CxSignSettings(monitorEnabled = true) },
            clock = { 0 },
        ).poll()

        assertTrue(notices.isEmpty())
        assertEquals(CxSignRecordStatus.SUCCESS, ledger.records[key]!!.status)
        assertEquals(0, gateway.signCalls.size)
    }

    @Test(timeout = 10_000)
    fun checkoutAutoOffAlsoPrompts() = runBlocking {
        val gateway = FakeGateway(listOf(active(type = 74, status = 1, name = "课堂签退")))
        val notices = runner(
            gateway,
            settings = { CxSignSettings(monitorEnabled = true) },
            clock = { 0 },
        ).poll()

        assertEquals(0, gateway.signCalls.size)
        assertEquals(CxSignKind.CHECKOUT, notices.single().kind)
        assertEquals(CxSignNotice.PROMPT, notices.single().notice)
        assertTrue(notices.single().message.contains("签退"))
    }

    @Test(timeout = 10_000)
    fun checkinSubmitsOnceAndNotifiesOnce() = runBlocking {
        val gateway = FakeGateway(listOf(active()))
        val ledger = FakeLedger()
        val runner = runner(gateway, ledger, clock = { 1_000_000 })

        val first = runner.poll()
        assertEquals(1, gateway.signCalls.size)
        assertEquals(CxSignKind.CHECKIN, gateway.signCalls.single().second)
        assertEquals(1, first.size)
        assertEquals(CxSignNotice.SUCCESS, first.single().notice)
        assertEquals(CxSignRecordStatus.SUCCESS, ledger.records.values.single().status)

        val second = runner.poll()
        assertTrue("成功后永久去重", second.isEmpty())
        assertEquals(1, gateway.signCalls.size)
        assertEquals(
            "自动签到成功写一条 SUCCESS 历史，时间取本轮 clock",
            listOf(CxSignHistoryResult.SUCCESS to 1_000_000L),
            ledger.history.map { it.second.result to it.second.atMs },
        )
        assertEquals("服务端附言带进历史", "签到成功", ledger.history.single().second.detail)
    }

    @Test(timeout = 10_000)
    fun checkoutUsesLocationSettings() = runBlocking {
        val gateway = FakeGateway(listOf(active(type = 74, status = 1, otherId = "4", name = "课堂签退")))
        val notices = runner(gateway, clock = { 0 }).poll()

        assertEquals(1, gateway.signCalls.size)
        assertEquals(CxSignKind.CHECKOUT, gateway.signCalls.single().second)
        assertEquals(settings.seedLat, gateway.lastSettings!!.seedLat, 1e-9)
        assertEquals("教学楼A", gateway.lastSettings!!.address)
        assertEquals(CxSignNotice.SUCCESS, notices.single().notice)
    }

    @Test(timeout = 10_000)
    fun endedActivitiesAreIgnored() = runBlocking {
        val gateway = FakeGateway(
            listOf(
                active(type = 2, status = 2),
                active(id = "a2", type = 74, status = 2, name = "课堂签退"),
            ),
        )
        val notices = runner(gateway, clock = { 0 }).poll()

        assertTrue(notices.isEmpty())
        assertEquals(0, gateway.signCalls.size)
    }

    @Test(timeout = 10_000)
    fun manualTypesNotifyOnceWithoutSubmitting() = runBlocking {
        val gateway = FakeGateway(listOf(active(otherId = "2", name = "二维码签到")))
        val ledger = FakeLedger()
        val runner = runner(gateway, ledger, clock = { 0 })

        val first = runner.poll()
        assertEquals("二维码不提交", 0, gateway.signCalls.size)
        assertEquals(CxSignNotice.MANUAL, first.single().notice)
        assertTrue(first.single().message.contains("二维码"))

        val second = runner.poll()
        assertTrue("手动提醒只发一次", second.isEmpty())
    }

    @Test(timeout = 10_000)
    fun gestureCheckinIsManualNeverSubmitsAndNeverRetries() = runBlocking {
        var now = 0L
        val gateway = FakeGateway(listOf(active(otherId = "3", name = "手势签到")))
        val ledger = FakeLedger()
        val runner = runner(gateway, ledger, clock = { now })

        val first = runner.poll()
        assertEquals("手势签到永远不自动提交", 0, gateway.signCalls.size)
        assertEquals(CxSignNotice.MANUAL, first.single().notice)
        assertTrue(first.single().message.contains("手势"))
        assertTrue("不再提数字密码", !first.single().message.contains("密码"))
        assertEquals("c1", first.single().courseId)
        assertEquals("k1", first.single().classId)

        val record = ledger.records.values.single()
        assertEquals(CxSignRecordStatus.MANUAL, record.status)
        assertEquals("手动提醒不算一次尝试", 0, record.attempts)

        now += CX_SIGN_RETRY_GAP_MS
        assertTrue("同一活动不重复提醒", runner.poll().isEmpty())
        assertEquals("记录仍是手动，没有累加次数", 0, ledger.records.values.single().attempts)
        assertEquals(0, gateway.signCalls.size)
        assertEquals("手势签到只记一条需手动", 1, ledger.history.size)
        assertEquals(CxSignHistoryResult.MANUAL, ledger.history.single().second.result)
        assertTrue("手动原因写进历史附言", ledger.history.single().second.detail.isNotBlank())
    }

    @Test(timeout = 10_000)
    fun manualGestureSuccessFromScreenStaysSuccess() = runBlocking {
        val gateway = FakeGateway(listOf(active(otherId = "3", name = "手势签到")))
        val ledger = FakeLedger()
        val key = signRecordKey("acct-a", "a1", CxSignKind.CHECKIN)
        // 用户在九宫格页面手动提交成功后落的台账。
        ledger.records[key] = CxSignRecord(CxSignRecordStatus.SUCCESS, 1, 0)

        assertTrue("已手动签到，不再提醒", runner(gateway, ledger, clock = { 60_000 }).poll().isEmpty())
        assertEquals(0, gateway.signCalls.size)
        assertEquals(
            "SUCCESS 不能被下一轮改写成 MANUAL",
            CxSignRecordStatus.SUCCESS,
            ledger.records[key]!!.status,
        )
    }

    @Test(timeout = 10_000)
    fun failuresRetryAfterGapAndGiveUpAtFive() = runBlocking {
        var now = 1_000_000L
        val gateway = FakeGateway(listOf(active())).apply {
            result = CxSignResult(false, "不在范围")
        }
        val ledger = FakeLedger()
        val runner = runner(gateway, ledger, clock = { now })

        runner.poll()
        assertEquals(1, gateway.signCalls.size)
        assertEquals(CxSignRecordStatus.FAILED, ledger.records.values.single().status)

        now += 30_000
        assertTrue("60s 间隔内不重试", runner.poll().isEmpty())
        assertEquals(1, gateway.signCalls.size)

        var notices = emptyList<CxSignNoticeItem>()
        while (gateway.signCalls.size < CX_SIGN_MAX_ATTEMPTS) {
            now += CX_SIGN_RETRY_GAP_MS
            notices = runner.poll()
        }
        assertEquals(CX_SIGN_MAX_ATTEMPTS, gateway.signCalls.size)
        assertEquals(1, notices.size)
        assertEquals(CxSignNotice.GAVE_UP, notices.single().notice)
        assertEquals(CxSignRecordStatus.GAVE_UP, ledger.records.values.single().status)

        now += CX_SIGN_RETRY_GAP_MS
        assertTrue("放弃后不再尝试", runner.poll().isEmpty())
        assertEquals(CX_SIGN_MAX_ATTEMPTS, gateway.signCalls.size)
    }

    @Test(timeout = 10_000)
    fun networkErrorCountsAsThisAttemptOnly() = runBlocking {
        val gateway = FakeGateway(listOf(active())).apply {
            error = CxException("网络请求失败", CX_CODE_NETWORK)
        }
        val ledger = FakeLedger()
        val notices = runner(gateway, ledger, clock = { 0 }).poll()

        assertTrue("一次网络失败不通知", notices.isEmpty())
        assertEquals(CxSignRecordStatus.FAILED, ledger.records.values.single().status)
    }

    @Test(timeout = 10_000)
    fun authAndRiskErrorsAreRethrown() = runBlocking {
        for (code in listOf(CX_CODE_AUTH, CX_CODE_RISK)) {
            val gateway = FakeGateway(listOf(active())).apply {
                error = CxException("失败", code)
            }
            val thrown = try {
                runner(gateway, clock = { 0 }).poll()
                null
            } catch (e: CxException) {
                e
            }
            assertEquals("$code 必须上抛", code, thrown?.code)
        }
    }

    @Test
    fun pollIntervalIsAdaptive() {
        assertEquals("有开放活动才用高频档", 90_000L, cxSignPollIntervalMs(true))
        assertEquals("空闲档 5 分钟", 5 * 60_000L, cxSignPollIntervalMs(false))
        assertEquals(90_000L, CX_SIGN_POLL_ACTIVE_MS)
        assertEquals(5 * 60_000L, CX_SIGN_POLL_IDLE_MS)
    }

    @Test(timeout = 10_000)
    fun pollScansAtMostFourCoursesAndRevisitsOpenOnes() = runBlocking {
        val courses = (1..6).map { CxCourse("c$it", "k$it", "p1", "课程$it", "", "", "") }
        val gateway = FakeGateway(
            courseList = courses,
            activesByCourse = mapOf("c2" to listOf(active(id = "a-c2"))),
        )
        val rotation = CxSignCourseRotation(maxPerRound = 4)

        runner(gateway, rotation = rotation, clock = { 0 }).poll()
        assertEquals("单轮最多 4 门课", 4, gateway.activeCalls)
        assertEquals(listOf("c1", "c2", "c3", "c4"), gateway.activeCourseIds)
        assertEquals(setOf("c2"), rotation.openCourseIds("acct-a"))

        // 游标已前进到 c5；c2 不在窗口里，但上一轮有开放活动，本轮必须优先扫。
        runner(gateway, rotation = rotation, clock = { 0 }).poll()
        assertEquals(8, gateway.activeCalls)
        assertEquals("c2", gateway.activeCourseIds[4])
        assertTrue("c2" in gateway.activeCourseIds.drop(4))
    }

    @Test(timeout = 10_000)
    fun runnerReportsOpenActivityForCadence() = runBlocking {
        val open = runner(FakeGateway(listOf(active())), clock = { 0 })
        open.poll()
        assertTrue("有 status=1 的签到，下一轮走 60s", open.lastPollHadOpenActivity)
        assertEquals(CX_SIGN_POLL_ACTIVE_MS, cxSignPollIntervalMs(open.lastPollHadOpenActivity))

        val closed = runner(FakeGateway(listOf(active(status = 2))), clock = { 0 })
        closed.poll()
        assertFalse("活动已结束，下一轮退到 4 分钟", closed.lastPollHadOpenActivity)
        assertEquals(CX_SIGN_POLL_IDLE_MS, cxSignPollIntervalMs(closed.lastPollHadOpenActivity))
    }

    @Test(timeout = 10_000)
    fun monitorOffAndRestrictionFallBackToIdleCadence() = runBlocking {
        val off = runner(
            FakeGateway(listOf(active())),
            settings = { CxSignSettings(monitorEnabled = false) },
            clock = { 0 },
        )
        off.poll()
        assertFalse("开关关闭没扫到活动", off.lastPollHadOpenActivity)

        val restricted = FakeGateway(listOf(active())).apply {
            activesError = CxSignRestrictedException(
                CxSignRestriction(2_000_000L, "您已被限制访问"),
            )
        }
        val r = runner(restricted, clock = { 1_000_000 })
        r.poll()
        assertFalse("受限轮不发请求，也不保留高频档", r.lastPollHadOpenActivity)
        assertEquals(CX_SIGN_POLL_IDLE_MS, cxSignPollIntervalMs(r.lastPollHadOpenActivity))
    }

    @Test(timeout = 10_000)
    fun restrictionAbortsPollAndSkipsUntilExpiry() = runBlocking {
        var clock = 1_000_000L
        val gateway = FakeGateway(listOf(active())).apply {
            activesError = CxSignRestrictedException(
                CxSignRestriction(2_000_000L, "您已被限制访问"),
            )
        }
        val ledger = FakeLedger()
        val runner = runner(gateway, ledger, clock = { clock })

        assertTrue("命中受限当轮不发通知", runner.poll().isEmpty())
        assertEquals("受限后不再请求后续课程", 1, gateway.activeCalls)
        assertEquals("解除时间落台账", 2_000_000L, ledger.restriction!!.untilMs)

        clock += 30_000
        assertTrue(runner.poll().isEmpty())
        assertEquals("退避期内连课程列表都不拉", 1, gateway.courseCalls)

        clock = 2_000_000L
        gateway.activesError = null
        runner.poll()
        assertEquals("解除时间到后恢复请求", 2, gateway.courseCalls)
    }

    @Test(timeout = 10_000)
    fun staleRunningRetryCountsFiveAttemptsTotal() = runBlocking {
        val now = 1_000_000L
        val gateway = FakeGateway(listOf(active())).apply {
            result = CxSignResult(false, "不在范围")
        }
        val ledger = FakeLedger()
        val key = signRecordKey("acct-a", "a1", CxSignKind.CHECKIN)
        ledger.records[key] = CxSignRecord(
            status = CxSignRecordStatus.RUNNING,
            attempts = 4,
            atMs = now - CX_SIGN_RUNNING_TIMEOUT_MS,
        )

        val notices = runner(gateway, ledger, clock = { now }).poll()

        assertEquals("超时的 RUNNING 允许再试一次", 1, gateway.signCalls.size)
        assertEquals("这一次就是第 5 次", 5, ledger.records[key]!!.attempts)
        assertEquals(CxSignRecordStatus.GAVE_UP, ledger.records[key]!!.status)
        assertEquals(CxSignNotice.GAVE_UP, notices.single().notice)
    }

    @Test(timeout = 10_000)
    fun staleRunningIsRecoveredBeforeFirstPollOnly() = runBlocking {
        val gateway = FakeGateway(listOf(active()))
        val ledger = FakeLedger()
        val runner = runner(gateway, ledger, clock = { 0 })

        runner.poll()
        runner.poll()

        assertEquals("只清理一次", 1, ledger.recoverCalls)
    }

    @Test(timeout = 10_000)
    fun ledgerIsPerAccount() = runBlocking {
        val gateway = FakeGateway(listOf(active()))
        val ledger = FakeLedger()

        runner(gateway, ledger, account = "acct-a", clock = { 0 }).poll()
        assertEquals(1, gateway.signCalls.size)

        runner(gateway, ledger, account = "acct-b", clock = { 0 }).poll()
        assertEquals("换账号必须重新判一次", 2, gateway.signCalls.size)
    }
}