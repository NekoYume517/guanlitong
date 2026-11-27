package com.sdmu.guanlitong.widget

import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class DesktopPrewarmTest {

    private val now = 2_000_000_000L
    private val ttl = DesktopWidgetRefreshPolicy.DATA_TTL_MS

    // ── TTL / 登录 / 冷却 ───────────────────────────────

    @Test
    fun `缓存未过期则不预热`() {
        assertEquals(
            PrewarmDecision.Fresh,
            DesktopPrewarmPolicy.decide(now, force = false, loggedIn = true, cachedAt = now - ttl / 2, lastFailureAt = 0),
        )
    }

    @Test
    fun `缓存过期且已登录才预热`() {
        assertEquals(
            PrewarmDecision.Fetch,
            DesktopPrewarmPolicy.decide(now, force = false, loggedIn = true, cachedAt = now - ttl - 1, lastFailureAt = 0),
        )
    }

    @Test
    fun `未登录时即使强制刷新也跳过`() {
        assertEquals(
            PrewarmDecision.NotLoggedIn,
            DesktopPrewarmPolicy.decide(now, force = true, loggedIn = false, cachedAt = null, lastFailureAt = 0),
        )
    }

    @Test
    fun `失败冷却期内不重复拉取`() {
        assertEquals(
            PrewarmDecision.Cooldown,
            DesktopPrewarmPolicy.decide(
                now, force = false, loggedIn = true,
                cachedAt = now - ttl - 1,
                lastFailureAt = now - 60_000L,
            ),
        )
    }

    @Test
    fun `冷启动无缓存允许立即拉一次`() {
        assertEquals(
            PrewarmDecision.Fetch,
            DesktopPrewarmPolicy.decide(now, force = false, loggedIn = true, cachedAt = null, lastFailureAt = 0),
        )
    }

    @Test
    fun `成绩预热跟随当前教务来源的账号`() {
        val grabOnly = mapOf("grab" to Account("grab", loggedIn = true))
        assertFalse(DesktopPrewarmPolicy.jwxtLoggedIn(JwxtSource.Official, grabOnly))
        assertTrue(DesktopPrewarmPolicy.jwxtLoggedIn(JwxtSource.Grab, grabOnly))
    }

    @Test
    fun `第二课堂只看自己的登录态`() {
        assertFalse(DesktopPrewarmPolicy.puLoggedIn(mapOf("jwxt" to Account("jwxt", loggedIn = true))))
        assertTrue(DesktopPrewarmPolicy.puLoggedIn(mapOf("pu" to Account("pu", loggedIn = true))))
    }

    // ── 预热执行：成功落盘 / 失败保留旧值 ─────────────────

    @Test
    fun `成功预热返回新的更新时间`() = runBlocking {
        var savedAt: Long? = null
        val outcome = runPrewarm(
            now = now,
            force = false,
            loggedIn = true,
            cachedAt = null,
            lastFailureAt = 0,
            fetch = { "grades" },
            onSuccess = { savedAt = now; now },
            onFailure = { _, _ -> PrewarmOutcome.Failed("unexpected") },
        )
        assertEquals(PrewarmOutcome.Updated(now), outcome)
        assertEquals(now, savedAt)
    }

    @Test
    fun `失败时保留旧缓存并记录失败`() = runBlocking {
        var cachedAt: Long? = 1234L
        var failureAt = 0L
        val outcome = runPrewarm(
            now = now,
            force = false,
            loggedIn = true,
            cachedAt = now - ttl - 1,
            lastFailureAt = 0,
            fetch = { error("boom") },
            onSuccess = { cachedAt = it; it },
            onFailure = { _, at ->
                failureAt = at
                PrewarmOutcome.Failed("boom")
            },
        )
        assertEquals(1234L, cachedAt)
        assertEquals(now, failureAt)
        assertTrue(outcome is PrewarmOutcome.Failed)
    }

    @Test
    fun `未登录不会触发拉取`() = runBlocking {
        var fetched = false
        val outcome = runPrewarm(
            now = now,
            force = true,
            loggedIn = false,
            cachedAt = null,
            lastFailureAt = 0,
            fetch = { fetched = true; 1 },
            onSuccess = { 1L },
            onFailure = { _, _ -> PrewarmOutcome.Failed("unexpected") },
        )
        assertEquals(PrewarmOutcome.NotLoggedIn, outcome)
        assertFalse(fetched)
    }

    // ── 单飞 ────────────────────────────────────────────

    @Test
    fun `多个实例共享同一次刷新`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val flight = SingleFlight<String>()
        val gate = CompletableDeferred<Unit>()
        var runs = 0

        val first = flight.run(scope) { runs += 1; gate.await(); "shared" }
        val second = flight.run(scope) { runs += 1; gate.await(); "shared" }
        assertSame(first, second)

        gate.complete(Unit)
        assertEquals("shared", first.await())
        assertEquals(1, runs)

        yield()
        val next = flight.run(scope) { runs += 1; "next" }
        assertEquals("next", next.await())
        assertEquals(2, runs)
    }

    // ── 上次更新时间 ────────────────────────────────────

    @Test
    fun `今天的缓存只显示时分`() {
        val nowMs = at(2026, 11, 27, 18, 0)
        assertEquals("上次更新 14:05", formatDesktopUpdatedLabel(at(2026, 11, 27, 14, 5), nowMs))
    }

    @Test
    fun `非今天的缓存带上月日`() {
        val nowMs = at(2026, 11, 27, 18, 0)
        assertEquals("上次更新 11月26日 23:59", formatDesktopUpdatedLabel(at(2026, 11, 26, 23, 59), nowMs))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, CampusZone)
            .toInstant()
            .toEpochMilli()
}