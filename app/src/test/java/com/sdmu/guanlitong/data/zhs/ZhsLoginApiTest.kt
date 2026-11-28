package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 登录响应解析：成功判据与上游提示透传。 */
class ZhsLoginApiTest {

    @Test
    fun acceptsNumericStringAndBooleanStatus() {
        assertEquals(
            "pwd-1",
            ZhsLoginApi.parseLoginResponse(200, "{\"status\":1,\"pwd\":\"pwd-1\",\"uuid\":\"u1\"}").pwd,
        )
        assertEquals(
            "pwd-2",
            ZhsLoginApi.parseLoginResponse(200, "{\"status\":\"1\",\"pwd\":\"pwd-2\"}").pwd,
        )
        assertEquals(
            "pwd-3",
            ZhsLoginApi.parseLoginResponse(200, "{\"status\":true,\"pwd\":\"pwd-3\"}").pwd,
        )
        assertEquals(
            "u9",
            ZhsLoginApi.parseLoginResponse(200, "{\"status\":1,\"pwd\":\"p\",\"uuid\":\"u9\"}").uuid,
        )
    }

    @Test
    fun failureCarriesServerMessage() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsLoginApi.parseLoginResponse(200, "{\"status\":0,\"msg\":\"密码错误\"}")
        }
        assertEquals(ZhsErrorKind.AUTH, error.kind)
        assertEquals("密码错误", error.message)
    }

    @Test
    fun blankFailureMessageGetsFriendlyFallback() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsLoginApi.parseLoginResponse(200, "{\"status\":0}")
        }
        assertEquals("账号或密码不正确，或滑块验证已失效", error.message)
    }

    @Test
    fun missingTicketIsParseError() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsLoginApi.parseLoginResponse(200, "{\"status\":1,\"pwd\":\"\"}")
        }
        assertEquals(ZhsErrorKind.PARSE, error.kind)
    }

    @Test
    fun http401IsAuthError() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsLoginApi.parseLoginResponse(401, "")
        }
        assertEquals(ZhsErrorKind.AUTH, error.kind)
    }

    @Test
    fun htmlErrorPageIsServerOrParseError() {
        assertEquals(
            ZhsErrorKind.SERVER,
            assertThrows(ZhsException::class.java) { ZhsLoginApi.parseLoginResponse(502, "<html>") }.kind,
        )
        assertEquals(
            ZhsErrorKind.PARSE,
            assertThrows(ZhsException::class.java) { ZhsLoginApi.parseLoginResponse(200, "<html>") }.kind,
        )
    }
}