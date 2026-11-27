package com.sdmu.guanlitong.data.pu

import com.sdmu.guanlitong.data.CacheShelf
import com.sdmu.guanlitong.data.CacheVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PuCacheDataTest {

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

    private fun activity(id: Long, name: String) = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("credit", 1.5)
        .toActivity()

    @Test
    fun `save 后能读到首页活动与总学时`() {
        val data = PuCacheData(MemoryShelf(), CacheVersion())
        data.save(listOf(activity(1, "志愿活动"), activity(2, "讲座")), 12.5)

        val cached = data.peek()
        assertEquals(12.5, cached?.totalCredit ?: 0.0, 0.0)
        assertEquals(listOf("志愿活动", "讲座"), cached?.activities?.map { it.name })
    }

    @Test
    fun `save 后版本号递增 peek 不变`() {
        val data = PuCacheData(MemoryShelf(), CacheVersion())
        assertEquals(0, data.version.value.value)

        data.save(emptyList(), 0.0)
        assertEquals(1, data.version.value.value)

        data.peek()
        assertEquals(1, data.version.value.value)

        data.save(listOf(activity(1, "志愿活动")), 2.0)
        assertEquals(2, data.version.value.value)
    }

    @Test
    fun `清空后读不到且版本号继续递增`() {
        val data = PuCacheData(MemoryShelf(), CacheVersion())
        data.save(listOf(activity(1, "志愿活动")), 2.0)
        data.clear()

        assertNull(data.peek())
        assertEquals(2, data.version.value.value)
    }

    @Test
    fun `新实例从同一份落盘恢复`() {
        val shelf = MemoryShelf()
        PuCacheData(shelf, CacheVersion()).save(listOf(activity(9, "社团活动")), 6.0)

        val reopened = PuCacheData(shelf, CacheVersion())
        assertEquals("社团活动", reopened.peek()?.activities?.single()?.name)
        assertEquals(6.0, reopened.peek()?.totalCredit ?: 0.0, 0.0)
    }
}