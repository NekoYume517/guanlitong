package com.sdmu.guanlitong.data.pu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §7.5：签到码拆分。
 *
 * 上游 `Actions.parseMid` 在**最后一个**分隔符处切成 `{后半, 前半}`，
 * 调用方把后半当 mid、前半当活动 id，所以真实格式是 `aid,mid`。
 * §7.5 的示例 `"123,456" → (123,456)` 把顺序写反了；这里以上游代码为准，
 * 两种写法都断言，避免以后有人「按文档修正」从而静默改坏扫码签到。
 */
class ParseMidTest {

    @Test
    fun json_payload() {
        assertEquals("123" to "456", PuActions.parseMid("""{"code":"123","id":"456"}"""))
    }

    @Test
    fun json_payload_with_activity_id_alias() {
        assertEquals("123" to "456", PuActions.parseMid("""{"mid":"123","activityId":"456"}"""))
    }

    @Test
    fun comma_is_aid_then_mid() {
        assertEquals("123" to "456", PuActions.parseMid("456,123"))
        assertEquals("456" to "123", PuActions.parseMid("123,456"))
    }

    @Test
    fun pipe_is_aid_then_mid() {
        assertEquals("123" to "456", PuActions.parseMid("456|123"))
    }

    @Test
    fun bare_mid_has_no_aid() {
        assertEquals("123" to "", PuActions.parseMid("123"))
    }

    @Test
    fun blank_and_garbage() {
        assertEquals("" to "", PuActions.parseMid(""))
        assertEquals("" to "", PuActions.parseMid(null))
        assertEquals("" to "", PuActions.parseMid("   "))
        assertEquals("" to "", PuActions.parseMid("{bad json"))
    }

    @Test
    fun leading_separator_is_treated_as_bare_mid() {
        assertEquals(",123" to "", PuActions.parseMid(",123"))
    }
}