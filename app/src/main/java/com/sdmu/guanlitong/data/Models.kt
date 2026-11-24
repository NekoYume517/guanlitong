package com.sdmu.guanlitong.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** 服务的登录方式。 */
enum class LoginMode {
    /** 在 WebView 内完成官方登录（验证码、统一身份认证等）。 */
    WEB,

    /** 通过后端 JSON 接口用账号密码登录。 */
    CREDENTIAL,
}

/**
 * 一个被整合的校园服务。
 *
 * @param id 稳定标识，用于保存账号与 Cookie
 * @param configurable 是否允许在设置中修改服务地址
 * @param requiresAccount 是否需要登录（false 表示纯信息类服务，不进账号管理）
 * @param externalPackage 若已安装该独立 App，则优先直接打开它（如第三方「山管助手」）
 */
data class SchoolService(
    val id: String,
    val name: String,
    val summary: String,
    val defaultUrl: String,
    val loginMode: LoginMode,
    val icon: ImageVector,
    val accent: Color,
    val configurable: Boolean = false,
    val requiresAccount: Boolean = true,
    val externalPackage: String? = null,
)

/** 某个服务在本机保存的账号状态。 */
data class Account(
    val serviceId: String,
    val username: String = "",
    val nickname: String = "",
    val loggedIn: Boolean = false,
    val lastLoginAt: Long = 0L,
    val hasPassword: Boolean = false,
) {
    val displayName: String
        get() = nickname.ifBlank { username }
}
