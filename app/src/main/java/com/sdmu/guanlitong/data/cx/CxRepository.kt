package com.sdmu.guanlitong.data.cx

import android.content.Context

/**
 * 学习通数据入口。页面只拿这个类，不直接碰网络与凭据存储。
 *
 * 第一期只有两件事：登录、课程列表。
 */
class CxRepository(context: Context) {

    private val cred = CxCredStore(context.applicationContext)

    val loggedIn: Boolean get() = cred.loggedIn

    val phone: String? get() = cred.phone

    /** 登录成功后立即保存会话；密码只在 [CxLoginApi.login] 里用一次。 */
    suspend fun login(phone: String, password: String): CxSession {
        val session = CxLoginApi.login(phone, password)
        cred.save(session)
        return session
    }

    /**
     * 会话失效只抛 401，由页面提示重新登录；这里不清凭据，
     * 避免网络抖动或服务端临时故障把用户踢下线。
     */
    suspend fun courses(): List<CxCourse> {
        val session = cred.session ?: throw CxException("请先登录学习通", 401)
        if (session.cookies.isEmpty()) throw CxException("请先登录学习通", 401)
        return CxApi(session.cookies).courses()
    }

    /** 只有用户主动退出时调用。 */
    fun signOut() = cred.clear()
}