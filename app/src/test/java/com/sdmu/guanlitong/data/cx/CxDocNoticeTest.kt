package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 资料刷时长终态通知：社交频道口径与停止/失败/结束文案。 */
class CxDocNoticeTest {

    private val course = CxDocCourse(
        courseId = "c1",
        clazzId = "k1",
        personId = "p1",
        cpi = "cpi1",
        enc = "enc1",
        name = "高等数学",
    )

    private fun state(
        reportedSeconds: Long = 300,
        fatalError: String? = null,
        message: String = "已上报 5.0 分钟",
        speed: Double = 10.0,
        pageNum: Int = 20,
    ) = CxDocTaskState(
        running = false,
        course = course,
        fileName = "讲义.pdf",
        speed = speed,
        reportedSeconds = reportedSeconds,
        pageNum = pageNum,
        message = message,
        fatalError = fatalError,
    )

    @Test
    fun terminalNoticesUseDedicatedSocialChannel() {
        val stopped = CxDocNoticeBuilder.complete(state(), CxDocFinishReason.STOPPED)

        assertEquals("cx_brush_social", stopped.channelId)
        assertNotEquals(CxDocNoticeBuilder.PROGRESS_CHANNEL_ID, stopped.channelId)
        assertEquals("android.intent.category.SOCIAL", stopped.category)
        assertEquals(2, stopped.priority)
        assertTrue(stopped.social)
        assertTrue(stopped.title.contains("学习通"))
        assertTrue(stopped.title.contains("高等数学"))
        assertTrue(stopped.title.contains("已停止"))
        assertTrue(stopped.text.contains("讲义.pdf"))
        assertTrue(stopped.text.contains("5.0 分钟"))
    }

    @Test
    fun failedNoticeCarriesReason() {
        val failed = CxDocNoticeBuilder.complete(
            state(fatalError = "阅读心跳连续失败（最后 HTTP 500）"),
            CxDocFinishReason.FAILED,
        )
        assertTrue(failed.title.contains("异常"))
        assertTrue(failed.text.contains("阅读心跳连续失败"))
        assertEquals(CxDocNoticeBuilder.SOCIAL_CHANNEL_ID, failed.channelId)
        assertEquals(CxDocNoticeBuilder.PRIORITY_MAX, failed.priority)
    }

    @Test
    fun doneNoticeShowsPagesAndSpeed() {
        val done = CxDocNoticeBuilder.complete(state(), CxDocFinishReason.DONE)
        assertTrue(done.title.contains("结束"))
        assertTrue(done.text.contains("20 页"))
        assertTrue(done.text.contains("10x"))
        assertTrue(done.social)
    }
}