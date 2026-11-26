package com.sdmu.guanlitong.data.cx

import android.content.Context
import com.sdmu.guanlitong.data.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 会话存储抽象：真实实现是 [CxCredStore]，单测用内存假实现。 */
interface CxSessionStore {
    val session: CxSession?
    val phone: String?
    fun save(session: CxSession)
    fun clear()
}

/** 「记住密码」存储抽象：真实实现走 AccountStore + Android Keystore。 */
interface CxPasswordVault {
    fun read(): String?
    fun write(password: String)
    fun clear()
}

/** 课程请求抽象，便于在单测里注入「先 401、重登后成功」的假请求。 */
fun interface CxCoursesSource {
    suspend fun courses(): List<CxCourse>
}

/**
 * 学习通数据入口。页面只拿这个类，不直接碰网络与凭据存储。
 *
 * 课程请求命中 401 / 302 / 登录页（[CxApi] 统一抛 401）时，如果本机存了
 * 「记住密码」，就在原请求之外自动重登并重试一次；重登本身按 1s / 2s / 4s
 * 退避最多 3 次，全部失败只报「登录已失效，请重新登录」，不清会话或密码。
 * 并发请求用 [reloginMutex] 单飞，避免同一时刻多次登录把会话顶掉。
 */
class CxRepository internal constructor(
    private val cred: CxSessionStore,
    private val vault: CxPasswordVault,
    private val loginApi: suspend (phone: String, password: String) -> CxSession,
    private val sourceFactory: (Map<String, String>) -> CxCoursesSource,
    private val attempts: Int = RELOGIN_ATTEMPTS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {

    constructor(
        context: Context,
        accountStore: AccountStore = AccountStore(context),
    ) : this(
        cred = CxCredStore(context),
        vault = AccountStoreVault(accountStore, "cx"),
        loginApi = CxLoginApi::login,
        sourceFactory = { cookies -> CxCoursesSource { CxApi(cookies).courses() } },
    )

    private val reloginMutex = Mutex()

    /** 会话被自动重登换掉的代数；等锁的请求靠它判断「别人已经登过了」。 */
    @Volatile
    private var refreshVersion = 0L

    val loggedIn: Boolean
        get() = cred.session?.let { it.phone.isNotBlank() && it.cookies.isNotEmpty() } == true

    val phone: String? get() = cred.phone

    /** 本机保存的密码；没勾选「记住密码」时为 null。 */
    val savedPassword: String? get() = vault.read()

    /** 登录成功后保存会话；只有勾选记住密码时才落盘密码，否则顺手清掉旧密码。 */
    suspend fun login(phone: String, password: String, rememberPassword: Boolean) {
        val session = loginApi(phone.trim(), password)
        cred.save(session)
        if (rememberPassword) vault.write(password) else vault.clear()
    }

    /** 取消勾选「记住密码」时立即清掉本机保存的密码。 */
    fun forgetPassword() = vault.clear()

    suspend fun courses(): List<CxCourse> {
        val current = cred.session ?: throw CxException(AUTH_LOST, 401)
        if (current.cookies.isEmpty()) throw CxException(AUTH_LOST, 401)

        val seenVersion = refreshVersion
        try {
            return sourceFactory(current.cookies).courses()
        } catch (e: CxException) {
            if (e.code != 401) throw e
        }

        val cookies = relogin(seenVersion) ?: throw CxException(AUTH_LOST, 401)
        try {
            return sourceFactory(cookies).courses()
        } catch (e: CxException) {
            if (e.code == 401) throw CxException(AUTH_LOST, 401)
            throw e
        }
    }

    private suspend fun relogin(seenVersion: Long): Map<String, String>? = reloginMutex.withLock {
        // 等锁期间别的请求已经换过会话，直接复用，不再登录。
        if (refreshVersion != seenVersion) {
            return@withLock cred.session?.cookies?.takeIf { it.isNotEmpty() }
        }
        val password = vault.read() ?: return@withLock null
        val phone = cred.phone?.takeIf { it.isNotBlank() } ?: return@withLock null

        repeat(attempts) { attempt ->
            if (attempt > 0) pause(BASE_DELAY_MS shl (attempt - 1))
            val renewed = runCatching { loginApi(phone, password) }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }
            if (renewed != null && renewed.cookies.isNotEmpty()) {
                cred.save(renewed)
                refreshVersion++
                return@withLock renewed.cookies
            }
        }
        null
    }

    /** 只清会话；账号页的登录状态与密码由 AccountStore 负责。 */
    fun clearSession() = cred.clear()

    /** 用户主动退出：会话与本机密码一起清。 */
    fun signOut() {
        cred.clear()
        vault.clear()
    }

    companion object {
        const val RELOGIN_ATTEMPTS = 3
        const val AUTH_LOST = "登录已失效，请重新登录"
        private const val BASE_DELAY_MS = 1_000L
    }
}

/** AccountStore 的密码槽位适配：Secrets 加密保存，不落明文。 */
internal class AccountStoreVault(
    private val store: AccountStore,
    private val serviceId: String,
) : CxPasswordVault {

    override fun read(): String? = store.passwordOf(serviceId)

    override fun write(password: String) = store.rememberPassword(serviceId, password)

    override fun clear() = store.forgetPassword(serviceId)
}