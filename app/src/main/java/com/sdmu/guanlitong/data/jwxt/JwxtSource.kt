package com.sdmu.guanlitong.data.jwxt

import com.sdmu.guanlitong.data.CookieUtils

/** 教务数据的来源：直连官方教务，或经自建抢课助手（sdmu-website-2）代查。 */
enum class JwxtSource(val key: String, val label: String, val hint: String) {
    Official(
        key = "official",
        label = "官方教务系统",
        hint = "用教务系统账号登录",
    ),
    Grab(
        key = "grab",
        label = "抢课助手",
        hint = "用抢课助手账号登录；要先在抢课助手里绑定教务账号。",
    ),
    ;

    companion object {
        fun fromKey(key: String?): JwxtSource =
            entries.firstOrNull { it.key == key } ?: Official
    }
}

/**
 * 当前教务数据的访问入口。
 *
 * 官方与抢课助手分别在各自域名下保存 Cookie，原生页只认 [origin] 这一份会话；
 * 切换来源时页面会换一个 Cookie 域和一个缓存 key。
 */
data class JwxtAccess(
    val source: JwxtSource,
    val officialUrl: String,
    val grabUrl: String,
    /** 会话失效时自动重登并返回新的 Cookie；为空表示不自动重登。 */
    val relogin: (suspend () -> String?)? = null,
) {
    /** 数据根地址：官方取到 /jwglxt，抢课助手取到站点根。 */
    val origin: String
        get() = when (source) {
            JwxtSource.Official -> JwxtClient.baseOf(officialUrl)
            JwxtSource.Grab -> GrabApiClient.baseOf(grabUrl)
        }

    /** 当前来源的登录态（WebView Cookie）。 */
    fun cookie(): String =
        origin.takeIf { it.isNotBlank() }
            ?.let { CookieUtils.cookieFor("$it/") }
            .orEmpty()

    /** 未登录或地址缺失时返回 null；配了 [relogin] 时会在会话失效后自动重登再重试。 */
    fun client(cookie: String = cookie()): JwxtDataSource? {
        val inner = JwxtDataSourceFactory.create(source, officialUrl, grabUrl, cookie)
            ?: return null
        val relogin = relogin ?: return inner
        return JwxtDataSourceFactory.withRelogin(inner, relogin) { next ->
            JwxtDataSourceFactory.create(source, officialUrl, grabUrl, next)
        }
    }
}