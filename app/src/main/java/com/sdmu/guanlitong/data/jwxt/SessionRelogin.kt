package com.sdmu.guanlitong.data.jwxt

import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.ApiClient
import com.sdmu.guanlitong.data.CookieUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 掉线后的自动重登。
 *
 * 只在「用户没有主动退出、并且保存过密码」时生效：
 * 依次尝试 [MAX_ATTEMPTS] 次，间隔按 1s / 2s / 4s 指数退避。
 * 成功后会注入新 Cookie 并把账号标记为已登录；失败也绝不清除登录状态，
 * 只跳过一小段时间，等下次打开 App、进入页面或数据请求时继续重试。
 */
class SessionRelogin(private val accounts: AccountStore) {

    fun serviceIdOf(source: JwxtSource): String = when (source) {
        JwxtSource.Official -> "jwxt"
        JwxtSource.Grab -> "grab"
    }

    /**
     * 打开 App 或进入教务功能时的补登录：只有本地会话已经没有了、但还留着密码时才尝试。
     *
     * @return 是否真的重新建立了会话
     */
    suspend fun ensure(source: JwxtSource, officialUrl: String, grabUrl: String): Boolean =
        ensureService(serviceIdOf(source), officialUrl, grabUrl)

    /**
     * 指定账号的补登录，不依赖当前教务数据来源。
     * 官方教务和抢课助手各自独立保活，互不影响。
     */
    suspend fun ensureService(
        serviceId: String,
        officialUrl: String,
        grabUrl: String,
    ): Boolean {
        val source = if (serviceId == "grab") JwxtSource.Grab else JwxtSource.Official
        val account = accounts.accountOf(serviceId)
        if (!account.loggedIn || !account.hasPassword) return false
        if (source == JwxtSource.Grab && grabUrl.isBlank()) return false
        if (JwxtAccess(source, officialUrl, grabUrl).cookie().isNotBlank()) return false

        // 连续失败时不要每次回到前台都跑一遍验证码识别，等一会儿再试。
        val now = System.currentTimeMillis()
        val failedAt = lastFailures[serviceId] ?: 0L
        if (now - failedAt < RETRY_COOLDOWN_MS) return false

        val recovered = relogin(source, officialUrl, grabUrl) != null
        if (recovered) lastFailures.remove(serviceId) else lastFailures[serviceId] = now
        return recovered
    }

    suspend fun relogin(
        source: JwxtSource,
        officialUrl: String,
        grabUrl: String,
    ): String? = mutex.withLock {
        val serviceId = serviceIdOf(source)
        val account = accounts.accountOf(serviceId)
        if (!account.loggedIn || !account.hasPassword) return@withLock null
        val username = account.username
        val password = accounts.passwordOf(serviceId)
        if (username.isBlank() || password.isNullOrBlank()) return@withLock null

        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(BASE_DELAY_MS shl (attempt - 1))
            val cookie = runCatching {
                // 调用方可能直接来自 LaunchedEffect（主线程），网络与 OCR 必须切到 IO。
                withContext(Dispatchers.IO) {
                    loginOnce(source, officialUrl, grabUrl, username, password)
                }
            }.getOrNull()
            if (!cookie.isNullOrBlank()) {
                injectCookie(source, officialUrl, grabUrl, cookie)
                accounts.markLoggedIn(serviceId, username, password = password)
                return@withLock cookie
            }
        }
        null
    }

    private suspend fun loginOnce(
        source: JwxtSource,
        officialUrl: String,
        grabUrl: String,
        username: String,
        password: String,
    ): String? = when (source) {
        JwxtSource.Official -> {
            val client = JwxtLoginClient(officialUrl)
            client.openLoginPage()
            if (client.alreadyLoggedIn) {
                client.exportCookies().takeIf { it.isNotBlank() }
            } else {
                // 验证码走离线 OCR；识别不完整就让本轮失败，下一轮会换一张新图。
                val captcha = runCatching { CaptchaOcr.recognize(client.fetchCaptcha()) }
                    .getOrDefault("")
                if (captcha.length != 6) {
                    throw JwxtLoginException("验证码识别失败")
                }
                val result = client.login(username, password, captcha)
                if (result.success) result.cookies else throw JwxtLoginException(result.message)
            }
        }

        JwxtSource.Grab -> {
            if (grabUrl.isBlank()) throw JwxtException("尚未配置抢课助手地址")
            val result = ApiClient.login(grabUrl, username, password)
            if (result.success) result.cookie else throw JwxtException(result.message)
        }
    }

    private fun injectCookie(
        source: JwxtSource,
        officialUrl: String,
        grabUrl: String,
        cookie: String,
    ) {
        when (source) {
            JwxtSource.Official ->
                CookieUtils.replaceFor(JwxtOrigin.ORIGIN, cookie, "/jwglxt")

            JwxtSource.Grab ->
                CookieUtils.inject(ApiClient.normalizeBaseUrl(grabUrl), cookie)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val BASE_DELAY_MS = 1_000L
        private const val RETRY_COOLDOWN_MS = 5 * 60_000L

        /** 多个入口（前台保活、数据请求、页面进入）共用同一把锁，避免并发重登。 */
        private val mutex = Mutex()
        private val lastFailures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}