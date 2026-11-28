package com.sdmu.guanlitong.data.srun

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动认证的门槛：不依赖 Android、系统网络或真实门户。 */
class SrunAutoAuthTest {

    @Test
    fun listenerRequiresExplicitSwitchAndCompleteCredentials() {
        var enabled = false
        var credentials: Pair<String, String>? = "demo-user" to "demo-Passw0rd!"
        val gate = SrunAutoListenerGate({ enabled }, { credentials != null })
        assertFalse(gate.active)

        enabled = true
        credentials = null
        assertFalse(gate.active)

        credentials = "demo-user" to "demo-Passw0rd!"
        assertTrue(gate.active)

        enabled = false
        assertFalse(gate.active)
    }

    @Test
    fun missingPasswordNeverChecksPortal() = runBlocking {
        val h = Harness(credentials = null)
        val (result, _) = h.auth.authenticate("42", SrunAutoState())
        assertNull(result)
        assertEquals(0, h.portal.statusCalls)
        assertEquals(0, h.portal.loginCalls)
    }

    @Test
    fun alreadyOnlineSkipsLogin() = runBlocking {
        val h = Harness(online = listOf(ONLINE))
        val (result, state) = h.auth.authenticate("42", SrunAutoState())
        assertTrue(result is SrunAutoResult.AlreadyOnline)
        assertEquals(1, h.portal.statusCalls)
        assertEquals(0, h.portal.loginCalls)
        assertEquals(1_700L, state.attemptedAt)
    }

    @Test
    fun unknownStatusDoesNotTreatFailureAsOffline() = runBlocking {
        val h = Harness(online = listOf(UNREACHABLE))
        val (result, _) = h.auth.authenticate("42", SrunAutoState())
        assertNull(result)
        assertEquals(0, h.portal.loginCalls)
    }

    @Test
    fun offlineUsesOnlySavedCredentials() = runBlocking {
        val h = Harness(online = listOf(OFFLINE, ONLINE))
        val (result, _) = h.auth.authenticate("42", SrunAutoState())
        assertTrue(result is SrunAutoResult.Success)
        assertEquals(listOf("demo-user" to "demo-Passw0rd!"), h.portal.logins)
        assertEquals(2, h.portal.statusCalls)
    }

    @Test
    fun successfulNetworkIsNotRetried() = runBlocking {
        val h = Harness()
        val (result, _) = h.auth.authenticate(
            "42",
            SrunAutoState(successfulNetworkKey = "42"),
        )
        assertNull(result)
        assertEquals(0, h.portal.statusCalls)
        assertEquals(0, h.portal.loginCalls)
    }

    @Test
    fun rejectionDoesNotRepeatWholeLoginRound() = runBlocking {
        val h = Harness(online = List(5) { OFFLINE }, rejected = true)
        val (result, _) = h.auth.authenticate("42", SrunAutoState())
        assertTrue(result is SrunAutoResult.Failure)
        assertTrue((result as SrunAutoResult.Failure).message.contains("账号或密码"))
        // 复用现有登录节奏：一次网络事件一轮，门户拒绝时最多四次尝试，不另起循环。
        assertEquals(4, h.portal.loginCalls)
        assertEquals(2, h.portal.statusCalls)
    }

    private class Harness(
        credentials: Pair<String, String>? = "demo-user" to "demo-Passw0rd!",
        online: List<SrunOnlineStatus> = emptyList(),
        rejected: Boolean = false,
    ) {
        val portal = FakePortal(online, rejected)
        private val accounts = MemoryAccounts()
        val repository = SrunRepository(
            store = MemoryStorage(SrunConfig(ip = "10.0.0.8")),
            accounts = accounts,
            delayMs = {},
            portalFactory = { portal },
        )
        val auth = SrunAutoAuth(
            repository = repository,
            savedCredentials = { credentials },
            clock = { 1_700L },
            settleDelay = {},
        )
    }

    private class MemoryStorage(private val value: SrunConfig) : SrunStorage {
        override fun config(): SrunConfig = value
        override fun saveConfig(config: SrunConfig) = Unit
    }

    private class MemoryAccounts : SrunAccountStorage {
        override val username = "demo-user"
        override fun password(): String = "demo-Passw0rd!"
        override fun save(username: String, password: String, rememberPassword: Boolean) = Unit
        override fun forgetPassword() = Unit
    }

    private class FakePortal(
        private val online: List<SrunOnlineStatus>,
        private val rejected: Boolean,
    ) : SrunPortalApi {
        override val ip = "10.0.0.8"
        var statusCalls = 0
        var loginCalls = 0
        val logins = mutableListOf<Pair<String, String>>()
        override suspend fun initIp() = Unit
        override suspend fun login(username: String, password: String): SrunLoginResult {
            loginCalls++
            logins += username to password
            return if (rejected) SrunLoginResult(false, error = "login_error") else ACCEPTED
        }
        override suspend fun checkOnline(): SrunOnlineStatus = online[statusCalls++]
        override suspend fun logout(username: String): String = error("自动认证绝不能注销")
    }

    companion object {
        val ONLINE = SrunOnlineStatus(online = true, userName = "demo-user")
        val OFFLINE = SrunOnlineStatus(online = false, detail = "not_online_error")
        val UNREACHABLE = SrunOnlineStatus(online = false, detail = "查询失败", failed = true)
        val ACCEPTED = SrunLoginResult(accepted = true)
    }
}
