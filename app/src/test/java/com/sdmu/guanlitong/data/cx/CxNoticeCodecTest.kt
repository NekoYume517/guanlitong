package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SharedPreferences 里的收件箱 JSON 编解码：坏数据不该让整个列表崩掉。 */
class CxNoticeCodecTest {

    @Test
    fun noticeRoundTripKeepsNullableFields() {
        val unread = CxNoticeEntity(
            id = "1",
            accountKey = "u1",
            ntype = "inbox_work",
            title = "📝 作业",
            content = "内容",
            timeMs = 123,
            remoteRead = false,
            localRead = false,
            cxInboxId = "cx1",
            actionUrl = "https://example.com/a",
        )
        val read = CxNoticeEntity(
            id = "2",
            accountKey = "u1",
            ntype = "inbox_checkin",
            title = "✅ 签到",
            content = "签到开始",
            timeMs = 456,
            remoteRead = true,
            localRead = true,
        )
        assertEquals(listOf(unread, read), decodeNotices(encodeNotices(listOf(unread, read))))
    }

    @Test
    fun badNoticeDataIsDropped() {
        assertTrue(decodeNotices(null).isEmpty())
        assertTrue(decodeNotices("not json").isEmpty())
        assertEquals(
            1,
            decodeNotices("""[{"id":"ok","ntype":"inbox_notice","timeMs":1},{"oops":1}]""").size,
        )
    }
}