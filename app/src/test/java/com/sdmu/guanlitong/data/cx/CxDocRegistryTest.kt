package com.sdmu.guanlitong.data.cx

import com.sdmu.guanlitong.service.BrushTaskRegistry
import com.sdmu.guanlitong.service.BrushTaskRoutes
import com.sdmu.guanlitong.service.BrushTaskSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 资料任务与共享刷课注册表的对接：运行中注册、进度换算、结束移除。
 *
 * 注册表是进程单例，每个用例前后都清空，避免与其它刷课来源互相干扰。
 */
class CxDocRegistryTest {

    private val course = CxDocCourse(
        courseId = "c1",
        clazzId = "k1",
        personId = "p1",
        cpi = "cpi1",
        enc = "enc1",
        name = "高等数学",
    )

    private val item = CxDocItem(
        name = "讲义.pdf",
        type = "doc",
        id = "d1",
        objectId = "oid1",
    )

    @Before
    fun setUp() {
        BrushTaskRegistry.clear()
    }

    @After
    fun tearDown() {
        CxDocTaskStatus.finish("测试收尾")
        BrushTaskRegistry.clear()
    }

    @Test
    fun registersRunningMaterialTaskWithCourseContext() {
        CxDocTaskStatus.start(course, item, speed = 10.0, pageNum = 20, nowMs = 1_000L)

        val task = BrushTaskRegistry.tasks.value.single()
        assertEquals("cx.material.oid1", task.id)
        assertEquals(BrushTaskSource.CX_MATERIAL, task.source)
        assertEquals("高等数学 · 讲义.pdf", task.title)
        assertTrue(task.subtitle.contains("0.0 分钟"))
        assertTrue(task.subtitle.contains("20 页"))
        assertTrue(task.subtitle.contains("10x"))
        assertEquals(0f, task.progress, 0.0001f)
        assertEquals("准备中", task.statusText)
        assertTrue(task.running)
        assertEquals(1_000L, task.startedAt)
        assertEquals(BrushTaskRoutes.CX_MATERIAL, task.route)
    }

    @Test
    fun progressIsHeightOverPagesTimesHundredAndClamped() {
        CxDocTaskStatus.start(course, item, speed = 1.0, pageNum = 10)
        CxDocTaskStatus.update { it.copy(height = 250) }
        assertEquals(0.25f, BrushTaskRegistry.tasks.value.single().progress, 0.0001f)

        CxDocTaskStatus.update { it.copy(height = 9_999) }
        assertEquals(1f, BrushTaskRegistry.tasks.value.single().progress, 0.0001f)

        CxDocTaskStatus.update { it.copy(pageNum = 0, maxHeight = 0, height = 0) }
        assertEquals(0f, BrushTaskRegistry.tasks.value.single().progress, 0.0001f)
    }

    @Test
    fun finishRemovesTaskFromRegistry() {
        CxDocTaskStatus.start(course, item, speed = 1.0, pageNum = 10)
        CxDocTaskStatus.update { it.copy(reportedSeconds = 90) }
        assertEquals(1, BrushTaskRegistry.tasks.value.size)

        CxDocTaskStatus.finish("已停止刷时长", fatalError = null)

        assertTrue(BrushTaskRegistry.tasks.value.isEmpty())
        assertEquals(0, BrushTaskRegistry.badgeCount(BrushTaskRegistry.tasks.value))
    }

    @Test
    fun idFallsBackToDataIdAndNewFileReplacesPrevious() {
        CxDocTaskStatus.start(course, item.copy(objectId = ""), speed = 1.0, pageNum = 10)
        assertEquals("cx.material.d1", BrushTaskRegistry.tasks.value.single().id)

        CxDocTaskStatus.start(
            course,
            item.copy(name = "习题.pdf", id = "d2", objectId = "oid2"),
            speed = 1.0,
            pageNum = 10,
        )
        val task = BrushTaskRegistry.tasks.value.single()
        assertEquals("cx.material.oid2", task.id)
        assertEquals("高等数学 · 习题.pdf", task.title)
    }
}