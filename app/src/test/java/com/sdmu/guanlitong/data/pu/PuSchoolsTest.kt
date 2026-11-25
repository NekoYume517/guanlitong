package com.sdmu.guanlitong.data.pu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学校列表的本地筛选与排序：字母查询匹配 `display_order`（官方客户端同款），
 * 中文查询匹配校名；空查询按官方顺序全量返回。
 */
class PuSchoolsTest {

    private val schools = listOf(
        PuSchool(goId = "1", name = "山东管理学院", displayOrder = "SDGLXY"),
        PuSchool(goId = "2", name = "山东大学", displayOrder = "SDDX"),
        PuSchool(goId = "3", name = "北京大学", displayOrder = "BJDX"),
    )

    @Test
    fun blank_query_returns_all_sorted() {
        val result = filterSchools(schools, "  ")
        assertEquals(schools.size, result.size)
        assertEquals(listOf("BJDX", "SDDX", "SDGLXY"), result.map { it.displayOrder })
    }

    @Test
    fun letters_match_display_order() {
        val result = filterSchools(schools, "sdgl")
        assertEquals(listOf("山东管理学院"), result.map { it.name })
    }

    @Test
    fun chinese_matches_name() {
        val result = filterSchools(schools, "山东")
        assertEquals(listOf("山东大学", "山东管理学院"), result.map { it.name })
    }

    @Test
    fun unknown_query_is_empty() {
        assertTrue(filterSchools(schools, "清华大学").isEmpty())
    }

    @Test
    fun group_falls_back_to_hash_for_numeric_order() {
        assertEquals("S", schools[0].group)
        assertEquals("#", PuSchool(goId = "9", name = "测试", displayOrder = "12").group)
    }
}