package com.sdmu.guanlitong.data.zhs

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录、课程刷新与退出的状态流转；依赖全部注入，不碰网络和 Android。
 */
class ZhsRepositoryTest {

    private class MemoryStorage(var stored: ZhsSession? = null) : ZhsSessionStorage {
        override val session: ZhsSession? get() = stored
        override fun save(session: ZhsSession) {
            stored = session
        }

        override fun clear() {
            stored = null
        }
    }

    private fun session(aesKey: String = "") = ZhsSession(
        account = "13800138000",
        uuid = "u-1",
        cookies = mapOf("CASTGC" to "TGT-1", "xlvt" to "abc"),
        aesKey = aesKey,
        loggedAt = 1_700_000_000_000L,
    )

    @Test
    fun loginSavesSessionAndPassesValidateUnchanged() = runBlocking {
        val storage = MemoryStorage()
        var seenValidate: String? = null
        val repository = ZhsRepository(
            sessions = storage,
            clock = { 42L },
            loginCall = { _, _, validate ->
                seenValidate = validate
                session()
            },
        )

        val result = repository.login("13800138000", "secret", "validate-token", rememberPassword = false)

        assertEquals("validate-token", seenValidate)
        assertEquals("13800138000", result.account)
        assertTrue(repository.loggedIn)
        assertEquals("13800138000", repository.account)
        assertEquals("TGT-1", storage.stored?.cookies?.get("CASTGC"))
    }

    @Test
    fun failedLoginKeepsStorageUntouched() = runBlocking {
        val storage = MemoryStorage(session())
        val repository = ZhsRepository(
            sessions = storage,
            loginCall = { _, _, _ -> throw ZhsException("密码错误", ZhsErrorKind.AUTH) },
        )

        val error = assertThrows(ZhsException::class.java) {
            runBlocking { repository.login("1", "2", "v", rememberPassword = false) }
        }
        assertEquals(ZhsErrorKind.AUTH, error.kind)
        assertEquals("TGT-1", storage.stored?.cookies?.get("CASTGC"))
    }

    @Test
    fun coursesWithoutSessionThrowsExpired() = runBlocking {
        val repository = ZhsRepository(
            sessions = MemoryStorage(),
            coursesCall = { throw IOException("should not be called") },
        )
        val error = assertThrows(ZhsException::class.java) { runBlocking { repository.courses() } }
        assertEquals(ZhsErrorKind.EXPIRED, error.kind)
        assertFalse(repository.loggedIn)
    }

    @Test
    fun coursesWithoutCastgcCountsAsLoggedOut() = runBlocking {
        val repository = ZhsRepository(
            sessions = MemoryStorage(session().copy(cookies = mapOf("xlvt" to "abc"))),
        )
        assertFalse(repository.loggedIn)
        assertThrows(ZhsException::class.java) { runBlocking { repository.courses() } }
        Unit
    }

    @Test
    fun coursesPersistRefreshedAesKey() = runBlocking {
        val storage = MemoryStorage(session())
        val repository = ZhsRepository(
            sessions = storage,
            coursesCall = { s ->
                ZhsCourseList(
                    shared = listOf(course(1, shared = true)),
                    flipped = listOf(course(2, shared = false)),
                    session = s.copy(aesKey = "new-key"),
                )
            },
        )

        val result = repository.courses()

        assertEquals("new-key", result.session.aesKey)
        assertEquals("new-key", storage.stored?.aesKey)
        assertEquals(1, result.shared.size)
        assertEquals(1, result.flipped.size)
        assertEquals("TGT-1", storage.stored?.cookies?.get("CASTGC"))
    }

    @Test
    fun coursesFailureDoesNotClearSession() = runBlocking {
        val storage = MemoryStorage(session())
        val repository = ZhsRepository(
            sessions = storage,
            coursesCall = { throw ZhsException("登录已过期，请重新登录", ZhsErrorKind.EXPIRED) },
        )

        assertThrows(ZhsException::class.java) { runBlocking { repository.courses() } }
        assertTrue(repository.loggedIn)
    }

    @Test
    fun courseDetailWithoutSessionThrowsExpired() = runBlocking {
        val repository = ZhsRepository(
            sessions = MemoryStorage(),
            detailCall = { _, _ -> throw IOException("should not be called") },
        )
        val error = assertThrows(ZhsException::class.java) {
            runBlocking { repository.courseDetail(course(1, shared = true)) }
        }
        assertEquals(ZhsErrorKind.EXPIRED, error.kind)
        Unit
    }

    @Test
    fun courseDetailPassesStoredSessionAndCourse() = runBlocking {
        val storage = MemoryStorage(session())
        var seenTicket: String? = null
        var seenCourse: ZhsCourse? = null
        val repository = ZhsRepository(
            sessions = storage,
            detailCall = { s, c ->
                seenTicket = s.cookies["CASTGC"]
                seenCourse = c
                ZhsCourseDetail(
                    chapters = emptyList(),
                    summary = ZhsSummary(0, 0, 0, 0),
                )
            },
        )

        val detail = repository.courseDetail(course(7, shared = false))

        assertEquals("TGT-1", seenTicket)
        assertEquals(7L, seenCourse?.courseId)
        assertTrue(detail.chapters.isEmpty())
        Unit
    }

    @Test
    fun reportRequiresSession() = runBlocking {
        val repository = ZhsRepository(
            sessions = MemoryStorage(),
            reportCall = { _, _ -> throw IOException("should not be called") },
        )
        val error = assertThrows(ZhsException::class.java) {
            runBlocking { repository.report(course(1, shared = true)) }
        }
        assertEquals(ZhsErrorKind.EXPIRED, error.kind)
        Unit
    }

    @Test
    fun questionsPersistsRefreshedQaKey() = runBlocking {
        val storage = MemoryStorage(session())
        val repository = ZhsRepository(
            sessions = storage,
            questionsCall = { s, _, newest ->
                ZhsQaPage(
                    questions = listOf(ZhsQuestion("q1", "问题", 0, "")),
                    session = s.copy(qaKey = "QA-${if (newest) "new" else "hot"}"),
                )
            },
        )

        val page = repository.questions(course(2, shared = true), newest = true)

        assertEquals("问题", page.questions.single().content)
        assertEquals("QA-new", storage.stored?.qaKey)
        Unit
    }

    @Test
    fun answersAndSubmitReachInjectedCalls() = runBlocking {
        var seenQuestion: String? = null
        var seenContent: String? = null
        val repository = ZhsRepository(
            sessions = MemoryStorage(session()),
            answersCall = { s, _, qid ->
                seenQuestion = qid
                ZhsAnswerPage("正文", emptyList(), s)
            },
            submitAnswerCall = { s, _, _, content ->
                seenContent = content
                s
            },
        )

        val page = repository.answers(course(3, shared = true), "q-9")
        repository.submitAnswer(course(3, shared = true), "q-9", "我的回答")

        assertEquals("q-9", seenQuestion)
        assertEquals("正文", page.questionContent)
        assertEquals("我的回答", seenContent)
        Unit
    }

    @Test
    fun logoutClearsEverything() {
        val storage = MemoryStorage(session())
        val repository = ZhsRepository(sessions = storage)

        repository.logout()

        assertNull(storage.session)
        assertFalse(repository.loggedIn)
        assertNull(repository.account)
    }

    @Test
    fun brushPersistsRefreshedStudyKey() = runBlocking {
        val storage = MemoryStorage(session())
        var seenSpeed: ZhsSpeedController? = null
        val repository = ZhsRepository(
            sessions = storage,
            brushCall = { _, _, _, speed, _, _, _, _ ->
                seenSpeed = speed
                session().copy(studyKey = "k-new") to
                    ZhsStudyOutcome(
                        brushedCount = 2,
                        addedSec = 120,
                        targetSec = 0,
                        captcha = false,
                        answered = 3,
                    )
            },
        )

        val speed = ZhsSpeedController(ZhsSpeedChoice.custom(4.0))
        val outcome = repository.brush(course(1, true), emptyList(), speed)

        assertEquals(2, outcome.brushedCount)
        assertEquals(120, outcome.addedSec)
        assertEquals(3, outcome.answered)
        assertEquals("k-new", storage.stored?.studyKey)
        assertEquals(4.0, seenSpeed?.choice?.rate)
    }

    private fun course(id: Long, shared: Boolean) = ZhsCourse(
        courseId = id,
        recruitId = id + 100,
        name = "课程 $id",
        teacher = "老师",
        school = "学校",
        progress = 50,
        shared = shared,
        smart = false,
    )
}