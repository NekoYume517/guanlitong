package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 头解析与登录过期判定；两种都是纯字符串逻辑，不碰网络。
 */
class ZhsHttpTest {

    @Test
    fun parsesCookieWithoutAttributes() {
        assertEquals("CASTGC" to "TGT-abc", ZhsHttp.parseSetCookie("CASTGC=TGT-abc"))
    }

    @Test
    fun ignoresCookieAttributes() {
        assertEquals(
            "CASTGC" to "TGT-abc",
            ZhsHttp.parseSetCookie(
                "CASTGC=TGT-abc; Path=/; Domain=.zhihuishu.com; " +
                    "Expires=Wed, 21 Oct 2026 07:28:00 GMT; HttpOnly; Secure",
            ),
        )
    }

    @Test
    fun keepsBase64CharsInValue() {
        assertEquals("xlvt" to "a+b/c==", ZhsHttp.parseSetCookie("xlvt=a+b/c==; Path=/"))
    }

    @Test
    fun emptyValueMeansRemovalEntry() {
        assertEquals("CASTGC" to "", ZhsHttp.parseSetCookie("CASTGC=; Max-Age=0"))
    }

    @Test
    fun rejectsMalformedCookies() {
        assertNull(ZhsHttp.parseSetCookie("no-equals-sign"))
        assertNull(ZhsHttp.parseSetCookie("=value"))
        assertNull(ZhsHttp.parseSetCookie(""))
    }

    @Test
    fun detectsAuthFailuresWithoutScanningCourseContent() {
        assertTrue(zhsAuthRequired("invalid session"))
        assertTrue(zhsAuthRequired("{\"code\":401}"))
        assertTrue(zhsAuthRequired("{\"code\":\"401\"}"))
        assertTrue(zhsAuthRequired("{\"msg\":\"登录已过期，请重新登录\"}"))
        assertTrue(zhsAuthRequired("{\"message\":\"未登录\"}"))
        assertTrue(zhsAuthRequired("{\"auth_required\":true}"))

        assertFalse(zhsAuthRequired("{\"code\":\"0\"}"))
        assertFalse(zhsAuthRequired("{\"rt\":{\"pageList\":[{\"courseName\":\"401课程\"}]}}"))
        assertFalse(zhsAuthRequired("{\"result\":{\"courseOpenDtos\":[]}}"))
        assertFalse(zhsAuthRequired("<html>502 Bad Gateway</html>"))
        assertFalse(zhsAuthRequired(""))
    }

    @Test
    fun authRequiredForStatusCodeEnvelope() {
        assertTrue(zhsAuthRequired("{\"statusCode\":401}"))
        assertFalse(zhsAuthRequired("{\"statusCode\":200}"))
    }
}