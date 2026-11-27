package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 刷课范围：video 与 audio 同等对待；文档/作业/测验一律手动。 */
class CxTaskCardTest {

    private fun card(type: String) = CxTaskCard(
        chapterId = "ch1",
        jobid = "job-$type",
        objectId = "obj-$type",
        mid = "",
        type = type,
        name = type,
        isPassed = false,
        duration = 60,
        attDuration = null,
        attDurationEnc = "",
        begins = 0,
        ends = 0,
        otherInfo = "",
        reportUrl = "",
    )

    @Test
    fun audioAndVideoAreBrushable() {
        assertTrue(card("video").isBrushable)
        assertTrue(card("audio").isBrushable)
    }

    @Test
    fun documentsAndWorkAreManual() {
        assertFalse(card("document").isBrushable)
        assertFalse(card("work").isBrushable)
        assertFalse(card("quiz").isBrushable)
    }

    @Test
    fun pendingQueueContainsBothVideoAndAudio() {
        val cards = listOf(
            card("video"),
            card("audio"),
            card("document"),
            card("video").copy(isPassed = true),
            card("audio").copy(isPassed = true),
        )

        val queue = cards.pendingBrushableTasks()

        assertEquals(listOf("job-video", "job-audio"), queue.map { it.jobid })
    }

    @Test
    fun typeLabelCoversKnownTypes() {
        assertEquals("视频", cxTypeLabel("video"))
        assertEquals("音频", cxTypeLabel("audio"))
        assertEquals("文档", cxTypeLabel("document"))
        assertEquals("作业", cxTypeLabel("work"))
        assertEquals("考试", cxTypeLabel("exam"))
        assertEquals("阅读", cxTypeLabel("read"))
    }

    @Test
    fun displayNameHidesTypeIdentifiers() {
        assertEquals("第八次上机作业", card("work").copy(name = "第八次上机作业").displayName)
        assertEquals("作业", card("workid").copy(name = "workid").displayName)
        assertEquals("考试", card("examid").copy(name = "examid").displayName)
        assertEquals("作业", card("workid").copy(name = "").displayName)
        assertEquals("视频", card("video").copy(name = "  ").displayName)
    }

    @Test
    fun typeLabelCoversRealMargTypes() {
        // 超星 mArg 的真实取值：作业/考试是 *id 后缀，不能把内部标识吐到界面。
        assertEquals("作业", cxTypeLabel("workid"))
        assertEquals("考试", cxTypeLabel("examid"))
        assertEquals("阅读", cxTypeLabel("readtask"))
        assertEquals("阅读", cxTypeLabel("readspeed"))
    }

    @Test
    fun unknownTypeNeverLeaksInternalIdentifier() {
        assertEquals("任务点", cxTypeLabel("custom"))
        assertEquals("任务点", cxTypeLabel(""))
        assertEquals("任务点", cxTypeLabel("live"))
    }
}