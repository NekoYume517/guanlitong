package com.sdmu.guanlitong.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号表单「记住密码」默认勾选。
 *
 * 各登录表单（教务、学习通、第二课堂、抢课助手、山管、知到、通用凭据）都从这里取
 * 初始值：默认保存密码后，首次登录即具备过期自动重登/回填的条件。
 */
class AccountDefaultsTest {

    @Test
    fun rememberPasswordIsCheckedByDefault() {
        assertTrue(AccountDefaults.REMEMBER_PASSWORD)
    }
}