package com.sdmu.guanlitong.data.zhs

import android.content.Context
import com.sdmu.guanlitong.data.AccountStore

/**
 * 知到（智慧树）的账号与课程数据入口。
 *
 * 设计上跟 [com.sdmu.guanlitong.data.cx.CxRepository] 对齐：
 * - 构造参数留出登录 / 课程接口与存储的注入点，单测可以完全不碰网络；
 * - 登录成功只保存会话，密码仅在用户勾选「记住密码」时才交给 AccountStore 加密保管；
 * - 不做后台静默重登：会话过期就抛 [ZhsErrorKind.EXPIRED]，由界面提示重新登录。
 */
class ZhsRepository(
    private val sessions: ZhsSessionStorage,
    private val accounts: AccountStore? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val loginCall: suspend (account: String, password: String, validate: String) -> ZhsSession =
        { account, password, validate -> ZhsLoginApi.login(account, password, validate) },
    private val coursesCall: suspend (session: ZhsSession) -> ZhsCourseList = { ZhsCourseApi.fetch(it) },
    private val detailCall: suspend (session: ZhsSession, course: ZhsCourse) -> ZhsCourseDetail =
        { session, course -> ZhsLearnApi.fetch(session, course) },
    private val reportCall: suspend (session: ZhsSession, course: ZhsCourse) -> ZhsReport =
        { session, course -> ZhsReportApi.fetch(session, course) },
    private val questionsCall: suspend (session: ZhsSession, course: ZhsCourse, newest: Boolean) -> ZhsQaPage =
        { session, course, newest -> ZhsQaApi.questions(session, course, newest) },
    private val answersCall: suspend (session: ZhsSession, course: ZhsCourse, questionId: String) -> ZhsAnswerPage =
        { session, course, questionId -> ZhsQaApi.answers(session, course, questionId) },
    private val submitAnswerCall: suspend (session: ZhsSession, course: ZhsCourse, questionId: String, content: String) -> ZhsSession =
        { session, course, questionId, content ->
            ZhsQaApi.submitAnswer(session, course, questionId, content)
        },
    private val homeworkCall: suspend (ZhsSession) -> List<ZhsHomework> = { ZhsHomeworkApi.fetch(it) },
    private val brushCall: suspend (
        ZhsSession,
        ZhsCourse,
        List<ZhsChapter>,
        ZhsSpeedController,
        Int,
        (ZhsStudyProgress) -> Unit,
        (String) -> Unit,
        () -> Boolean,
    ) -> Pair<ZhsSession, ZhsStudyOutcome> =
        { session, course, chapters, speed, targetSec, onProgress, onLog, shouldStop ->
            val client = ZhsStudyClient(session)
            val outcome = ZhsStudyEngine(ZhsStudyGatewayImpl(client))
                .run(course, chapters, speed, targetSec, onProgress, onLog, shouldStop)
            session.copy(studyKey = client.studyKey) to outcome
        },
    private val brushItemCall: suspend (
        ZhsSession,
        ZhsCourse,
        ZhsFlatItem,
        ZhsSpeedController,
        (ZhsStudyProgress) -> Unit,
        (String) -> Unit,
    ) -> Pair<ZhsSession, ZhsStudyOutcome> =
        { session, course, item, speed, onProgress, onLog ->
            val client = ZhsStudyClient(session)
            val outcome = ZhsStudyEngine(ZhsStudyGatewayImpl(client))
                .runItem(course, item, speed, onProgress, onLog)
            session.copy(studyKey = client.studyKey) to outcome
        },
) {

    constructor(context: Context, accounts: AccountStore? = null) :
        this(ZhsSessionStore(context.applicationContext), accounts)

    val loggedIn: Boolean get() = sessions.loggedIn

    val account: String? get() = sessions.account

    val hasSavedPassword: Boolean get() = accounts?.accountOf(SERVICE_ID)?.hasPassword == true

    /** 登录：滑块 [validate] 由界面透传，解析在 [ZhsLoginApi] 里完成。 */
    suspend fun login(
        account: String,
        password: String,
        validate: String,
        rememberPassword: Boolean,
    ): ZhsSession {
        val session = loginCall(account, password, validate).let { result ->
            if (result.loggedAt > 0) result else result.copy(loggedAt = clock())
        }
        sessions.save(session)
        accounts?.let { store ->
            store.markLoggedIn(
                serviceId = SERVICE_ID,
                username = session.account,
                nickname = maskZhsAccount(session.account),
                password = password.takeIf { rememberPassword },
            )
            if (!rememberPassword) store.forgetPassword(SERVICE_ID)
        }
        return session
    }

    /**
     * 拉取共享课 + 翻转课。会话过期时抛 [ZhsErrorKind.EXPIRED]，
     * 已换到的 AES 密钥会顺手写回会话存储。
     */
    /** 「我的作业」提醒列表。 */
    suspend fun homework(): List<ZhsHomework> = homeworkCall(requireSession())

    suspend fun courses(): ZhsCourseList {
        val session = sessions.session
            ?.takeIf { it.hasTicket }
            ?: throw ZhsException("登录已过期，请重新登录", ZhsErrorKind.EXPIRED)
        val result = coursesCall(session)
        if (result.session != session) {
            sessions.save(result.session)
        }
        return result
    }

    /**
     * 拉取单门课程的章节树与学习进度。会话过期时抛 [ZhsErrorKind.EXPIRED]；
     * 不做后台静默重登，界面提示用户重新登录。
     */
    suspend fun courseDetail(course: ZhsCourse): ZhsCourseDetail =
        detailCall(requireSession(), course)

    private fun requireSession(): ZhsSession = sessions.session
        ?.takeIf { it.hasTicket }
        ?: throw ZhsException("登录已过期，请重新登录", ZhsErrorKind.EXPIRED)

    private fun persistIfChanged(updated: ZhsSession) {
        if (updated != sessions.session) sessions.save(updated)
    }

    /** 课程成绩分析。主接口失败即整页失败，附加项由数据层兜底。 */
    suspend fun report(course: ZhsCourse): ZhsReport {
        val session = requireSession()
        return reportCall(session, course)
    }

    /** 问答列表；可能换回新的问答密钥，顺手写回会话。 */
    suspend fun questions(course: ZhsCourse, newest: Boolean): ZhsQaPage {
        val session = requireSession()
        val page = questionsCall(session, course, newest)
        persistIfChanged(page.session)
        return page
    }

    /** 某问题的回答列表。 */
    suspend fun answers(course: ZhsCourse, questionId: String): ZhsAnswerPage {
        val session = requireSession()
        val page = answersCall(session, course, questionId)
        page.session?.let(::persistIfChanged)
        return page
    }

    /**
     * 批量刷课。默认刷完所有未看完的视频（[targetSec] = 0）；
     * 滑块验证时不抛异常，返回 `captcha = true` 由界面提示用户。
     */
    suspend fun brush(
        course: ZhsCourse,
        chapters: List<ZhsChapter>,
        speed: ZhsSpeedController,
        targetSec: Int = 0,
        onProgress: (ZhsStudyProgress) -> Unit = {},
        onLog: (String) -> Unit = {},
        /** 每个条目开始时询问是否停止；旧项目语义是「当前这节刷完即停」。 */
        shouldStop: () -> Boolean = { false },
    ): ZhsStudyOutcome {
        val session = requireSession()
        val (updated, outcome) =
            brushCall(session, course, chapters, speed, targetSec, onProgress, onLog, shouldStop)
        persistIfChanged(updated)
        return outcome
    }

    /** 单节刷课：章节页每一节的「开始」。 */
    suspend fun brushItem(
        course: ZhsCourse,
        item: ZhsFlatItem,
        speed: ZhsSpeedController,
        onProgress: (ZhsStudyProgress) -> Unit = {},
        onLog: (String) -> Unit = {},
    ): ZhsStudyOutcome {
        val session = requireSession()
        val (updated, outcome) = brushItemCall(session, course, item, speed, onProgress, onLog)
        persistIfChanged(updated)
        return outcome
    }

    /** 提交回答。 */
    suspend fun submitAnswer(course: ZhsCourse, questionId: String, content: String) {
        val session = requireSession()
        persistIfChanged(submitAnswerCall(session, course, questionId, content))
    }

    fun logout(clearPassword: Boolean = true) {
        sessions.clear()
        accounts?.signOut(SERVICE_ID, clearPassword = clearPassword)
    }

    fun forgetPassword() {
        accounts?.forgetPassword(SERVICE_ID)
    }

    companion object {
        const val SERVICE_ID = "zhs"
    }
}