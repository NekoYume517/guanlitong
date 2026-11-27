package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 收件箱接口的错误码与真实路径：302/401 → 401（交给 Repository 重登），202 → 403，
 * 其他非 200 → 502；200 时按 body 解析。
 */
class CxInboxApiTest {

    private lateinit var server: ServerSocket
    private val workers = mutableListOf<Thread>()

    @Before
    fun setUp() {
        server = ServerSocket(0)
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        workers.forEach { runCatching { it.join(1_000) } }
    }

    private fun respond(statusLine: String, headers: String = "", body: String = "") {
        val worker = thread(start = true, isDaemon = true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            runCatching {
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 $statusLine\r\n$headers" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
            }
            runCatching { socket.close() }
        }
        workers += worker
    }

    private fun api(): CxInboxApi = CxInboxApi(
        CxApi(mapOf("_uid" to "1"), attempts = 1, pause = {}),
        baseUrl = "http://127.0.0.1:${server.localPort}",
    )

    @Test
    fun redirectMeansAuthLost() {
        respond("302 Found", "Location: https://mooc1-1.chaoxing.com/visit/login\r\n")
        val e = assertThrows(CxException::class.java) { runBlocking { api().fetch() } }
        assertEquals(CX_CODE_AUTH, e.code)
    }

    @Test
    fun riskPageMeans403() {
        respond("202 Accepted", "", "请完成验证码")
        val e = assertThrows(CxException::class.java) { runBlocking { api().fetch() } }
        assertEquals(CX_CODE_RISK, e.code)
    }

    @Test
    fun serverErrorMeans502() {
        respond("500 Internal Server Error", "", "boom")
        val e = assertThrows(CxException::class.java) { runBlocking { api().fetch() } }
        assertEquals(502, e.code)
    }

    @Test
    fun parsesSuccessfulPage() {
        respond("200 OK", "Content-Type: application/json\r\n", CxFixtureData.inboxJson)
        val page = runBlocking { api().fetch() }
        assertEquals(3, page.items.size)
        assertEquals("abc123", page.lastGetId)
    }
}