package com.sdmu.guanlitong.data.pu

import android.content.Context

/** 山东管理学院 go_id（§6.4）。 */
internal const val PU_SCHOOL_GO_ID = 208791531159552L

/**
 * 进程级运行时：持有当前 [PuApi]。
 *
 * 项目没有 Application 类，无法在 `onCreate` 里初始化；所以由 [PuCredStore]
 * 的构造（每个 PU 页面都会调用 `rememberPuCredStore()`）完成 attach + bind。
 */
internal object PuRuntime {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var api: PuApi? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun bind(token: String, goId: String) {
        api = PuApi(token, goId)
    }

    fun unbind() {
        api = null
    }

    /** 未登录 / 已清凭据时抛 401，交给 `puCall()` 统一处理。 */
    fun requireApi(): PuApi = api ?: throw PuException("请先登录口袋校园", 401)

    /** 保留给需要 Application 级 context 的场景（当前未使用）。 */
    fun contextOrNull(): Context? = appContext
}

/**
 * PU 数据层入口：登录 / 退出 + 凭据存储。
 *
 * 业务动作统一走 `PuActions`（阶段 B 的 UI 就是这样调用的），
 * 这里只负责「拿到 token 并落到 [PuCredStore]」。
 */
class PuRepository(context: Context) {

    val store: PuCredStore = PuCredStore(context.applicationContext)

    /**
     * 登录：密码只在这一个请求里出现，不保存、不打印（§6.4 第 5 条）。
     * 成功后 [PuCredStore.save] 会把 token（Keystore 加密）+ goId + 姓名 + 学号落盘。
     */
    suspend fun login(
        account: String,
        password: String,
        schoolId: Long = PU_SCHOOL_GO_ID,
    ): PuSession {
        val session = PuLoginApi.login(account, password, schoolId)
        store.save(session)
        return session
    }

    /** 退出登录：清凭据 + 解绑运行时。 */
    fun logout() {
        store.clear()
    }
}