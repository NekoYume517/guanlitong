package com.sdmu.guanlitong.data.cx

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 学习通「记住密码」与登录过期自动重登的单测。
 *
 * 全部依赖注入的假会话存储、假密码槽、假登录与假课程请求，不碰网络与 Android。
 */
class CxRepositoryTest {

    @Test
    fun rememberedPasswordIsSavedReadAndCleared() = runBlocking {
        val store = FakeSessionStore(null)
        val vault = FakeVault()
        val repository = repository(store, vault, login = { _, _ -> session() })

        repository.login("13800000000", "secret", rememberPassword = true)
        assertEquals("secret", repository.savedPassword)
        assertEquals("secret", vault.value)

        // 不勾选记住密码：登录成功即丢弃密码。
        repository.login("13800000000", "other", rememberPassword = false)
        assertNull(repository.savedPassword)
        assertNull(vault.value)

        // 取消勾选：立刻清掉本机密码。
        repository.login("13800000000", "secret", rememberPassword = true)
        repository.forgetPassword()
        assertNull(repository.savedPassword)

        // 退出登录：会话与密码一起清。
        repository.login("13800000000", "secret", rememberPassword = true)
        repository.signOut()
        assertNull(store.session)
        assertNull(vault.value)
    }

    @Test
    fun authLossReloginsOnceAndRetriesRequest() = runBlocking {
        val store = FakeSessionStore(session(uid = "old"))
        val vault = FakeVault("secret")
        val logins = AtomicInteger()
        val fetches = AtomicInteger()
        val repository = repository(
            store = store,
            vault = vault,
            login = { phone, password ->
                logins.incrementAndGet()
                assertEquals("13800000000", phone)
                assertEquals("secret", password)
                session(uid = "new")
            },
            sourceFactory = { cookies ->
                CxCoursesSource {
                    fetches.incrementAndGet()
                    if (cookies["UID"] == "old") throw CxException(CxRepository.AUTH_LOST, 401)
                    listOf(COURSE)
                }
            },
        )

        assertEquals(listOf(COURSE), repository.courses())
        assertEquals(1, logins.get())
        assertEquals(2, fetches.get())
        assertEquals("new", store.session?.cookies?.get("UID"))
    }

    @Test
    fun reloginFailureKeepsSessionAndPassword() {
        val original = session(uid = "old")
        val store = FakeSessionStore(original)
        val vault = FakeVault("secret")
        val logins = AtomicInteger()
        val repository = repository(
            store = store,
            vault = vault,
            login = { _, _ ->
                logins.incrementAndGet()
                throw IOException("network down")
            },
            sourceFactory = { _ ->
                CxCoursesSource { throw CxException(CxRepository.AUTH_LOST, 401) }
            },
        )

        val error = assertThrows(CxException::class.java) {
            runBlocking { repository.courses() }
        }

        assertEquals(401, error.code)
        assertEquals(CxRepository.AUTH_LOST, error.message)
        assertEquals(3, logins.get())
        assertEquals(original, store.session)
        assertEquals("secret", vault.value)
    }

    @Test
    fun authLossWithoutSavedPasswordDoesNotLogin() {
        val store = FakeSessionStore(session(uid = "old"))
        val vault = FakeVault(null)
        val logins = AtomicInteger()
        val repository = repository(
            store = store,
            vault = vault,
            login = { _, _ -> logins.incrementAndGet(); session(uid = "new") },
            sourceFactory = { _ -> CxCoursesSource { throw CxException(CxRepository.AUTH_LOST, 401) } },
        )

        val error = assertThrows(CxException::class.java) {
            runBlocking { repository.courses() }
        }

        assertEquals(CxRepository.AUTH_LOST, error.message)
        assertEquals(0, logins.get())
    }

    @Test
    fun concurrentAuthLossOnlyReloginsOnce() = runBlocking {
        val store = FakeSessionStore(session(uid = "old"))
        val vault = FakeVault("secret")
        val logins = AtomicInteger()
        val repository = repository(
            store = store,
            vault = vault,
            login = { _, _ ->
                delay(30)
                logins.incrementAndGet()
                session(uid = "new")
            },
            sourceFactory = { cookies ->
                CxCoursesSource {
                    if (cookies["UID"] == "old") {
                        delay(10)
                        throw CxException(CxRepository.AUTH_LOST, 401)
                    }
                    listOf(COURSE)
                }
            },
        )

        val results = listOf(
            async { repository.courses() },
            async { repository.courses() },
        ).awaitAll()

        assertEquals(2, results.size)
        assertEquals(listOf(COURSE), results[0])
        assertEquals(listOf(COURSE), results[1])
        assertEquals(1, logins.get())
    }

    @Test
    fun retriedRequestStillUnauthorizedReportsAuthLost() {
        val store = FakeSessionStore(session(uid = "old"))
        val vault = FakeVault("secret")
        val logins = AtomicInteger()
        val fetches = AtomicInteger()
        val repository = repository(
            store = store,
            vault = vault,
            login = { _, _ -> logins.incrementAndGet(); session(uid = "new") },
            sourceFactory = { _ ->
                CxCoursesSource {
                    fetches.incrementAndGet()
                    throw CxException(CxRepository.AUTH_LOST, 401)
                }
            },
        )

        val error = assertThrows(CxException::class.java) {
            runBlocking { repository.courses() }
        }

        assertEquals(CxRepository.AUTH_LOST, error.message)
        assertEquals(1, logins.get())
        assertEquals(2, fetches.get())
        // 重登换了会话但课程仍 401：会话不被清掉，交给用户手动处理。
        assertEquals("new", store.session?.cookies?.get("UID"))
    }

    @Test
    fun otherErrorsDoNotTriggerRelogin() {
        val store = FakeSessionStore(session(uid = "old"))
        val vault = FakeVault("secret")
        val logins = AtomicInteger()
        val repository = repository(
            store = store,
            vault = vault,
            login = { _, _ -> logins.incrementAndGet(); session(uid = "new") },
            sourceFactory = { _ -> CxCoursesSource { throw CxException("网络错误，请稍后重试", 502) } },
        )

        val error = assertThrows(CxException::class.java) {
            runBlocking { repository.courses() }
        }

        assertEquals(502, error.code)
        assertEquals(0, logins.get())
    }

    private fun repository(
        store: FakeSessionStore,
        vault: FakeVault,
        login: suspend (String, String) -> CxSession,
        sourceFactory: (Map<String, String>) -> CxCoursesSource = { CxCoursesSource { listOf(COURSE) } },
    ): CxRepository = CxRepository(
        cred = store,
        vault = vault,
        loginApi = login,
        sourceFactory = sourceFactory,
        pause = {},
    )

    private class FakeSessionStore(private var current: CxSession?) : CxSessionStore {
        override val session: CxSession? get() = current
        override val phone: String? get() = current?.phone?.takeIf { it.isNotBlank() }
        override fun save(session: CxSession) {
            current = session
        }

        override fun clear() {
            current = null
        }
    }

    private class FakeVault(var value: String? = null) : CxPasswordVault {
        override fun read(): String? = value
        override fun write(password: String) {
            value = password
        }

        override fun clear() {
            value = null
        }
    }

    private fun session(uid: String = "1"): CxSession =
        CxSession(phone = "13800000000", cookies = mapOf("UID" to uid))

    private companion object {
        val COURSE = CxCourse(
            courseId = "1",
            clazzId = "2",
            personId = "3",
            name = "高等数学",
            teacher = "王平",
            coverUrl = "",
            link = "",
        )
    }
}