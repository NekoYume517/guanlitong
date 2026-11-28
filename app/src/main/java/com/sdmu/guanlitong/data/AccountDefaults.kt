package com.sdmu.guanlitong.data

/**
 * 账号表单的默认偏好。
 *
 * 「记住密码」默认勾选：首次登录就把密码加密存进 [AccountStore]，登录过期时才能
 * 直接用记住的账号回填（知到自动重登、学习通自动重登都依赖这一条）。用户手动
 * 取消勾选仍按原逻辑立刻清掉本机密码，各登录表单只改初始值，不动保存路径。
 */
object AccountDefaults {

    /** 所有账号登录表单「记住密码」开关的初始值。 */
    const val REMEMBER_PASSWORD = true
}