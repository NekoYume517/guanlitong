package com.sdmu.guanlitong.data.cx

import com.sdmu.guanlitong.data.CxPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 课程功能页的模块列表：内容、可用状态与点击后的目标页。 */
class CxCourseModuleTest {

    @Test
    fun moduleListStartsWithChaptersAndHasExpectedOrder() {
        assertEquals(listOf("chapters", "materials", "works"), cxCourseModules.map { it.id })
        assertEquals(listOf("章节刷课", "资料", "作业"), cxCourseModules.map { it.title })
    }

    @Test
    fun everyModuleHasDescriptionAndUniqueId() {
        assertTrue(cxCourseModules.all { it.description.isNotBlank() })
        assertEquals(cxCourseModules.size, cxCourseModules.map { it.id }.toSet().size)
    }

    @Test
    fun chaptersAndMaterialsAreTheAvailableModules() {
        val chapters = cxCourseModules.first { it.id == "chapters" }
        assertTrue(chapters.available)
        assertEquals(CxPage.CourseDetail, chapters.target)

        val materials = cxCourseModules.first { it.id == "materials" }
        assertTrue(materials.available)
        assertEquals(CxPage.Materials, materials.target)

        assertTrue(cxCourseModules.filter { it.id != "chapters" && it.id != "materials" }
            .none { it.available })
    }

    @Test
    fun worksIsStillExplicitlyDisabled() {
        val works = cxCourseModules.first { it.id == "works" }
        assertFalse(works.available)
        assertNull(works.target)
    }

    @Test
    fun tapsRouteOnlyIntoTargetPagesThatExist() {
        assertEquals(CxPage.CourseDetail, cxCourseModuleTarget("chapters"))
        assertEquals(CxPage.Materials, cxCourseModuleTarget("materials"))
        assertNull(cxCourseModuleTarget("works"))
        assertNull(cxCourseModuleTarget("not-a-module"))
    }
}