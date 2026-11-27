package com.sdmu.guanlitong.data.pu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PuSummaryTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `进行中与即将开始分别归类`() {
        val ongoing = activity(1, "进行中", now - 60_000L, now + 60_000L)
        val upcoming = activity(2, "马上开始", now + 60_000L, now + 120_000L)
        val ended = activity(3, "已结束", now - 120_000L, now - 60_000L)

        val summary = summarizePu(listOf(ongoing, upcoming, ended), 42.0, now)
        assertEquals(listOf("进行中"), summary.ongoing.map { it.name })
        assertEquals(listOf("马上开始"), summary.upcoming.map { it.name })
        assertEquals(listOf("已结束"), summary.latest.map { it.name })
        assertEquals(42.0, summary.totalCredit, 0.001)
        assertEquals("进行中", summary.headline?.name)
    }

    @Test
    fun `没有进行中和未来活动时回退最近结束`() {
        val older = activity(1, "更早", now - 300_000L, now - 200_000L)
        val newer = activity(2, "更近", now - 100_000L, now - 50_000L)
        val summary = summarizePu(listOf(older, newer), 10.0, now)
        assertEquals(listOf("更近", "更早"), summary.latest.map { it.name })
        assertEquals("更近", summary.headline?.name)
    }

    @Test
    fun `学时完成度按目标 clamp`() {
        val summary = summarizePu(emptyList(), 40.0, now, targetCredit = 100.0)
        assertEquals(0.4f, summary.creditRatio(), 0.001f)
        val full = summarizePu(emptyList(), 130.0, now, targetCredit = 100.0)
        assertEquals(1f, full.creditRatio(), 0.001f)
    }

    @Test
    fun `上游有分类字段时按分类占比`() {
        val a = activity(1, "A", now, now + 1_000L, typeName = "讲座")
        val b = activity(2, "B", now, now + 1_000L, typeName = "志愿服务")
        val c = activity(3, "C", now, now + 1_000L, typeName = "讲座")
        val slices = summarizePu(listOf(a, b, c), 3.0, now).categories
        assertEquals("讲座", slices.first().label)
        assertEquals(2, slices.first().count)
        assertEquals(2, slices.size)
    }

    @Test
    fun `没有分类字段时按活动状态分组`() {
        val ongoing = activity(1, "进行中", now - 1_000L, now + 1_000L)
        val ended = activity(2, "已结束", now - 9_000L, now - 5_000L)
        val slices = summarizePu(listOf(ongoing, ended), 2.0, now).categories
        assertEquals(setOf("进行中", "已结束"), slices.map { it.label }.toSet())
        assertTrue(slices.all { it.count == 1 })
    }

    private fun activity(
        id: Long,
        name: String,
        startMs: Long,
        endMs: Long,
        typeName: String? = null,
    ): PuActivity {
        val raw = JSONObject().apply { typeName?.let { put("typeName", it) } }
        return PuActivity(
            id = id,
            name = name,
            startTime = startMs.toString(),
            endTime = endMs.toString(),
            joinStartTime = "",
            joinEndTime = "",
            signStartTime = "",
            signOutStartTime = "",
            address = "",
            signPlace = "",
            credit = 1.0,
            canSignOutTime = 0,
            raw = raw,
        )
    }
}