package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 问答报文逐字节对齐参考实现（不发请求）。 */
class ZhsQaApiTest {

    private fun session(qaKey: String = ""): ZhsSession = ZhsSession(
        account = "138****0000",
        cookies = mapOf("CASTGC" to "TGT-1", "jt-cas" to "CAS-1"),
        qaKey = qaKey,
    )

    @Test
    fun questionPayloadMatchesReferenceJson() {
        assertEquals(
            "{\"courseId\":\"1101\",\"pageIndex\":0,\"pageSize\":50,\"recruitId\":\"2201\"}",
            ZhsQaApi.questionPayload(1101, 2201, 0, 50),
        )
    }

    @Test
    fun answersPayloadMatchesReferenceJson() {
        assertEquals(
            "{\"questionId\":\"q1\",\"sourceType\":\"2\",\"courseId\":\"1101\"," +
                "\"recruitId\":\"2201\",\"pageIndex\":0,\"pageSize\":20}",
            ZhsQaApi.answersPayload("q1", 1101, 2201, 0, 20),
        )
    }

    @Test
    fun savePayloadEscapesContentAndKeepsOrder() {
        assertEquals(
            "{\"annexs\":\"[]\",\"qid\":\"q1\",\"source\":\"2\"," +
                "\"aContent\":\"答\\\"案\",\"courseId\":\"1101\",\"recruitId\":\"2201\",\"saveSource\":1}",
            ZhsQaApi.savePayload("q1", 1101, 2201, "答\"案"),
        )
    }

    @Test
    fun ensureKeyReusesCachedKey() {
        val cached = session(qaKey = "KEY")
        assertEquals(cached, ZhsQaApi.ensureKey(cached))
    }

    @Test
    fun ensureKeyWithoutTicketCountsAsExpired() {
        val error = assertThrows(ZhsException::class.java) {
            ZhsQaApi.ensureKey(ZhsSession(account = "x"))
        }
        assertEquals(ZhsErrorKind.EXPIRED, error.kind)
    }
}