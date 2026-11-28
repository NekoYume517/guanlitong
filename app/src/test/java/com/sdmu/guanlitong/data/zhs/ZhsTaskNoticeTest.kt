package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 终态通知：社交频道、CATEGORY_SOCIAL、PRIORITY_MAX 与文案。 */
class ZhsTaskNoticeTest {

    private fun state(
        courseName: String = "形势与政策",
        gained: Int = 300,
        target: Int = 600,
        captcha: Boolean = false,
        error: String? = null,
    ) = ZhsTaskState(
        mode = ZhsTaskMode.BATCH,
        running = false,
        courseName = courseName,
        target = target,
        gained = gained,
        captcha = captcha,
        error = error,
    )

    @Test
    fun metTargetGoesToSocialChannel() {
        val notice = ZhsTaskNoticeBuilder.complete(state(gained = 600, target = 600))

        assertEquals(ZhsTaskNoticeBuilder.SOCIAL_CHANNEL_ID, notice.channelId)
        assertEquals("zhs_brush_social", notice.channelId)
        assertEquals(ZhsTaskNoticeBuilder.CATEGORY_SOCIAL, notice.category)
        assertEquals("android.intent.category.SOCIAL", notice.category)
        assertEquals(ZhsTaskNoticeBuilder.PRIORITY_MAX, notice.priority)
        assertTrue(notice.social)
        assertTrue(notice.title.contains("形势与政策"))
        assertTrue(notice.title.contains("达标"))
        assertTrue(notice.text.contains("10:00"))
        assertTrue(notice.text.contains("已达标"))
    }

    @Test
    fun unmetTargetSaysUnmet() {
        val notice = ZhsTaskNoticeBuilder.complete(state(gained = 120, target = 600))
        assertTrue(notice.title.contains("未达标"))
        assertTrue(notice.text.contains("02:00"))
        assertTrue(notice.text.contains("未达标"))
    }

    @Test
    fun captchaTellsUserToVerifyInApp() {
        val notice = ZhsTaskNoticeBuilder.complete(
            state(gained = 60, target = 600, captcha = true),
        )
        assertTrue(notice.title.contains("滑块验证"))
        assertTrue(notice.text.contains("智慧树 App"))
        assertTrue(notice.text.contains("完成验证"))
    }

    @Test
    fun errorTerminatesWithReason() {
        val notice = ZhsTaskNoticeBuilder.complete(
            state(gained = 90, target = 600, error = "登录已过期，请重新登录"),
        )
        assertTrue(notice.title.contains("异常终止"))
        assertTrue(notice.text.contains("登录已过期"))
    }

    @Test
    fun singleItemHasNoTargetWording() {
        val notice = ZhsTaskNoticeBuilder.complete(
            ZhsTaskState(
                mode = ZhsTaskMode.ITEM,
                running = false,
                courseName = "课程",
                gained = 45,
            ),
        )
        assertTrue(notice.title.contains("刷课完成"))
        assertTrue(notice.text.contains("00:45"))
    }
}