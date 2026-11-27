package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 网络层重试：连接被断开后必须整单重试；连续失败必须上抛
 * [CxException]（[CX_CODE_NETWORK]），绝不能被吞成「空页面」。
 */
class CxApiRetryTest {

    private lateinit var server: ServerSocket
    private val workers = mutableListOf<Thread>()

    @Before
    fun setUp() {
        server = ServerSocket(0).apply { soTimeout = 200 }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        workers.forEach { runCatching { it.join(1_000) } }
    }

    /** 前 [closeFirst] 次连接直接断开，之后返回 200 "ok"；[respond] 为 false 则一直断开。 */
    private fun startServer(closeFirst: Int, respond: Boolean) {
        val worker = thread(start = true, isDaemon = true) {
            var closed = 0
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                if (closed < closeFirst || !respond) {
                    closed++
                    runCatching { socket.close() }
                } else {
                    runCatching {
                        val response = (
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n" +
                                "Connection: close\r\n\r\nok"
                            ).toByteArray()
                        socket.getOutputStream().write(response)
                        socket.getOutputStream().flush()
                        socket.close()
                    }
                }
            }
        }
        workers += worker
    }

    /** 接受连接但永不响应：客户端在 readTimeout 后抛 SocketTimeoutException。 */
    private fun startStallServer() {
        val worker = thread(start = true, isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    runCatching { Thread.sleep(5_000) }
                    runCatching { socket.close() }
                }
            }
        }
        workers += worker
    }

    private fun api(attempts: Int = 3) = CxApi(
        cookies = emptyMap(),
        connectTimeoutMs = 1_000,
        readTimeoutMs = 1_000,
        attempts = attempts,
        pause = { },
    )

    private fun url() = "http://127.0.0.1:${server.localPort}/page"

    @Test(timeout = 15_000)
    fun retriesAfterDroppedConnection() = runBlocking {
        startServer(closeFirst = 2, respond = true)
        val (status, body) = api(attempts = 5).get(url())

        assertEquals(200, status)
        assertEquals("ok", body)
    }

    @Test(timeout = 20_000)
    fun readTimeoutRetriesThenThrowsNetworkError() {
        startStallServer()

        val error = assertThrows(CxException::class.java) {
            runBlocking { api(attempts = 2).get(url()) }
        }
        assertEquals(CX_CODE_NETWORK, error.code)
        assertTrue(
            "应记录真实超时类型，实际：${error.message}",
            error.message.orEmpty().contains("SocketTimeoutException"),
        )
    }

    @Test(timeout = 20_000)
    fun unknownHostThrowsNetworkError() {
        val error = assertThrows(CxException::class.java) {
            runBlocking {
                api(attempts = 3).get("http://no-such-host.invalid/page")
            }
        }
        assertEquals(CX_CODE_NETWORK, error.code)
        assertTrue(error.message.orEmpty().contains("UnknownHostException"))
    }

    @Test(timeout = 15_000)
    fun exhaustedRetriesThrowsNetworkError() {
        startServer(closeFirst = Int.MAX_VALUE, respond = true)

        val error = assertThrows(CxException::class.java) {
            runBlocking { api(attempts = 3).get(url()) }
        }
        assertEquals(CX_CODE_NETWORK, error.code)
    }
}