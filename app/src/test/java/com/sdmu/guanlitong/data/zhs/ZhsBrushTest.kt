package com.sdmu.guanlitong.data.zhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷课纯算法与报文：期望值由参考实现 `zhs_server.py` 直接生成，
 * 规范、节奏、`sdsew` 混淆、`zwsds` 与 AES 密文都逐字节对齐。
 */
class ZhsBrushTest {

    private val chaptersBody = """
        {"chapterOrLesson":{"clList":[
          {"chapterId":11,"chapterName":"第一章","lessonTempDtos":[
            {"lessonId":101,"lessonName":"1.1 三级节","lessonVideoTempDtos":[
              {"lessonVideoName":"视频 A","lessonVideoId":1001,"lessonVideoSec":300,"watchStatus":1},
              {"lessonVideoName":"视频 B","lessonVideoId":1002,"lessonVideoSec":600,"watchStatus":0}
            ]},
            {"lessonId":102,"lessonName":"1.2 两级视频","videoId":555,"lessonSec":900,"watchStatus":0},
            {"lessonId":103,"lessonName":"1.3 讨论","videoId":0}
          ]}
        ]}}
    """.trimIndent()

    // ------------------------------------------------------------ 扁平条目

    @Test
    fun flatItemsFollowLevelRulesAndRealVideoMapping() {
        val chapters = ZhsLearnParser.parseChapters(chaptersBody)
        val items = ZhsLearnParser.flatItems(chapters, mapOf(101L to 1001L to 9001L))

        assertEquals(3, items.size)

        val first = items[0]
        assertEquals(11L, first.chapterId)
        assertEquals(101L, first.lessonId)
        assertEquals(1001L, first.lessonVideoId)
        assertEquals(9001L, first.videoId)
        assertEquals(300, first.sec)
        assertTrue(first.watched)

        // 没有映射时 videoId 回落 lessonVideoId。
        val second = items[1]
        assertEquals(1002L, second.lessonVideoId)
        assertEquals(1002L, second.videoId)
        assertEquals(600, second.sec)
        assertFalse(second.watched)

        // 两级课：lessonVideoId=0，videoId 用 lesson 自带值。
        val third = items[2]
        assertEquals(102L, third.lessonId)
        assertEquals(0L, third.lessonVideoId)
        assertEquals(555L, third.videoId)
        assertEquals(900, third.sec)
    }

    @Test
    fun flatItemsDropZeroLessonOrVideo() {
        val chapters = ZhsLearnParser.parseChapters(chaptersBody)
        assertTrue(ZhsLearnParser.flatItems(chapters).none { it.lessonId == 103L })
    }

    @Test
    fun finishedUsesWatchOrServerTime() {
        assertTrue(item(watched = true, sec = 100, studyTime = 0).finished)
        assertTrue(item(watched = false, sec = 100, studyTime = 100).finished)
        assertTrue(item(watched = false, sec = 100, studyTime = 150).finished)
        assertFalse(item(watched = false, sec = 100, studyTime = 99).finished)
        assertFalse(item(watched = false, sec = 0, studyTime = 0).finished)
    }

    private fun item(watched: Boolean, sec: Int, studyTime: Int) = ZhsFlatItem(
        chapterId = 11,
        lessonId = 101,
        lessonVideoId = 1001,
        videoId = 1001,
        sec = sec,
        name = "视频",
        studyTime = studyTime,
        watched = watched,
    )

    // ------------------------------------------------------------ 节奏规划

    @Test
    fun defaultPlanMatchesReference() {
        val plan = ZhsBrushPlanner.plan(300, ZhsSpeedChoice.DEFAULT)
        assertEquals(6, plan.calls)
        assertEquals(4.0, plan.intervalSec, 0.0)
        assertEquals(listOf(25, 75, 125, 175, 225, 275), (0 until plan.calls).map(plan::sessionAt))
        assertEquals("默认 · 6个请求", plan.label)
    }

    @Test
    fun defaultPlanShortVideoStillUsesOneCall() {
        val plan = ZhsBrushPlanner.plan(10, ZhsSpeedChoice.DEFAULT)
        assertEquals(1, plan.calls)
        assertEquals(listOf(15), (0 until plan.calls).map(plan::sessionAt))
        assertEquals("默认 · 1个请求", plan.label)

        val two = ZhsBrushPlanner.plan(60, ZhsSpeedChoice.DEFAULT)
        assertEquals(2, two.calls)
        assertEquals(listOf(15, 45), (0 until two.calls).map(two::sessionAt))
    }

    @Test
    fun customPlanSplitsByThirtySeconds() {
        val plan = ZhsBrushPlanner.plan(300, ZhsSpeedChoice.custom(2.0))
        assertEquals(10, plan.calls)
        assertEquals(15.0, plan.intervalSec, 0.0)
        assertEquals((1..10).map { it * 30 }, (0 until plan.calls).map(plan::sessionAt))
        assertEquals("自定义 2x · 10个请求", plan.label)
    }

    @Test
    fun customPlanClampsIntervalAndReportsEffectiveRate() {
        val slow = ZhsBrushPlanner.plan(300, ZhsSpeedChoice.custom(1.0))
        assertEquals(30.0, slow.intervalSec, 0.0)
        assertEquals("自定义 1x · 10个请求", slow.label)

        val fast = ZhsBrushPlanner.plan(6, ZhsSpeedChoice.custom(64.0))
        assertEquals(1, fast.calls)
        assertEquals(0.35, fast.intervalSec, 0.0)
        assertEquals(listOf(6), (0 until fast.calls).map(fast::sessionAt))
        assertEquals("自定义 64x · 1个请求 (实际≈17.1x)", fast.label)
    }

    @Test
    fun customRateIsClampedToSixtyFour() {
        assertEquals(64.0, ZhsSpeedChoice.custom(999.0).rate, 0.0)
        assertEquals(1.0, ZhsSpeedChoice.custom(0.1).rate, 0.0)
    }

    @Test
    fun replanContinuesFromAdvancedPosition() {
        val initial = ZhsBrushPlanner.plan(300, ZhsSpeedChoice.DEFAULT)
        // 已经报了 4 次，最后一次推进到 175 秒，剩 125 秒切 2x。
        val replanned = ZhsBrushPlanner.replan(300, 4, initial, ZhsSpeedChoice.custom(2.0))

        assertEquals(5, replanned.calls)
        assertEquals(12.5, replanned.intervalSec, 0.0)
        assertEquals(listOf(200, 225, 250, 275, 300), (0 until replanned.calls).map(replanned::sessionAt))
        assertEquals("自定义 2x · 5个请求", replanned.label)
    }

    @Test
    fun replanToDefaultUsesRemainingSeconds() {
        val initial = ZhsBrushPlanner.plan(300, ZhsSpeedChoice.custom(4.0))
        // 自定义 4x 已报 2 次（60 秒），剩下 240 秒切默认。
        val replanned = ZhsBrushPlanner.replan(300, 2, initial, ZhsSpeedChoice.DEFAULT)

        assertEquals(6, replanned.calls)
        assertEquals(4.0, replanned.intervalSec, 0.0)
        assertEquals(listOf(80, 120, 160, 200, 240, 280), (0 until replanned.calls).map(replanned::sessionAt))
    }

    @Test
    fun pyRoundIsBankersRounding() {
        assertEquals(0, ZhsBrushPlanner.pyRound(0.5))
        assertEquals(2, ZhsBrushPlanner.pyRound(1.5))
        assertEquals(2, ZhsBrushPlanner.pyRound(2.5))
        assertEquals(3, ZhsBrushPlanner.pyRound(2.51))
    }

    // ------------------------------------------------------------ 报文编码

    @Test
    fun zMatchesReferenceObfuscation() {
        assertEquals(
            "484840454f5b544b414144445b5f434a40454f5b55414a4b46415155484f4b444450544a4042414f0b06191e15120e0217",
            ZhsBrushCodec.z(
                listOf(2201, 101, 1001, 9001, 11, 0, 25, 125, "00:00:25", "abcdefzhs"),
            ),
        )
    }

    @Test
    fun ewsswMatchesReference() {
        assertEquals("0,1,100,150,300,300", ZhsBrushCodec.ewssw(300))
        assertEquals("0,1,1,2,2,2", ZhsBrushCodec.ewssw(2))
        assertEquals("0,1,1,2,5,5", ZhsBrushCodec.ewssw(5))
    }

    @Test
    fun zwsdsIsBase64OfId() {
        assertEquals("MTI1MzM1Mjg3NTg=", ZhsBrushCodec.zwsds(12_533_528_758L))
        assertNull(ZhsBrushCodec.zwsds(0))
    }

    @Test
    fun uuidComesFromCaslogcElseFallback() {
        val cookie = "%7B%22uuid%22%3A%22abc-123%22%7D"
        assertEquals("abc-123zhs", ZhsBrushCodec.uuid(cookie))
        assertEquals(ZhsBrushCodec.FALLBACK_UUID, ZhsBrushCodec.uuid(null))
        assertEquals(ZhsBrushCodec.FALLBACK_UUID, ZhsBrushCodec.uuid("not-json"))
    }

    @Test
    fun brushPayloadMatchesReferenceJson() {
        assertEquals(
            "{\"ewssw\":\"0,1,100,150,300,300\",\"sdsew\":\"abcd\",\"zwsds\":\"MTI1MzM1Mjg3NTg=\"," +
                "\"courseId\":1101,\"dateFormate\":1700000000000}",
            ZhsBrushCodec.brushPayload(1101, "0,1,100,150,300,300", "abcd", "MTI1MzM1Mjg3NTg=", 1_700_000_000_000L),
        )
    }

    @Test
    fun brushPayloadAesMatchesReferenceCipher() {
        val payload = ZhsBrushCodec.brushPayload(
            courseId = 1101,
            ewssw = "0,1,100,150,300,300",
            sdsew = "484840454f5b544b414144445b5f434a40454f5b55414a4b46415155484f4b444450544a4042414f0b06191e15120e0217",
            zwsds = "MTI1MzM1Mjg3NTg=",
            date = 1_700_000_000_000L,
        )
        assertEquals(
            "6mlEHTmaDjL1CPFv2MWrOucjGhTJPuCCYuG8CJFtM8xnuIOFyExiY3xisFLmHuSXcC7Rjw/Al3IeMXs+9hKrQg" +
                "xGOQ4PgH4YXw0Z8O444V8s9NbVzZ3Uu/w39VHnLJd8mMGDZ310Xk7U2JNkU2tVLkI4NivkFcDKg2urQpLcN" +
                "U1uraXDmR8i8wX52DsiomRBLDOEmd3l5swf+AJPTFseDige9qwzjOItPsl86hksau7Rp4SjsEjMt0WrRuRt" +
                "nv+u6Iv4o3P6U3Pee2ILdvFKGou/GnT7Z+VpU/7tQN6VzYU=",
            ZhsCrypto.aesEncrypt(payload, "0123456789abcdef"),
        )
    }
}