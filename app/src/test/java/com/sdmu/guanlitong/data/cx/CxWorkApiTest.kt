package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 作业接口：302 → 跟随、401/202/500 错误码、200 + meta refresh 兜底，
 * 以及提交参数拼装与成功/失败判定。
 */
class CxWorkApiTest {

    private lateinit var server: ServerSocket
    private val workers = mutableListOf<Thread>()

    @Before
    fun setUp() {
        server = ServerSocket(0)
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        workers.forEach { runCatching { it.join(2_000) } }
    }

    private fun base(path: String): String = "http://127.0.0.1:${server.localPort}$path"

    private fun api(): CxWorkApi = CxWorkApi(
        CxApi(mapOf("_uid" to "1"), attempts = 1, pause = {}),
    )

    private fun respond(
        statusLine: String,
        headers: String = "",
        body: String = "",
        captured: AtomicReference<String>? = null,
    ) {
        val worker = thread(start = true, isDaemon = true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            runCatching {
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val headBytes = ByteArrayOutputStream()
                while (true) {
                    val byte = input.read()
                    if (byte == -1) break
                    headBytes.write(byte)
                    val bytes = headBytes.toByteArray()
                    val size = bytes.size
                    if (size >= 4 &&
                        bytes[size - 4] == 13.toByte() &&
                        bytes[size - 3] == 10.toByte() &&
                        bytes[size - 2] == 13.toByte() &&
                        bytes[size - 1] == 10.toByte()
                    ) {
                        break
                    }
                }
                val head = String(headBytes.toByteArray(), Charsets.UTF_8)
                val contentLength = Regex("(?i)content-length:\\s*(\\d+)")
                    .find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bodyBuffer = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = input.read(bodyBuffer, read, contentLength - read)
                    if (count <= 0) break
                    read += count
                }
                captured?.set(String(bodyBuffer, 0, read, Charsets.UTF_8))

                val bytes = body.toByteArray(Charsets.UTF_8)
                val responseHead = "HTTP/1.1 $statusLine\r\n$headers" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(responseHead.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
            }
            runCatching { socket.close() }
        }
        workers += worker
    }

    private fun loadDetail(): CxWorkDetail = CxWorkParser.parse(
        CxWorkFixtureData.workPageHtml,
        CxWorkFixtureData.DOWORK_URL,
    )

    @Test
    fun followsRedirectToDoWorkPage() {
        respond("302 Found", "Location: /doWork?workId=456\r\n")
        respond("200 OK", "Content-Type: text/html\r\n", CxWorkFixtureData.workPageHtml)

        val detail = runBlocking {
            api().fetchDetail(base("/mooc2/work/intoexamorwork?workId=456"))
        }
        assertEquals(7, detail.questions.size)
        assertEquals("高等数学", detail.courseName)
    }

    @Test
    fun unauthorizedMeans401() {
        respond("401 Unauthorized")
        val e = assertThrows(CxException::class.java) {
            runBlocking { api().fetchDetail(base("/mooc2/work/intoexamorwork")) }
        }
        assertEquals(CX_CODE_AUTH, e.code)
    }

    @Test
    fun redirectWithoutLocationMeans401() {
        respond("302 Found")
        val e = assertThrows(CxException::class.java) {
            runBlocking { api().fetchDetail(base("/mooc2/work/intoexamorwork")) }
        }
        assertEquals(CX_CODE_AUTH, e.code)
    }

    @Test
    fun riskPageMeans403() {
        respond("202 Accepted", "", "请完成验证码")
        val e = assertThrows(CxException::class.java) {
            runBlocking { api().fetchDetail(base("/mooc2/work/intoexamorwork")) }
        }
        assertEquals(CX_CODE_RISK, e.code)
    }

    @Test
    fun serverErrorMeans502() {
        respond("500 Internal Server Error", "", "boom")
        val e = assertThrows(CxException::class.java) {
            runBlocking { api().fetchDetail(base("/mooc2/work/intoexamorwork")) }
        }
        assertEquals(502, e.code)
    }

    @Test
    fun followsClientSideRedirectPage() {
        respond("200 OK", "Content-Type: text/html\r\n", CxWorkFixtureData.redirectPageHtml)
        respond("200 OK", "Content-Type: text/html\r\n", CxWorkFixtureData.workPageHtml)

        val detail = runBlocking {
            api().fetchDetail(base("/mooc2/work/intoexamorwork"))
        }
        assertEquals(7, detail.questions.size)
    }

    @Test
    fun submitSendsAnswersAndBaseParams() {
        val captured = AtomicReference<String>()
        respond("200 OK", "Content-Type: application/json\r\n", CxWorkFixtureData.submitSuccessJson, captured)
        val detail = loadDetail().copy(submitUrl = base("/submit"))
        val answers = mapOf(
            "111" to listOf("B"),
            "333" to listOf("false"),
            "444" to listOf("第一空", "第二空"),
            "555" to listOf("惯性"),
        )

        val result = runBlocking { api().submit(detail, answers) }

        assertTrue(result.success)
        assertEquals("提交成功", result.message)
        val body = URLDecoder.decode(captured.get(), "UTF-8")
        assertTrue(body, body.contains("workId=404252409"))
        assertTrue(body, body.contains("answer111=B"))
        assertFalse(body, body.contains("answerEditor1111"))
        assertTrue(body, body.contains("answer333=false"))
        assertTrue(body, body.contains("answerEditor4441=第一空"))
        assertTrue(body, body.contains("answerEditor4442=第二空"))
        assertTrue(body, body.contains("answer444=第一空"))
        assertTrue(body, body.contains("answer555=惯性"))
        // 未作答的题不写 answer 字段，但仍要出现在 answerwqbid 里。
        assertFalse(body, body.contains("answer222="))
        assertTrue(body, body.contains("answerwqbid=111,222,333,444,555,666,777"))
    }

    @Test
    fun submitFailureIsReported() {
        respond("200 OK", "Content-Type: application/json\r\n", CxWorkFixtureData.submitFailureJson)
        val detail = loadDetail().copy(submitUrl = base("/submit"))

        val result = runBlocking { api().submit(detail, mapOf("111" to listOf("B"))) }

        assertFalse(result.success)
        assertEquals("作业已截止", result.message)
    }

    @Test
    fun submitRejectsMissingSubmitUrl() {
        val detail = loadDetail().copy(submitUrl = "")
        val e = assertThrows(CxException::class.java) {
            runBlocking { api().submit(detail, emptyMap()) }
        }
        assertEquals(502, e.code)
    }
}