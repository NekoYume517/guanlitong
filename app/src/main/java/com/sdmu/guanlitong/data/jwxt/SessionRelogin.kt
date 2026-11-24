package com.sdmu.guanlitong.data.jwxt

import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.ApiClient
import com.sdmu.guanlitong.data.CookieUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 掉线后的自动重登。
 *
 * 只在「用户没有主动退出、并且保存过密码」时生效：
 * 依次尝试 [MAX_ATTEMPTS] 次，间隔按 1s / 2s / 4s 指数退避。
 * 成功后会注入新 Cookie 并把账号标记为已登录，失败则返回 null 由页面继续提示手动登录。
 */
class SessionRelogin(private val accounts: AccountStore) {

    private val mutex = Mutex()

    fun serviceIdOf(source: JwxtSource): String = when (source) {
        JwxtSource.Official -> "jwxt"
        JwxtSource.Grab -> "grab"
    }

    /**
     * 打开 App 或进入教务功能时的补登录：只有本地会话已经没有了、但还留着密码时才尝试。
     *
     * @return 是否真的重新建立了会话
     */
    suspend fun ensure(source: JwxtSource, officialUrl: String, grabUrl: String): Boolean {
        val serviceId = serviceIdOf(source)
        val account = accounts.accountOf(serviceId)
        if (!account.loggedIn || !account.hasPassword) return false
        if (JwxtAccess(source, officialUrl, grabUrl).cookie().isNotBlank()) return false
        return relogin(source, officialUrl, grabUrl) != null
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
                loginOnce(source, officialUrl, grabUrl, username, password)
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
    }
}