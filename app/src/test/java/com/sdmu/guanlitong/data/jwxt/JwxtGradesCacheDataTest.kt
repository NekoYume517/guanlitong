package com.sdmu.guanlitong.data.jwxt

import com.sdmu.guanlitong.data.CacheShelf
import com.sdmu.guanlitong.data.CacheVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JwxtGradesCacheDataTest {

    private class MemoryShelf : CacheShelf {
        val values = mutableMapOf<String, String>()

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String) {
            values[key] = value
        }

        override fun clear() {
            values.clear()
        }
    }

    private val official = "https://jwxt.sdmu.edu.cn/jwglxt"
    private val grab = "https://jwxt.bansonic.top"

    private fun grade(name: String, semesterKey: String) = GradeItem(
        name = name,
        score = "88",
        credit = 3.0,
        point = 3.6,
        nature = "必修",
        type = "理论",
        teacher = "王老师",
        semester = "2025-2026 第一学期",
        semesterKey = semesterKey,
    )

    private fun store(shelf: CacheShelf = MemoryShelf()) =
        JwxtGradesCacheData(shelf, CacheVersion()) to shelf

    @Test
    fun `存 A 来源后读 B 来源为空`() {
        val (data, _) = store()
        data.save(official, listOf(grade("高等数学", "2025-1")))

        assertNotNull(data.peek(official))
        assertNull(data.peek(grab))
    }

    @Test
    fun `两个来源各存一份互不覆盖`() {
        val (data, _) = store()
        data.save(official, listOf(grade("高等数学", "2025-1")))
        data.save(grab, listOf(grade("线性代数", "2025-2")))

        assertEquals("高等数学", data.peek(official)?.grades?.single()?.name)
        assertEquals("线性代数", data.peek(grab)?.grades?.single()?.name)
    }

    @Test
    fun `save 后版本号递增 peek 不变`() {
        val (data, _) = store()
        assertEquals(0, data.version.value.value)

        data.save(official, listOf(grade("高等数学", "2025-1")))
        assertEquals(1, data.version.value.value)

        data.peek(official)
        data.peek(grab)
        assertEquals(1, data.version.value.value)

        data.save(official, emptyList())
        assertEquals(2, data.version.value.value)
    }

    @Test
    fun `落盘内容带 baseUrl 校验且按来源分 key`() {
        val shelf = MemoryShelf()
        val (data, _) = store(shelf)
        data.save(official, listOf(grade("高等数学", "2025-1")))

        val key = "grades_" + Integer.toHexString(official.hashCode())
        assertTrue(shelf.values.containsKey(key))
        assertTrue(shelf.values[key]!!.contains("\"baseUrl\":\"$official\""))

        // 直接把 A 的字节塞到 B 的 key 下，读取端必须拒绝，不能串数据。
        val grabKey = "grades_" + Integer.toHexString(grab.hashCode())
        shelf.values[grabKey] = shelf.values[key]!!
        val fresh = JwxtGradesCacheData(shelf, CacheVersion())
        assertNull(fresh.peek(grab))
    }

    @Test
    fun `相同来源重新 save 覆盖旧成绩`() {
        val (data, _) = store()
        data.save(official, listOf(grade("高等数学", "2025-1")))
        data.save(official, listOf(grade("大学物理", "2025-2"), grade("体育", "2025-2")))

        val cached = data.peek(official)
        assertEquals(2, cached?.grades?.size)
        assertEquals("大学物理", cached?.grades?.first()?.name)
    }
}