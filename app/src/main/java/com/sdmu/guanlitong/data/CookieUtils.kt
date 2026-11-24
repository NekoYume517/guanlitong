package com.sdmu.guanlitong.data

import android.net.Uri
import android.webkit.CookieManager

/** WebView 登录态的读写与判断。 */
object CookieUtils {

    fun cookieFor(url: String): String =
        CookieManager.getInstance().getCookie(url).orEmpty()

    /**
     * 根据落地 URL 粗略判断是否已登录。
     * 不同服务的登录成功标志不同，这里只对已知服务做判断，
     * 其余服务不自动标记，避免把「未登录但带了匿名 cookie」误判成已登录。
     */
    fun looksLoggedIn(serviceId: String, url: String): Boolean {
        val lowered = url.lowercase()
        return when (serviceId) {
            "jwxt" -> lowered.contains("index_initmenu") || lowered.contains("loginsuccess")
            // 智慧山管：离开登录/SSO 页面、且不是门户首页本身，才算进入已登录区域
            "shanguan" -> lowered.contains("myv.sdmu.edu.cn") &&
                !lowered.contains("login") &&
                !lowered.contains("cas") &&
                !lowered.contains("sso") &&
                !lowered.endsWith(".cn/")
            else -> false
        }
    }

    /** 把后端登录返回的 Cookie 注入 WebView，实现原生登录与网页登录共用登录态。 */
    fun inject(url: String, cookie: String) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setCookie(url, "$cookie; Path=/")
        manager.flush()
    }

    /**
     * 用新会话替换某个路径下的旧 Cookie。
     *
     * 原生登录会在服务端新建一个会话；如果 WebView 里还留着旧的 JSESSIONID，
     * 同一个请求会带两个同名 Cookie，服务器可能取到失效的那个。这里先按可能
     * 的路径清掉同名旧 Cookie，再只写入新会话。
     */
    fun replaceFor(originUrl: String, cookie: String, path: String) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)

        val origin = originUrl.trimEnd('/')
        val scopedUrl = "$origin${if (path.startsWith("/")) path else "/$path"}"
        val existing = buildString {
            append(manager.getCookie(origin).orEmpty())
            append(';')
            append(manager.getCookie("$scopedUrl/").orEmpty())
            append(';')
            append(cookie)
        }
        val names = existing.split(';')
            .map { it.trim().substringBefore('=').trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // CookieManager 只能按 URL 读写，旧 Cookie 的 Path 未知，常见路径都清一遍。
        val oldPaths = listOf("/", path, "$path/")
        for (name in names) {
            for (oldPath in oldPaths) {
                manager.setCookie(origin, "$name=; Max-Age=0; Path=$oldPath")
            }
        }

        cookie.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair ->
                manager.setCookie(scopedUrl, "$pair; Path=$path")
            }
        manager.flush()
    }

    /** 清除某个服务域名下的 Cookie（逐个设置为过期，覆盖 URL 各层路径）。 */
    fun clearFor(url: String) {
        val manager = CookieManager.getInstance()
        val names = manager.getCookie(url).orEmpty()
            .split(';')
            .map { it.trim().substringBefore('=').trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val paths = pathsOf(url)
        for (name in names) {
            for (path in paths) {
                manager.setCookie(url, "$name=; Max-Age=0; Path=$path")
            }
        }
        manager.flush()
    }

    /** 页面 URL 的所有父目录：`/jwglxt/xtgl/login.html` → `/`、`/jwglxt`、`/jwglxt/xtgl`。 */
    private fun pathsOf(url: String): List<String> {
        val path = Uri.parse(url).path.orEmpty()
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        val paths = linkedSetOf("/")
        var current = ""
        for (segment in segments.dropLast(1)) {
            current += "/$segment"
            paths += current
            paths += "$current/"
        }
        return paths.toList()
    }

    fun clearAll() {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }
}
