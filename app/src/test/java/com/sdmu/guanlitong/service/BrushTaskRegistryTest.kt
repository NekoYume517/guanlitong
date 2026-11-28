package com.sdmu.guanlitong.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 多任务注册表：注册/更新/移除、徽标计数、排序与单任务直达。 */
class BrushTaskRegistryTest {

    @Before
    fun setUp() = BrushTaskRegistry.clear()

    @After
    fun tearDown() = BrushTaskRegistry.clear()

    private fun task(
        id: String,
        source: BrushTaskSource = BrushTaskSource.ZHS_STUDY,
        running: Boolean = true,
        startedAt: Long = 0,
        progress: Float = 0.2f,
        route: String = "",
    ) = BrushTaskSnapshot(
        id = id,
        source = source,
        title = "任务 $id",
        subtitle = "副标题",
        progress = progress,
        statusText = "50%",
        running = running,
        startedAt = startedAt,
        route = route,
    )

    @Test
    fun registerKeepsTasksFromDifferentSources() {
        BrushTaskRegistry.register(task("cx", BrushTaskSource.CX_STUDY, route = BrushTaskRoutes.CX_STUDY))
        BrushTaskRegistry.register(task("zhs", BrushTaskSource.ZHS_STUDY, route = BrushTaskRoutes.ZHS_STUDY))

        val tasks = BrushTaskRegistry.tasks.value
        assertEquals(2, tasks.size)
        assertEquals(setOf("cx", "zhs"), tasks.map { it.id }.toSet())
    }

    @Test
    fun registerSameIdReplacesSnapshot() {
        BrushTaskRegistry.register(task("zhs", progress = 0.1f))
        BrushTaskRegistry.register(task("zhs", progress = 0.7f))

        assertEquals(1, BrushTaskRegistry.tasks.value.size)
        assertEquals(0.7f, BrushTaskRegistry.tasks.value.single().progress)
    }

    @Test
    fun updateOnlyTouchesRegisteredTask() {
        BrushTaskRegistry.register(task("zhs", progress = 0.1f))
        BrushTaskRegistry.update(task("zhs", progress = 0.4f))
        BrushTaskRegistry.update(task("missing", progress = 0.9f))

        val tasks = BrushTaskRegistry.tasks.value
        assertEquals(1, tasks.size)
        assertEquals(0.4f, tasks.single().progress)
    }

    @Test
    fun unregisterRemovesOnlyThatTask() {
        BrushTaskRegistry.register(task("cx"))
        BrushTaskRegistry.register(task("zhs"))

        BrushTaskRegistry.unregister("cx")

        assertEquals(listOf("zhs"), BrushTaskRegistry.tasks.value.map { it.id })
        BrushTaskRegistry.unregister("zhs")
        assertEquals(0, BrushTaskRegistry.tasks.value.size)
    }

    @Test
    fun badgeCountsActiveOrProgressedTasks() {
        val tasks = listOf(
            task("a", running = true, progress = 0f),
            task("b", running = false, progress = 0.5f),
            task("c", running = false, progress = 0f),
        )
        assertEquals(2, BrushTaskRegistry.badgeCount(tasks))
    }

    @Test
    fun runningTasksSortBeforeFinishedThenByStartTime() {
        BrushTaskRegistry.register(task("late", running = true, startedAt = 300))
        BrushTaskRegistry.register(task("early", running = true, startedAt = 100))
        BrushTaskRegistry.register(task("done-old", running = false, startedAt = 50))
        BrushTaskRegistry.register(task("done-new", running = false, startedAt = 400))

        assertEquals(
            listOf("early", "late", "done-old", "done-new"),
            BrushTaskRegistry.tasks.value.map { it.id },
        )
    }

    @Test
    fun singleTaskOpensDirectly() {
        assertNull(BrushTaskRegistry.directTarget(emptyList()))
        BrushTaskRegistry.register(task("zhs", route = BrushTaskRoutes.ZHS_STUDY))
        assertEquals("zhs.study", BrushTaskRegistry.directTarget(BrushTaskRegistry.tasks.value)?.route)
        BrushTaskRegistry.register(task("cx"))
        assertNull(BrushTaskRegistry.directTarget(BrushTaskRegistry.tasks.value))
    }
}