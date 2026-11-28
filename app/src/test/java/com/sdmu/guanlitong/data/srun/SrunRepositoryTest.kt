package com.sdmu.guanlitong.data.srun

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录 / 查询 / 注销的状态流转，对照 Go 版 login 模式的节奏。
 *
 * 门户、等待、探测、账号保管全部注入：不碰网络，也不碰 Android。
 * 账号密码都是虚构夹具。
 */
class SrunRepositoryTest {

    @Test
    fun firstAttemptOnlineSavesAccountAndPassword() = runBlocking {
        val h = Harness()
        h.next(logins = listOf(ACCEPTED), online = listOf(ONLINE))
        val progress = mutableListOf<Pair<Int, Int>>()

        val outcome = h.repository.login("demo-user", "demo-Passw0rd!", rememberPassword = true) { attempt, total ->
            progress += attempt to total
        }

        assertTrue(outcome.accepted)
        assertTrue(outcome.online)
        assertEquals(1, outcome.attempts)
        assertEquals(ACCEPTED.summary, outcome.lastSummary)
        assertEquals("login_ok", outcome.lastMessage)
        assertNull(outcome.lastError)
        // Go：登录后等 1 秒再问门户。
        assertEquals(listOf(1_000L), h.delays)
        assertEquals(listOf(1 to 4), progress)
        assertEquals(listOf(Triple("demo-user", "demo-Passw0rd!", true)), h.accounts.saves)
        assertEquals("demo-Passw0rd!", h.accounts.password())
    }

    @Test
    fun rememberPasswordOffKeepsTheUsernameOnly() = runBlocking {
        val h = Harness()
        h.next(logins = listOf(ACCEPTED), online = listOf(ONLINE))

        h.repository.login("demo-user", "demo-Passw0rd!", rememberPassword = false)

        assertEquals(listOf(Triple("demo-user", "demo-Passw0rd!", false)), h.accounts.saves)
        assertEquals("demo-user", h.accounts.username)
        assertNull(h.accounts.password())
        assertEquals(1, h.accounts.forgotten)
    }

    @Test
    fun rejectedLoginIsRetriedFourTimesWithGoDelays() = runBlocking {
        val h = Harness()
        h.next(logins = List(4) { REJECTED }, online = listOf(NOT_ONLINE))

        val outcome = h.repository.login("demo-user", "wrong-password", rememberPassword = true)

        assertEquals(4, outcome.attempts)
        assertFalse(outcome.accepted)
        assertFalse(outcome.online)
        // 2+attempt*2 秒：4 / 6 / 8，收尾再等 1 秒问一次门户。
        assertEquals(listOf(4_000L, 6_000L, 8_000L, 1_000L), h.delays)
        assertEquals(REJECTED.summary, outcome.lastSummary)
        assertEquals("账号或密码不对（门户返回：示例提示）", outcome.lastMessage)
        assertNull(outcome.lastError)
        // 没被门户接受过：账号密码都不记。
        assertTrue(h.accounts.saves.isEmpty())
    }

    @Test
    fun transportErrorIsRetriedAndThenForgotten() = runBlocking {
        val h = Harness()
        h.next(logins = listOf(IOException("reset"), ACCEPTED), online = listOf(ONLINE))

        val outcome = h.repository.login("demo-user", "demo-Passw0rd!", rememberPassword = true)

        assertEquals(2, outcome.attempts)
        assertTrue(outcome.online)
        assertEquals(listOf(4_000L, 1_000L), h.delays)
        assertNull(outcome.lastError)
        assertEquals(ACCEPTED.summary, outcome.lastSummary)
        assertEquals("login_ok", outcome.lastMessage)
        assertEquals(1, h.accounts.saves.size)
    }

    @Test
    fun allRequestsFailingStillAsksThePortalOnce() = runBlocking {
        val h = Harness()
        val error = SrunException("https：reset；http：reset", SrunErrorKind.NETWORK)
        // 最后那次门户查询说在线（比如本来就在线上）：Go 的判据只看它。
        h.next(logins = List(4) { error }, online = listOf(ONLINE))

        val outcome = h.repository.login("demo-user", "demo-Passw0rd!", rememberPassword = true)

        assertEquals(4, outcome.attempts)
        assertFalse(outcome.accepted)
        assertTrue(outcome.online)
        assertEquals(listOf(4_000L, 6_000L, 8_000L, 1_000L), h.delays)
        assertEquals("", outcome.lastSummary)
        assertEquals("", outcome.lastMessage)
        assertEquals(error.message, outcome.lastError)
        assertTrue(h.accounts.saves.isEmpty())
    }

    @Test
    fun acceptedButNotYetOnlineGetsOneMoreCheck() = runBlocking {
        val h = Harness()
        h.next(logins = listOf(REJECTED, ACCEPTED), online = listOf(NOT_ONLINE, ONLINE))

        val outcome = h.repository.login("demo-user", "demo-Passw0rd!", rememberPassword = true)

        assertTrue(outcome.accepted)
        assertTrue(outcome.online)
        assertEquals(listOf(4_000L, 1_000L, 3_000L), h.delays)
    }

    @Test
    fun acceptedButStillOfflineReportsSo() = runBlocking {
        val h = Harness()
        h.next(logins = listOf(ACCEPTED), online = listOf(NOT_ONLINE, NOT_ONLINE))

        val outcome = h.repository.login("demo-user", "demo-Passw0rd!", rememberPassword = true)

        assertTrue(outcome.accepted)
        assertFalse(outcome.online)
        assertEquals(listOf(1_000L, 3_000L), h.delays)
        assertEquals(NOT_ONLINE, outcome.status)
        // 门户接受过就说明账号密码是对的，照样记住。
        assertEquals(1, h.accounts.saves.size)
    }

    @Test
    fun portalPageFailureDoesNotStopLogin() = runBlocking {
        val h = Harness()
        val portal = h.next(logins = listOf(ACCEPTED), online = listOf(ONLINE))
        portal.pageError = IOException("reset")

        val outcome = h.repository.login("demo-user", "demo-Passw0rd!", rememberPassword = true)

        assertEquals(1, portal.initCalls)
        assertTrue(outcome.online)
    }

    @Test
    fun ipLearnedThisSessionIsReusedButNotPersisted() = runBlocking {
        val h = Harness()
        h.next(logins = listOf(ACCEPTED), online = listOf(ONLINE), ip = "10.0.0.9")

        h.repository.login("demo-user", "demo-Passw0rd!", rememberPassword = true)
        h.next(online = listOf(ONLINE), ip = "10.0.0.9")
        h.repository.status()

        assertEquals(listOf("", "10.0.0.9"), h.seenIps)
        assertEquals("", h.storage.config().ip)
    }

    @Test
    fun manuallyConfiguredIpWins() = runBlocking {
        val h = Harness(MemoryStorage(SrunConfig(ip = "10.9.9.9")))
        h.next(logins = listOf(ACCEPTED), online = listOf(ONLINE), ip = "10.0.0.9")

        h.repository.login("demo-user", "demo-Passw0rd!", rememberPassword = true)
        h.next(online = listOf(ONLINE), ip = "10.0.0.9")
        h.repository.status()

        assertEquals(listOf("10.9.9.9", "10.9.9.9"), h.seenIps)
    }

    @Test
    fun statusCarriesTheConnectivityProbe() = runBlocking {
        val h = Harness()
        h.next(online = listOf(ONLINE))
        h.probe = { true }
        assertEquals(SrunStatus(ONLINE, reachable = true), h.repository.status())

        h.next(online = listOf(NOT_ONLINE))
        h.probe = { false }
        assertEquals(SrunStatus(NOT_ONLINE, reachable = false), h.repository.status())

        // 探测自己出错只算没有结论。
        h.next(online = listOf(NOT_ONLINE))
        h.probe = { throw IOException("reset") }
        assertEquals(SrunStatus(NOT_ONLINE, reachable = null), h.repository.status())
    }

    @Test
    fun logoutIsJudgedByWhatThePortalSaysAfterwards() = runBlocking {
        val h = Harness()
        suspend fun logout(body: Any, after: SrunOnlineStatus): SrunLogoutResult {
            h.next(logout = listOf(body), online = listOf(after))
            return h.repository.logout("demo-user")
        }

        val done = logout("""cb({"res":"ok"});""", NOT_ONLINE)
        assertEquals("已注销", done.message)
        assertTrue(done.ok)
        assertEquals("门户受理了注销，但还显示这台设备在线", logout("""cb({"res":"ok"});""", ONLINE).message)
        assertEquals("门户没有受理注销，这台设备仍显示在线", logout("""cb({"res":"fail"});""", ONLINE).message)
        assertEquals("门户没有受理注销，这台设备仍显示在线", logout("<html></html>", ONLINE).message)
        val blind = logout("""cb({"res":"ok"});""", UNREACHABLE)
        assertEquals("注销请求已发出，但查不到现在的在线状态（查询失败：reset）", blind.message)
        assertFalse(blind.ok)
        val unsent = logout(IOException("reset"), UNREACHABLE)
        assertEquals("注销请求没发出去（reset）", unsent.message)
        assertFalse(unsent.ok)

        // 注销不清密码：下回登录不用重输。
        h.accounts.save("demo-user", "demo-Passw0rd!", true)
        logout("""cb({"res":"ok"});""", NOT_ONLINE)
        assertEquals("demo-Passw0rd!", h.accounts.password())
    }

    @Test
    fun cancellingBetweenAttemptsStopsTheRetryLoop() = runBlocking {
        val h = Harness()
        val portal = h.next(logins = List(4) { REJECTED }, online = listOf(ONLINE))
        val job = launch {
            h.repositoryWith { awaitCancellation() }
                .login("demo-user", "wrong-password", rememberPassword = true)
        }
        // 让第一轮跑完、卡在两次尝试之间的等待里。
        while (portal.loginCalls < 1) yield()

        job.cancelAndJoin()

        assertEquals(1, portal.loginCalls)
        assertTrue(h.accounts.saves.isEmpty())
    }

    @Test
    fun cancellingWhileALoginIsInFlightKeepsItsResult() = runBlocking {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val portal = h.next(logins = listOf(ACCEPTED), online = listOf(ONLINE))
        portal.beforeLogin = {
            started.complete(Unit)
            gate.await()
        }
        val job = launch {
            h.repository.login("demo-user", "demo-Passw0rd!", rememberPassword = true)
        }
        started.await()

        job.cancel()
        gate.complete(Unit)
        job.join()

        // 请求已经发出去了：门户接受了，账号就得记下来。
        assertEquals(listOf(Triple("demo-user", "demo-Passw0rd!", true)), h.accounts.saves)
    }

    @Test
    fun savedCredentialsComeFromAccountStorage() {
        val h = Harness()
        assertEquals("", h.repository.savedUsername)
        assertNull(h.repository.savedPassword())

        h.accounts.save("demo-user", "demo-Passw0rd!", true)
        assertEquals("demo-user", h.repository.savedUsername)
        assertEquals("demo-Passw0rd!", h.repository.savedPassword())

        h.accounts.save("demo-user", "demo-Passw0rd!", false)
        assertNull(h.repository.savedPassword())

        h.repository.forgetPassword()
        assertEquals(2, h.accounts.forgotten)
    }

    private class MemoryStorage(var config: SrunConfig = SrunConfig()) : SrunStorage {
        override fun config(): SrunConfig = config
        override fun saveConfig(config: SrunConfig) {
            this.config = config
        }
    }

    private class MemoryAccounts : SrunAccountStorage {
        override var username: String = ""
        private var stored: String? = null
        val saves = mutableListOf<Triple<String, String, Boolean>>()
        var forgotten = 0

        override fun password(): String? = stored

        override fun save(username: String, password: String, rememberPassword: Boolean) {
            saves += Triple(username, password, rememberPassword)
            this.username = username
            stored = if (rememberPassword) password else null
            if (!rememberPassword) forgetPassword()
        }

        override fun forgetPassword() {
            forgotten++
            stored = null
        }
    }

    /** 剧本化的门户：每次调用弹出一条，条目是异常就抛。 */
    private class FakePortal(
        override var ip: String,
        private val logins: List<Any>,
        private val online: List<SrunOnlineStatus>,
        private val logout: List<Any>,
    ) : SrunPortalApi {
        var initCalls = 0
        var loginCalls = 0
        var pageError: Throwable? = null
        var beforeLogin: (suspend () -> Unit)? = null
        private var loginIndex = 0
        private var onlineIndex = 0
        private var logoutIndex = 0

        override suspend fun initIp() {
            initCalls++
            pageError?.let { throw it }
        }

        override suspend fun login(username: String, password: String): SrunLoginResult {
            loginCalls++
            beforeLogin?.invoke()
            return when (val next = logins[loginIndex++]) {
                is SrunLoginResult -> next
                is Throwable -> throw next
                else -> error("bad login script")
            }
        }

        override suspend fun checkOnline(): SrunOnlineStatus = online[onlineIndex++]

        override suspend fun logout(username: String): String = when (val next = logout[logoutIndex++]) {
            is String -> next
            is Throwable -> throw next
            else -> error("bad logout script")
        }
    }

    private class Harness(val storage: SrunStorage = MemoryStorage()) {
        val accounts = MemoryAccounts()
        val delays = mutableListOf<Long>()
        val seenIps = mutableListOf<String>()
        val script = ArrayDeque<FakePortal>()
        var probe: suspend () -> Boolean = { true }

        /** 默认仓库：等待只记时长，不真等。 */
        val repository: SrunRepository = repositoryWith { delays += it }

        fun repositoryWith(delayMs: suspend (Long) -> Unit) = SrunRepository(
            store = storage,
            accounts = accounts,
            delayMs = delayMs,
            portalFactory = { config ->
                seenIps += config.ip
                script.removeFirst()
            },
            probeCall = { probe() },
        )

        /** 排下一个门户：登录 / 查询 / 注销各自按调用顺序出队。 */
        fun next(
            logins: List<Any> = emptyList(),
            online: List<SrunOnlineStatus> = emptyList(),
            logout: List<Any> = emptyList(),
            ip: String = "",
        ): FakePortal = FakePortal(ip, logins, online, logout).also(script::addLast)
    }

    private companion object {
        val ACCEPTED = SrunLoginResult(true, sucMsg = "login_ok", summary = "res=ok error=ok error_msg=<nil> suc_msg=login_ok")
        val REJECTED = SrunLoginResult(
            false, error = "login_error", errorMsg = "示例提示",
            summary = "res=login_error error=login_error error_msg=示例提示 suc_msg=<nil>",
        )
        val ONLINE = SrunOnlineStatus(online = true, userName = "demo-user", onlineIp = "10.0.0.8")
        val NOT_ONLINE = SrunOnlineStatus(online = false, detail = "not_online_error")
        val UNREACHABLE = SrunOnlineStatus(online = false, detail = "查询失败：reset", failed = true)
    }
}
