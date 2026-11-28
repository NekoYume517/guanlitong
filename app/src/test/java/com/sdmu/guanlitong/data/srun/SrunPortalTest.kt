package com.sdmu.guanlitong.data.srun

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实的 [SrunPortal] 配上假的传输层：核对发出去的 URL 与 Go 版逐字节一致。
 *
 * 期望的查询串是 Go 版 `url.Values.Encode()` 的输出（时间戳固定），
 * 所以参数个数、键序、转义、info 与 chksum 的取值都对得上。
 * 账号、密码、IP 都是虚构夹具。
 */
class SrunPortalTest {

    @Test
    fun loginSendsTheSameUrlsAsGo() = runBlocking {
        val (portal, transport) = portal(handler = ::happyPath)

        portal.initIp()
        val result = portal.login("demo-user", "demo-Passw0rd!")

        assertEquals(
            listOf(
                "$BASE/",
                CHALLENGE_URL,
                LOGIN_URL,
            ),
            transport.requests,
        )
        // Go 版强调的两条：get_challenge 只有 4 个参数，srun_portal 严格 14 个。
        assertEquals(4, CHALLENGE_URL.substringAfter('?').split('&').size)
        assertEquals(14, LOGIN_URL.substringAfter('?').split('&').size)
        assertTrue(LOGIN_URL.contains("os=windows%2B10"))
        assertTrue(LOGIN_URL.contains("name=windows"))
        assertFalse(LOGIN_URL.contains("mac="))
        assertFalse(LOGIN_URL.contains("nas_ip"))

        assertTrue(result.accepted)
        assertEquals("login_ok", result.sucMsg)
        assertEquals("10.0.0.8", portal.ip)
    }

    @Test
    fun clientIpFromChallengeOverridesThePageOne() = runBlocking {
        val (portal, transport) = portal {
            when (it) {
                "$BASE/" -> PAGE
                else -> CHALLENGE_WITH("tok", ""","client_ip":"10.0.0.9"""")
            }
        }

        portal.initIp()
        portal.login("demo-user", "demo-Passw0rd!")

        assertEquals("10.0.0.9", portal.ip)
        assertTrue(transport.requests.last().contains("&ip=10.0.0.9&"))
    }

    @Test
    fun onlineIpIsUsedOnlyWithoutClientIp() {
        suspend fun ipAfterLogin(challengeFields: String): String {
            val (portal, _) = portal {
                when (it) {
                    "$BASE/" -> PAGE
                    else -> CHALLENGE_WITH("tok", challengeFields)
                }
            }
            portal.initIp()
            portal.login("demo-user", "demo-Passw0rd!")
            return portal.ip
        }
        runBlocking {
            assertEquals("10.0.0.10", ipAfterLogin(""","online_ip":"10.0.0.10""""))
            assertEquals(
                "10.0.0.11",
                ipAfterLogin(""","client_ip":"10.0.0.11","online_ip":"10.0.0.10""""),
            )
            // 两个都没给：沿用首页取到的。
            assertEquals("10.0.0.8", ipAfterLogin(""))
        }
    }

    @Test
    fun configuredIpSkipsThePortalPage() = runBlocking {
        val (portal, transport) = portal(SrunConfig(ip = " 10.0.0.7 "), ::happyPath)

        portal.initIp()

        assertEquals(emptyList<String>(), transport.requests)
        assertEquals("10.0.0.7", portal.ip)
    }

    @Test
    fun firstSchemeSucceedingMeansOneRequest() = runBlocking {
        val (portal, transport) = portal(SrunConfig(ip = "10.0.0.8")) { ACCEPTED }

        assertEquals(ACCEPTED, portal.logout("demo-user"))

        assertEquals(1, transport.requests.size)
    }

    @Test
    fun fallsBackToTheOtherSchemeOnlyWhenTheFirstFails() = runBlocking {
        val (portal, transport) = portal {
            when {
                it == "http://wlrz.sdmu.edu.cn/" -> PAGE
                it.startsWith("https://") -> throw IOException("reset")
                else -> error("unexpected request $it")
            }
        }

        portal.initIp()

        assertEquals(listOf("$BASE/", "http://wlrz.sdmu.edu.cn/"), transport.requests)
        assertEquals("10.0.0.8", portal.ip)

        // 配置成 http 时反过来：先 http，再 https。
        val (inverted, second) = portal(SrunConfig(initUrl = "http://portal.example.edu/")) {
            if (it.startsWith("https://")) PAGE else throw IOException("reset")
        }
        inverted.initIp()
        assertEquals(
            listOf("http://portal.example.edu/", "https://portal.example.edu/"),
            second.requests,
        )
    }

    @Test
    fun bothSchemesFailingReportsBothReasons() = runBlocking {
        val (portal, transport) = portal(handler = { throw IOException("reset") })

        portal.initIp() // 首页拿不到不算错，后面的请求照常进行。
        assertEquals(2, transport.requests.size)

        val error = assertThrows(SrunException::class.java) {
            runBlocking { portal.logout("demo-user") }
        }
        assertEquals(SrunErrorKind.NETWORK, error.kind)
        assertEquals("https：reset；http：reset", error.message)
    }

    @Test
    fun logoutSendsTheSameUrlAsGo() = runBlocking {
        val (portal, transport) = portal(SrunConfig(ip = "10.0.0.8", acId = "3")) { "ok" }

        assertEquals("ok", portal.logout("demo-user"))

        assertEquals(
            "https://wlrz.sdmu.edu.cn/cgi-bin/srun_portal?ac_id=3&action=logout&" +
                "callback=jQuery11240645308969735664_1700000000002&ip=10.0.0.8&username=demo-user",
            transport.requests.single(),
        )
    }

    @Test
    fun onlineStatusNeverThrows() = runBlocking {
        val (online, transport) = portal { ONLINE_BODY }
        val status = online.checkOnline()
        assertTrue(status.online)
        assertEquals(
            "$BASE/cgi-bin/rad_user_info?_=1700000000003&callback=jQuery11240645308969735664_1700000000002&ip=",
            transport.requests.single(),
        )

        val (offline, _) = portal { """cb({"error":"not_online_error"});""" }
        val notOnline = offline.checkOnline()
        assertFalse(notOnline.online)
        assertFalse(notOnline.failed)
        assertEquals("not_online_error", notOnline.detail)

        val (down, _) = portal(handler = { throw IOException("reset") })
        val unreachable = down.checkOnline()
        assertFalse(unreachable.online)
        assertTrue(unreachable.failed)
        assertEquals("查询失败：https：reset；http：reset", unreachable.detail)

        val (hijacked, _) = portal(handler = { "<html>请先登录</html>" })
        val unparsable = hijacked.checkOnline()
        assertTrue(unparsable.failed)
        assertEquals("解析失败：<html>请先登录</html>", unparsable.detail)
    }

    @Test
    fun rejectedLoginIsParsedNotThrown() = runBlocking {
        val (portal, _) = portal {
            if ("get_challenge" in it) CHALLENGE else REJECTED_BODY
        }

        val result = portal.login("demo-user", "wrong-password")

        assertFalse(result.accepted)
        assertEquals("账号或密码不对（门户返回：示例提示）", result.message)
    }

    @Test
    fun missingChallengeOrHijackedLoginIsAParseError() = runBlocking {
        val (noToken, _) = portal(handler = { CHALLENGE_WITH("", ""","client_ip":"10.0.0.8"""") })
        val missing = assertThrows(SrunException::class.java) {
            runBlocking { noToken.login("demo-user", "demo-Passw0rd!") }
        }
        assertEquals(SrunErrorKind.PARSE, missing.kind)
        assertTrue(missing.message!!.startsWith("门户没有下发 challenge"))

        val (hijacked, transport) = portal {
            if ("get_challenge" in it) CHALLENGE else "<html>请先登录</html>"
        }
        val hijackedError = assertThrows(SrunException::class.java) {
            runBlocking { hijacked.login("demo-user", "demo-Passw0rd!") }
        }
        assertEquals(SrunErrorKind.PARSE, hijackedError.kind)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun networkErrorsDoNotEchoRequestUrls() {
        // 底层异常的原话里可能带完整 URL（里面有密码的 MD5），不能原样上屏。
        val leaky = IOException("Failed to connect to portal/?password={MD5}abc&info=x")
        assertEquals("网络错误，请稍后重试", leaky.srunMessage())
        assertEquals("连接被重置", IOException("连接被重置").srunMessage())
        // 认不出响应时带出的正文片段，查询串也要抹掉。
        assertEquals(
            """<a href="http://portal/?…">请先登录</a>""",
            SrunParser.snippet("""<a href="http://portal/?wlanuserip=10.0.0.8&u=demo">请先登录</a>"""),
        )
    }

    private class FakeTransport(private val handler: (String) -> String) : SrunTransport {
        val requests = mutableListOf<String>()

        override fun get(url: String): String {
            requests += url
            return handler(url)
        }
    }

    /**
     * 建一个配了假传输层的门户。时钟每次读数 +1ms：
     * 第一个请求的 callback 是 …002、`_` 是 …003，与 Go 黄金值对齐。
     */
    private fun portal(
        config: SrunConfig = SrunConfig(),
        handler: (String) -> String,
    ): Pair<SrunPortal, FakeTransport> {
        var tick = 1700000000002L
        val transport = FakeTransport(handler)
        return SrunPortal(config, transport) { tick++ } to transport
    }

    private fun happyPath(url: String): String = when {
        url == "$BASE/" -> PAGE
        "get_challenge" in url -> CHALLENGE
        else -> ACCEPTED
    }

    /** `cb({"challenge":"<token>", <fields>});` */
    private fun CHALLENGE_WITH(token: String, fields: String): String =
        """cb({"challenge":"$token"$fields});"""

    private companion object {
        const val BASE = "https://wlrz.sdmu.edu.cn"

        val PAGE = """<html><input type="hidden" id="user_ip" value="10.0.0.8"></html>"""
        const val CHALLENGE = """jQuery_x({"challenge":"test-challenge-123","client_ip":"10.0.0.8"});"""
        const val ACCEPTED = """jQuery_y({"res":"ok","error":"ok","suc_msg":"login_ok"});"""
        const val ONLINE_BODY =
            """cb({"error":"ok","user_name":"demo-user","online_ip":"10.0.0.8"});"""
        const val REJECTED_BODY =
            """cb({"res":"login_error","error":"login_error","error_msg":"示例提示"});"""

        /** Go 版首页已取得 IP 后，get_challenge 的完整 URL。 */
        const val CHALLENGE_URL = "$BASE/cgi-bin/get_challenge?_=1700000000003&" +
            "callback=jQuery11240645308969735664_1700000000002&ip=10.0.0.8&username=demo-user"

        /** Go 版 srun_portal 登录的完整 URL，账号密码均为虚构夹具。 */
        const val LOGIN_URL = "$BASE/cgi-bin/srun_portal?_=1700000000005&ac_id=1&action=login&" +
            "callback=jQuery11240645308969735664_1700000000004&" +
            "chksum=f35869e3b3ba39eb656d843215cc359cbc3c9db3&double_stack=0&info=%7BSRBX1%7D" +
            "mQ%2BTUfcHoSUrsWXC7krO3sXnIKr2BcxJz%2FeCd3hWln8XEqMJ5sd7gT5SZ5wcgJNYZSB7aeFMSF1oXK7r" +
            "Q77LXhVd7EjXUyr7c9AC20w4%2BDJ6WFYpCFe%2FoMbKqSlugq1vsEPk6gCYRQM%3D&ip=10.0.0.8&n=200&" +
            "name=windows&os=windows%2B10&password=%7BMD5%7De8b21cd30d0e10e96dea1d6999d7880f&type=1&" +
            "username=demo-user"
    }
}
