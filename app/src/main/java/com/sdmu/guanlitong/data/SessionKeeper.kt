package com.sdmu.guanlitong.data

import android.content.Context
import com.sdmu.guanlitong.data.jwxt.SessionRelogin
import com.sdmu.guanlitong.data.sdgy.SdgySessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * 三个账号共用的前台保活。
 *
 * 回到 App 时检查本校教务、抢课助手和山管app：
 * 只有「用户没退出 + 保存了密码 + 本地会话不见了」才会真的重登；
 * 任何失败都不会清除登录状态（学校服务器半夜会关机）。
 */
object SessionKeeper {

    suspend fun onAppForeground(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        supervisorScope {
            launch {
                try {
                    keepJwxtAccounts(appContext)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 保活失败只影响这一次尝试，不会退出任何账号。
                }
            }
            launch {
                try {
                    SdgySessionManager.ensure(appContext)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 同上：服务器不可达时保持原登录状态。
                }
            }
        }
    }

    private suspend fun keepJwxtAccounts(context: Context) {
        val accounts = AccountStore(context)
        val settings = SettingsStore(context)
        val officialUrl = ServiceCatalog.byId("jwxt")?.let { settings.urlFor(it) }.orEmpty()
        val grabUrl = ServiceCatalog.byId("grab")?.let { settings.urlFor(it) }.orEmpty()
        val relogin = SessionRelogin(accounts)

        // 两个账号各自保活，不依赖当前教务数据来源。
        relogin.ensureService("jwxt", officialUrl, grabUrl)
        relogin.ensureService("grab", officialUrl, grabUrl)
    }
}