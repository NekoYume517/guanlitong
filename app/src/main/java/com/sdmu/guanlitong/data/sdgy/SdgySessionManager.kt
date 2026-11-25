package com.sdmu.guanlitong.data.sdgy

import android.content.Context
import com.sdmu.guanlitong.data.AccountStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 一次会话检查的结果，只用于界面提示，不表示账号被退出。 */
enum class SdgySessionState {
    /** 用户本来就没有登录。 */
    NotLoggedIn,

    /** 会话在线，或刚刚自动恢复成功。 */
    Online,

    /** 会话暂时不可用，但登录状态和密码都保留着，稍后继续重试。 */
    WaitingRetry,
}

data class SdgySessionCheck(
    val state: SdgySessionState,
    val message: String? = null,
)

/**
 * 山管会话保活。
 *
 * 策略与另外两个账号一致：
 * - 只有用户主动退出才会清账号；
 * - 被顶号 / 会话过期时，用本地保存的密码自动重登；
 * - 重登失败（包括学校服务器半夜关机、断网）也绝不清除登录状态，
 *   只记录一条提示，等下次回前台或打开账号页时继续重试。
 */
object SdgySessionManager {

    private val mutex = Mutex()
    private var lastAttemptAt = 0L

    private const val ONLINE_INTERVAL_MS = 60_000L
    private const val RETRY_INTERVAL_MS = 5 * 60_000L
    private const val MAX_ATTEMPTS = 3
    private const val BASE_DELAY_MS = 1_000L

    suspend fun ensure(context: Context, force: Boolean = false): SdgySessionCheck =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val accounts = AccountStore(appContext)
            if (!accounts.accountOf("sdgy").loggedIn) {
                return@withContext SdgySessionCheck(SdgySessionState.NotLoggedIn)
            }

            val store = SdgySessionStore(appContext)
            val interval = if (store.lastError() != null) {
                RETRY_INTERVAL_MS
            } else {
                ONLINE_INTERVAL_MS
            }
            if (!force && System.currentTimeMillis() - lastAttemptAt < interval) {
                return@withContext currentState(store)
            }

            mutex.withLock {
                // 等锁期间可能已经有别的入口完成过一次检查。
                if (!force && System.currentTimeMillis() - lastAttemptAt < interval) {
                    return@withLock currentState(store)
                }
                lastAttemptAt = System.currentTimeMillis()
                checkLocked(accounts, store)
            }
        }

    /** 退出登录时调用：只清会话，不碰账号和密码。 */
    fun signOut(context: Context) {
        val appContext = context.applicationContext
        val store = SdgySessionStore(appContext)
        val saved = store.load()
        if (saved != null) {
            runCatching { SdgyClient(store.deviceId(), saved).logout() }
        }
        store.clear()
    }

    private suspend fun checkLocked(
        accounts: AccountStore,
        store: SdgySessionStore,
    ): SdgySessionCheck {
        val saved = store.load()
        if (saved != null && saved.hasSession) {
            val client = SdgyClient(store.deviceId(), saved)
            try {
                client.ping()
                client.session.savedAt = System.currentTimeMillis()
                store.save(client.session)
                store.markOk()
                return SdgySessionCheck(SdgySessionState.Online)
            } catch (_: SdgyException) {
                // 服务端明确拒绝：可能是被顶号或会话过期，继续尝试用保存的密码重登。
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = networkMessage(e)
                store.setError(message)
                return SdgySessionCheck(SdgySessionState.WaitingRetry, message)
            }
        }
        return relogin(accounts, store)
    }

    private suspend fun relogin(
        accounts: AccountStore,
        store: SdgySessionStore,
    ): SdgySessionCheck {
        val account = accounts.accountOf("sdgy")
        val password = accounts.passwordOf("sdgy")
        if (account.username.isBlank() || password.isNullOrBlank()) {
            val message = "没有保存账号密码，无法自动重新登录；登录状态已保留"
            store.setError(message)
            return SdgySessionCheck(SdgySessionState.WaitingRetry, message)
        }

        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(BASE_DELAY_MS shl (attempt - 1))
            try {
                val client = SdgyClient(store.deviceId())
                val session = client.login(account.username, password)
                session.savedAt = System.currentTimeMillis()
                store.save(session)
                store.markOk()
                return SdgySessionCheck(SdgySessionState.Online)
            } catch (e: SdgyPasswordResetException) {
                val message = "需要先在官方山管 App 重置密码；登录状态已保留"
                store.setError(message)
                return SdgySessionCheck(SdgySessionState.WaitingRetry, message)
            } catch (e: SdgyException) {
                if (e.code.isEmpty() || e.code.startsWith("5") || e.code == "0001") {
                    lastError = e
                } else {
                    val message = "自动登录失败，请重新登录；登录状态已保留"
                    store.setError(message)
                    return SdgySessionCheck(SdgySessionState.WaitingRetry, message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }

        val message = networkMessage(lastError)
        store.setError(message)
        return SdgySessionCheck(SdgySessionState.WaitingRetry, message)
    }

    private fun currentState(store: SdgySessionStore): SdgySessionCheck {
        val hasSession = store.load()?.hasSession == true
        if (hasSession && store.lastError() == null) {
            return SdgySessionCheck(SdgySessionState.Online)
        }
        return SdgySessionCheck(
            SdgySessionState.WaitingRetry,
            store.lastError() ?: "等待自动恢复登录",
        )
    }

    private fun networkMessage(error: Throwable?): String = when (error) {
        is UnknownHostException -> "无法解析山管服务器地址，登录状态已保留"
        is ConnectException -> "山管服务器暂时无法连接（夜间可能关机），登录状态已保留"
        is SocketTimeoutException -> "连接山管服务器超时，登录状态已保留"
        else -> "暂时连不上山管服务器（夜间可能关机），登录状态已保留"
    }
}