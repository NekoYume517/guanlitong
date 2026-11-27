package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 九宫格手势的纯算法：命中、重复点过滤、越界、单点与 signCode 拼装。
 *
 * 棋盘边长 300 → 格边长 100，点的中心依次在 (50,50)、(150,50)…(250,250)。
 */
class CxGesturePatternTest {

    private val board = 300f

    @Test
    fun pointsAreNumberedInReadingOrder() {
        for (index in 1..CxGesturePattern.POINTS) {
            val column = (index - 1) % 3
            val row = (index - 1) / 3
            val x = (column + 0.5f) * 100f
            val y = (row + 0.5f) * 100f
            assertEquals("点 $index 的中心应命中自己", index, CxGesturePattern.hit(x, y, board))
        }
    }

    @Test
    fun gapBetweenDotsAndEdgesHitNothing() {
        assertNull("四点中间的空隙", CxGesturePattern.hit(100f, 100f, board))
        assertNull("棋盘左侧越界", CxGesturePattern.hit(-1f, 50f, board))
        assertNull("棋盘上侧越界", CxGesturePattern.hit(50f, -0.5f, board))
        assertNull("棋盘右边界", CxGesturePattern.hit(board, 50f, board))
        assertNull("棋盘下边界", CxGesturePattern.hit(50f, board, board))
        assertNull("边长为 0 时不命中", CxGesturePattern.hit(50f, 50f, 0f))
    }

    @Test
    fun repeatedPointIsIgnored() {
        assertEquals(listOf(1, 2), CxGesturePattern.append(listOf(1), 2))
        assertEquals("同一只手势不允许重点", listOf(1, 2), CxGesturePattern.append(listOf(1, 2), 2))
        assertEquals(listOf(1), CxGesturePattern.append(listOf(1), 1))
    }

    @Test
    fun invalidPointIsIgnored() {
        assertEquals(listOf(1), CxGesturePattern.append(listOf(1), 0))
        assertEquals(listOf(1), CxGesturePattern.append(listOf(1), 10))
        assertEquals(listOf(1), CxGesturePattern.append(listOf(1), null))
        assertEquals(emptyList<Int>(), CxGesturePattern.append(emptyList(), null))
    }

    @Test
    fun codeIsDigitsInOrder() {
        assertEquals("12369", CxGesturePattern.code(listOf(1, 2, 3, 6, 9)))
    }

    @Test
    fun singlePointStillProducesItsCode() {
        assertEquals("5", CxGesturePattern.code(listOf(5)))
        assertEquals("", CxGesturePattern.code(emptyList()))
    }

    @Test
    fun draggingAcrossCentersBuildsThePattern() {
        // 模拟手指依次划过的坐标：1 → 2 → 3 → 6 → 9，中间夹着空隙的采样点。
        val path = listOf(
            50f to 50f,
            100f to 100f, // 空隙，不该加点
            150f to 50f,
            250f to 50f,
            250f to 150f,
            250f to 250f,
        )
        var sequence = emptyList<Int>()
        for ((x, y) in path) {
            sequence = CxGesturePattern.append(
                sequence,
                CxGesturePattern.hit(x, y, board),
            )
        }
        assertEquals(listOf(1, 2, 3, 6, 9), sequence)
        assertEquals("12369", CxGesturePattern.code(sequence))
    }
}