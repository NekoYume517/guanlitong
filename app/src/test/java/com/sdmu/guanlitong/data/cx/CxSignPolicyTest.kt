package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 去重与重试策略：成功永久去重、失败不冷却轮（30s 轮询下靠 60s 间隔）、最多 5 次、
 * 活动结束即停、通知每条活动一次。
 */
class CxSignPolicyTest {

    private fun failed(attempts: Int, atMs: Long = 0) =
        CxSignRecord(CxSignRecordStatus.FAILED, attempts, atMs)

    @Test
    fun successAndGiveUpAndManualSkipForever() {
        val now = 10_000_000L
        assertEquals(
            CxSignPlan.SKIP,
            planSign(CxSignRecord(CxSignRecordStatus.SUCCESS, 1, 0), now, activeEnded = false),
        )
        assertEquals(
            CxSignPlan.SKIP,
            planSign(CxSignRecord(CxSignRecordStatus.GAVE_UP, 5, 0), now, activeEnded = false),
        )
        assertEquals(
            CxSignPlan.SKIP,
            planSign(CxSignRecord(CxSignRecordStatus.MANUAL, 0, 0), now, activeEnded = false),
        )
    }

    @Test
    fun newActivityAttemptsImmediately() {
        assertEquals(CxSignPlan.ATTEMPT, planSign(null, 0, activeEnded = false))
    }

    @Test
    fun failedRetriesAfterGapNotAfterTenMinutes() {
        // 没有 10 分钟冷却：60 秒一到就能再试（30s 轮询下就是隔一轮）。
        assertEquals(CxSignPlan.WAIT, planSign(failed(1), 59_999, activeEnded = false))
        assertEquals(CxSignPlan.ATTEMPT, planSign(failed(1), 60_000, activeEnded = false))
        assertEquals(CxSignPlan.ATTEMPT, planSign(failed(1), 300_000, activeEnded = false))
    }

    @Test
    fun attemptsAreCappedAtFive() {
        assertEquals(CxSignPlan.ATTEMPT, planSign(failed(4), 60_000, activeEnded = false))
        assertEquals(
            "第 5 次之后不再尝试",
            CxSignPlan.SKIP,
            planSign(failed(5), 60_000, activeEnded = false),
        )
    }

    @Test
    fun runningWaits() {
        val running = CxSignRecord(CxSignRecordStatus.RUNNING, 1, 0)
        assertEquals(CxSignPlan.WAIT, planSign(running, 5_000, activeEnded = false))
    }

    @Test
    fun runningEscapesAfterTimeout() {
        val running = CxSignRecord(CxSignRecordStatus.RUNNING, 1, 0)
        assertEquals(
            CxSignPlan.WAIT,
            planSign(running, CX_SIGN_RUNNING_TIMEOUT_MS - 1, activeEnded = false),
        )
        assertEquals(
            "超过 180s 的 RUNNING 必须允许重试，否则协程死掉会永远卡住",
            CxSignPlan.ATTEMPT,
            planSign(running, CX_SIGN_RUNNING_TIMEOUT_MS, activeEnded = false),
        )
    }

    @Test
    fun staleRunningIsRecoveredToFailed() {
        val stale = CxSignRecord(CxSignRecordStatus.RUNNING, 2, 0)
        val recovered = recoverStaleRecord(stale, CX_SIGN_RUNNING_TIMEOUT_MS)
        assertEquals(CxSignRecordStatus.FAILED, recovered.status)
        assertEquals("次数保留，不重复计", 2, recovered.attempts)

        val fresh = CxSignRecord(CxSignRecordStatus.RUNNING, 2, 0)
        assertEquals(
            "未超时的 RUNNING 不动",
            fresh,
            recoverStaleRecord(fresh, CX_SIGN_RUNNING_TIMEOUT_MS - 1),
        )
        val success = CxSignRecord(CxSignRecordStatus.SUCCESS, 3, 0)
        assertEquals(success, recoverStaleRecord(success, Long.MAX_VALUE))
    }

    @Test
    fun endedActivityStopsEvenWhenRetryable() {
        assertEquals(CxSignPlan.SKIP, planSign(failed(1), 60_000, activeEnded = true))
    }

    @Test
    fun recordTransitionsCountAttemptsAndGiveUpAtFive() {
        var record: CxSignRecord? = null
        repeat(4) { index ->
            record = runningRecord(record, nowMs = index * 60_000L)
            assertEquals(index + 1, record!!.attempts)
            assertEquals(CxSignRecordStatus.RUNNING, record!!.status)
            record = attemptedRecord(record, nowMs = index * 60_000L, success = false)
            assertEquals(index + 1, record!!.attempts)
            assertEquals(CxSignRecordStatus.FAILED, record!!.status)
        }

        record = attemptedRecord(record, nowMs = 4 * 60_000L, success = false)
        assertEquals(CxSignRecordStatus.GAVE_UP, record!!.status)
        assertEquals(5, record!!.attempts)
    }

    @Test
    fun successWinsOverAttemptCount() {
        val record = attemptedRecord(
            CxSignRecord(CxSignRecordStatus.FAILED, 4, 0),
            nowMs = 60_000,
            success = true,
        )
        assertEquals(CxSignRecordStatus.SUCCESS, record.status)
        assertEquals(5, record.attempts)
        assertEquals(CxSignRecordStatus.SUCCESS, record.status)
    }

    @Test
    fun noticeOnlyOncePerActivity() {
        val success = CxSignRecord(CxSignRecordStatus.SUCCESS, 1, 0)
        assertEquals(CxSignNotice.SUCCESS, signNotice(null, success))
        assertEquals(
            "重复提交到同一条已成功的活动不再通知",
            CxSignNotice.NONE,
            signNotice(success, success),
        )

        val failed4 = CxSignRecord(CxSignRecordStatus.FAILED, 4, 0)
        val gaveUp = CxSignRecord(CxSignRecordStatus.GAVE_UP, 5, 0)
        assertEquals("第 4 次失败不通知", CxSignNotice.NONE, signNotice(failed4, failed4))
        assertEquals("最终失败通知一次", CxSignNotice.GAVE_UP, signNotice(failed4, gaveUp))
        assertEquals(CxSignNotice.NONE, signNotice(gaveUp, gaveUp))

        val manual = manualRecord(null, 0)
        assertEquals(CxSignNotice.MANUAL, signNotice(null, manual))
        assertEquals(CxSignNotice.NONE, signNotice(manual, manual))
    }

    @Test
    fun promptedRecordStillAllowsMakeUpSign() {
        val prompted = promptRecord(failed(2, atMs = 0), nowMs = 100)
        assertEquals(CxSignRecordStatus.PROMPTED, prompted.status)
        assertEquals("保留已有尝试次数", 2, prompted.attempts)

        assertEquals(
            "提醒过不等于放弃：打开自动签到后应补签",
            CxSignPlan.ATTEMPT,
            planSign(prompted, 100, activeEnded = false),
        )
        assertEquals("首次提醒发一条", CxSignNotice.PROMPT, signNotice(failed(2), prompted))
        assertEquals(
            "同一活动不重复提醒",
            CxSignNotice.NONE,
            signNotice(prompted, prompted),
        )
        assertEquals(
            "活动已结束则不补签",
            CxSignPlan.SKIP,
            planSign(prompted, 100, activeEnded = true),
        )
    }

    @Test
    fun recordKeyIncludesAccountAndKind() {
        val a = signRecordKey("acct-a", "active-1", CxSignKind.CHECKIN)
        val b = signRecordKey("acct-b", "active-1", CxSignKind.CHECKIN)
        val c = signRecordKey("acct-a", "active-1", CxSignKind.CHECKOUT)

        assertNotEquals("换账号不能互相误判已签", a, b)
        assertNotEquals("签到与签退各自一条", a, c)
        assertEquals("acct-a:active-1:checkin", a)
    }
}