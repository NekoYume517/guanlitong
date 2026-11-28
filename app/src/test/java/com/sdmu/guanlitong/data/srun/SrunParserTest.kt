package com.sdmu.guanlitong.data.srun

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 门户响应的解析与错误码文案：判据与 Go 版各解析分支一致。响应内容全部是虚构夹具。 */
class SrunParserTest {

    @Test
    fun userIdComesFromHiddenInput() {
        assertEquals(
            "10.12.34.56",
            SrunParser.parseUserId("""<html><input type="hidden" id="user_ip" value="10.12.34.56"></html>"""),
        )
        assertEquals("", SrunParser.parseUserId("""<input id="user_ip" value="">"""))
        assertNull(SrunParser.parseUserId("""<input id="other" value="10.0.0.8">"""))
    }

    @Test
    fun jsonpWrapperIsStripped() {
        assertEquals("abc", SrunParser.parseJsonp("""jQuery1124_1({"challenge":"abc"});""").getString("challenge"))
        assertEquals("abc", SrunParser.parseJsonp("""  {"challenge":"abc"}  """).getString("challenge"))
        // Go 的 json.Unmarshal 遇到 null 给一个空 map。
        assertEquals(0, SrunParser.parseJsonp("null").length())
    }

    @Test
    fun unparsableBodyIsParseError() {
        val hijacked = assertThrows(SrunException::class.java) {
            SrunParser.parseJsonp("<html>请先登录</html>")
        }
        assertEquals(SrunErrorKind.PARSE, hijacked.kind)
        // 原始内容带在提示里（最多 200 字），才认得出被劫持成了什么页面。
        assertTrue(hijacked.message!!.contains("请先登录"))

        val long = assertThrows(SrunException::class.java) { SrunParser.parseJsonp("x".repeat(500)) }
        assertTrue(long.message!!.contains("x".repeat(200) + "..."))
        assertFalse(long.message!!.contains("x".repeat(201)))
    }

    @Test
    fun loginAcceptedOnResOkOrSuccess() {
        assertTrue(SrunParser.parseLogin("""cb({"res":"ok","error":"ok","suc_msg":"login_ok"});""").accepted)
        assertTrue(SrunParser.parseLogin("""cb({"res":"success"});""").accepted)
    }

    @Test
    fun loginAcceptedOnAccessTokenWithoutError() {
        assertTrue(SrunParser.parseLogin("""cb({"access_token":"tok"});""").accepted)
        assertTrue(SrunParser.parseLogin("""cb({"access_token":"tok","error":"ok"});""").accepted)
        assertFalse(SrunParser.parseLogin("""cb({"access_token":"tok","error":"login_error"});""").accepted)
    }

    @Test
    fun numericResCountsForNothingLikeGoTypeAssertion() {
        // Go: m["res"].(string) 对数字失败，拿到空串。
        val result = SrunParser.parseLogin("""cb({"res":1,"error":"ok"});""")
        assertFalse(result.accepted)
        assertEquals("", SrunParser.parseLogin("""cb({"res":1});""").error)
        assertEquals("ok", result.error)
    }

    @Test
    fun loginSummaryMirrorsGoLine() {
        assertEquals(
            "res=ok error=ok error_msg=<nil> suc_msg=login_ok",
            SrunParser.parseLogin("""cb({"res":"ok","error":"ok","suc_msg":"login_ok"});""").summary,
        )
        assertEquals(
            "res=login_error error=login_error error_msg=示例提示 suc_msg=<nil>",
            SrunParser.parseLogin("""cb({"res":"login_error","error":"login_error","error_msg":"示例提示"});""").summary,
        )
        assertEquals("res=1 error=2 error_msg=<nil> suc_msg=<nil>", SrunParser.parseLogin("""cb({"res":1,"error":2})""").summary)
        assertEquals("res=<nil> error=<nil> error_msg=<nil> suc_msg=<nil>", SrunParser.parseLogin("null").summary)
    }

    @Test
    fun loginMessageExplainsPortalErrors() {
        assertEquals("登录成功", SrunParser.parseLogin("""cb({"res":"ok"});""").message)
        assertEquals("login_ok", SrunParser.parseLogin("""cb({"res":"ok","suc_msg":"login_ok"});""").message)
        assertEquals("账号或密码不对", SrunParser.parseLogin("""cb({"error":"login_error"});""").message)
    }

    @Test
    fun errorCodesAreExplainedAndNeverHidePortalText() {
        assertEquals("账号或密码不对", SrunErrors.describe("login_error"))
        assertEquals(
            "门户说这台设备已经在线；如果其实上不了网，先「注销」再登录（门户返回：示例提示）",
            SrunErrors.describe("ip_already_online_error", " 示例提示 "),
        )
        // 认不出的错误码：原样带出门户自己的提示。
        assertEquals("门户拒绝了请求：示例提示", SrunErrors.describe("weird_code", "示例提示"))
        assertEquals("门户拒绝了请求（weird_code）", SrunErrors.describe("weird_code"))
        assertEquals("门户没有给出明确结果", SrunErrors.describe(""))
    }

    @Test
    fun onlineWhenPortalReportsAUser() {
        val status = SrunParser.parseOnline(
            """cb({"error":"ok","user_name":"demo-user","online_ip":"10.0.0.8","online_device_total":2,"add_time":1700000000,"all_bytes":123456789});""",
        )
        assertTrue(status.online)
        assertFalse(status.failed)
        assertEquals("demo-user", status.userName)
        assertEquals("10.0.0.8", status.onlineIp)
        assertEquals("2", status.deviceTotal)
        assertEquals("1700000000", status.addTime)
        assertEquals("123456789", status.allBytes)
    }

    @Test
    fun notOnlineWhenPortalSaysSo() {
        val status = SrunParser.parseOnline("""cb({"error":"not_online_error"});""")
        assertFalse(status.online)
        assertFalse(status.failed)
        assertEquals("not_online_error", status.detail)
    }

    @Test
    fun responseWithoutUserCountsAsOffline() {
        val status = SrunParser.parseOnline("""cb({"error":"ok"})""")
        assertFalse(status.online)
        assertFalse(status.failed)
        assertEquals("""unknown: cb({"error":"ok"})""", status.detail)
    }

    @Test
    fun logoutAcknowledgement() {
        assertEquals(true, SrunParser.parseLogout("""cb({"res":"ok"});"""))
        assertEquals(true, SrunParser.parseLogout("""cb({"error":"ok"});"""))
        assertEquals(false, SrunParser.parseLogout("""cb({"res":"fail"});"""))
        assertEquals(false, SrunParser.parseLogout("""cb({"error":"not_online_error"});"""))
        assertNull(SrunParser.parseLogout("""cb({"client_ip":"10.0.0.8"});"""))
    }

    @Test
    fun stringFieldsOnlyAcceptRealStrings() {
        val json = JSONObject("""{"s":"v","n":1,"b":true,"nil":null,"obj":{"a":1}}""")
        with(json) {
            assertEquals("v", stringOrNull("s"))
            assertEquals("", json.stringOrNull("n"))
            assertEquals("", json.stringOrNull("nil"))
            assertEquals("", json.stringOrNull("obj"))
            assertEquals("", json.stringOrNull("missing"))

            assertEquals("v", json.display("s"))
            assertEquals("1", json.display("n"))
            assertEquals("true", json.display("b"))
            assertEquals("<nil>", json.display("nil"))
            assertEquals("<nil>", json.display("missing"))
        }
    }

    @Test
    fun failuresBecomeReadableChinese() {
        assertEquals("解析不了门户域名，请确认已连接校园网", UnknownHostException("secret-host").srunMessage())
        assertEquals("连接门户超时", SocketTimeoutException("timeout").srunMessage())
        assertEquals("连不上门户，请确认已连接校园网", java.net.ConnectException("refused").srunMessage())
        assertEquals("校园网认证失败", IllegalStateException().srunMessage())
        // 门户自己的解析错误保留原文。
        assertEquals("认不出来", SrunException("认不出来", SrunErrorKind.PARSE).srunMessage())
        // 没有异常对象时（Result 里没有原因）也要有话可说。
        assertEquals("网络错误，请稍后重试", (null as Throwable?).srunMessage())
    }

    @Test
    fun bytesAreHumanReadable() {
        assertEquals("0 B", formatSrunBytes("0"))
        assertEquals("512 B", formatSrunBytes(" 512 "))
        assertEquals("1.5 KB", formatSrunBytes("1536"))
        assertEquals("1.2 GB", formatSrunBytes("1288490189"))
        assertEquals("1.0 TB", formatSrunBytes("1099511627776"))
        assertEquals("n/a", formatSrunBytes("n/a"))
        assertEquals("", formatSrunBytes(""))
    }

    @Test
    fun configFallsBackToGoDefaults() {
        val defaults = SrunConfig().normalized()
        assertEquals("https://wlrz.sdmu.edu.cn", defaults.initUrl)
        assertEquals("wlrz.sdmu.edu.cn", defaults.host)
        assertEquals("200", defaults.n)
        assertEquals("1", defaults.type)
        assertEquals("1", defaults.acId)
        assertEquals(SrunCrypto.ENC_VERSION, defaults.enc)

        // Go 的 base()：去掉末尾斜杠，没写 scheme 补 https，空值回默认。
        val messy = SrunConfig(initUrl = "  portal.example.edu/  ", n = "", type = "", acId = "", enc = "").normalized()
        assertEquals("https://portal.example.edu", messy.initUrl)
        assertEquals(SrunCrypto.ENC_VERSION, messy.enc)
        assertEquals("200", messy.n)
        assertEquals("1", messy.type)
        assertEquals("1", messy.acId)

        assertEquals(SRUN_DEFAULT_PORTAL, SrunConfig(initUrl = "  ").normalized().initUrl)
        assertEquals("http://portal.example.edu", SrunConfig(initUrl = "http://portal.example.edu").normalized().initUrl)
    }
}
