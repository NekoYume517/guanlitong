package com.sdmu.guanlitong.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 学习通章节刷课同步进共享任务表：开始注册、更新覆盖、结束移除。 */
class CxTaskStatusRegistryTest {

    @Before
    fun setUp() {
        BrushTaskRegistry.clear()
        CxTaskStatus.reset()
    }

    @After
    fun tearDown() {
        CxTaskStatus.reset()
        BrushTaskRegistry.clear()
    }

    @Test
    fun startRegistersCxStudyTask() {
        CxTaskStatus.start("高等数学", "扫描章节")

        val task = BrushTaskRegistry.tasks.value.single()
        assertEquals("cx.study", task.id)
        assertEquals(BrushTaskSource.CX_STUDY, task.source)
        assertEquals("学习通", task.source.label)
        assertEquals("高等数学", task.title)
        assertEquals(BrushTaskRoutes.CX_STUDY, task.route)
        assertTrue(task.running)
    }

    @Test
    fun updateRefreshesProgressAndSubtitle() {
        CxTaskStatus.start("高等数学", "刷课")
        CxTaskStatus.update {
            it.copy(currentIndex = 3, total = 10, currentTask = "第3节 极限", position = 30, duration = 60)
        }

        val task = BrushTaskRegistry.tasks.value.single()
        assertEquals("第3节 极限", task.subtitle)
        assertEquals(0.2f, task.progress)
        assertEquals("3/10", task.statusText)
    }

    @Test
    fun finishRemovesTaskFromRegistry() {
        CxTaskStatus.start("高等数学", "刷课")
        CxTaskStatus.finish(message = "完成")

        assertEquals(0, BrushTaskRegistry.tasks.value.size)
        assertTrue(!CxTaskStatus.state.value.running)
    }
}