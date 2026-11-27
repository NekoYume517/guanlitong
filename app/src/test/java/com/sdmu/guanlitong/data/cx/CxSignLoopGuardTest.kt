package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签到轮询启停去重：App 启动、课程页、设置页都会请求「确保在跑」，
 * 同一时刻只能有一条轮询协程。
 */
class CxSignLoopGuardTest {

    @Test
    fun secondStartRequestIsRejectedUntilLoopEnds() {
        val guard = CxSignLoopGuard()

        assertTrue("第一次请求启动", guard.begin())
        assertTrue(guard.isRunning())
        assertFalse("已经开着时不能再起一条", guard.begin())
        assertFalse(guard.begin())

        guard.end()
        assertFalse(guard.isRunning())
        assertTrue("上一轮结束后允许重新启动", guard.begin())
    }

    @Test
    fun endIsIdempotent() {
        val guard = CxSignLoopGuard()

        assertFalse("从未启动时 end 无副作用", guard.isRunning())
        guard.end()
        assertTrue(guard.begin())
        guard.end()
        guard.end()
        assertTrue(guard.begin())
    }
}