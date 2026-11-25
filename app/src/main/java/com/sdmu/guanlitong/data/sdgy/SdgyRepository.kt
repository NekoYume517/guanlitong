package com.sdmu.guanlitong.data.sdgy

import android.content.Context
import com.sdmu.guanlitong.data.AccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 山管业务页面共用的会话入口。
 *
 * 页面不直接碰加密存储，也不自己处理被顶号：
 * - 正常调用直接使用本地会话；
 * - 服务端返回会话失效时，交给 [SdgySessionManager] 自动重登一次再重试；
 * - 重登失败（夜间关机/断网）只把错误抛给界面，绝不清除登录状态。
 */
class SdgyRepository(context: Context) {

    private val appContext = context.applicationContext
    private val store = SdgySessionStore(appContext)
    private val accounts = AccountStore(appContext)

    /** 山管登录学号。付款码换卡号、页面展示都需要它。 */
    fun studentId(): String =
        accounts.accounts.value["sdgy"]?.username.orEmpty()
            .ifBlank { store.load()?.userId.orEmpty() }

    fun displayName(): String =
        accounts.accounts.value["sdgy"]?.displayName.orEmpty()

    /** 打开页面时强制探活一次，让被顶号能当场恢复。 */
    suspend fun ensureOnline(): SdgySessionCheck =
        SdgySessionManager.ensure(appContext, force = true)

    /**
     * 在 IO 线程执行一次山管业务调用。
     *
     * 会话失效会自动重登并重试一次；网络失败原样抛出，由页面显示可重试提示。
     */
    suspend fun <T> call(
        forceCheck: Boolean = false,
        block: (SdgyClient) -> T,
    ): T = withContext(Dispatchers.IO) {
        if (forceCheck) SdgySessionManager.ensure(appContext, force = true)
        var client = newClient()
        try {
            try {
                block(client)
            } catch (e: SdgyException) {
                if (!e.sessionLost) throw e
                val check = SdgySessionManager.ensure(appContext, force = true)
                if (check.state != SdgySessionState.Online) {
                    throw SdgyException(check.message ?: "山管登录已失效，请重新登录", e.code)
                }
                client = newClient()
                block(client)
            }
        } finally {
            // 业务请求可能刷新 Cookie，成功与否都尽量写回，方便下次继续用。
            runCatching { store.save(client.session) }
        }
    }

    private fun newClient(): SdgyClient {
        val session = store.load()?.takeIf { it.hasSession }
            ?: throw SdgyException("还没登录山管 App，先到「我的 → 山管 App」登录", "not_login")
        return SdgyClient(store.deviceId(), session)
    }
}