package com.sdmu.guanlitong.data.cx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「最近记录」的追加、去重、200 条上限与编码解码：
 * 历史只用于展示，不能影响去重台账，也不能被名字里的分隔符带偏。
 */
class CxSignHistoryTest {

    private fun entry(
        activeId: String = "a1",
        kind: CxSignKind = CxSignKind.CHECKIN,
        result: CxSignHistoryResult = CxSignHistoryResult.SUCCESS,
        atMs: Long = 1_000,
        courseName: String = "高等数学",
        activeName: String = "课堂签到",
        courseId: String = "c1",
        clazzId: String = "k1",
        detail: String = "",
    ) = CxSignHistoryEntry(
        activeId = activeId,
        kind = kind,
        result = result,
        atMs = atMs,
        courseName = courseName,
        activeName = activeName,
        courseId = courseId,
        clazzId = clazzId,
        detail = detail,
    )

    @Test
    fun appendsNewestFirst() {
        val first = appendSignHistory(emptyList(), entry(activeId = "a1", atMs = 1))
        val second = appendSignHistory(first, entry(activeId = "a2", atMs = 2))

        assertEquals(listOf("a2", "a1"), second.map { it.activeId })
    }

    @Test
    fun sameActivityKindAndResultIsWrittenOnce() {
        val once = appendSignHistory(emptyList(), entry())
        val twice = appendSignHistory(once, entry(atMs = 9_000, activeName = "改名后的活动"))

        assertEquals(1, twice.size)
        assertEquals("重复事件保留最早写入的那条", 1_000L, twice.single().atMs)
    }

    @Test
    fun differentResultOrKindIsKept() {
        var list = appendSignHistory(emptyList(), entry(result = CxSignHistoryResult.MANUAL))
        list = appendSignHistory(list, entry(result = CxSignHistoryResult.SUCCESS))
        list = appendSignHistory(
            list,
            entry(kind = CxSignKind.CHECKOUT, result = CxSignHistoryResult.SUCCESS),
        )

        assertEquals(
            "先需手动后成功、签到和签退都要保留",
            listOf(
                CxSignKind.CHECKOUT to CxSignHistoryResult.SUCCESS,
                CxSignKind.CHECKIN to CxSignHistoryResult.SUCCESS,
                CxSignKind.CHECKIN to CxSignHistoryResult.MANUAL,
            ),
            list.map { it.kind to it.result },
        )
    }

    @Test
    fun capsAtTwoHundredNewest() {
        var list = emptyList<CxSignHistoryEntry>()
        repeat(CX_SIGN_HISTORY_LIMIT + 5) { index ->
            list = appendSignHistory(list, entry(activeId = "a$index", atMs = index.toLong()))
        }

        assertEquals(CX_SIGN_HISTORY_LIMIT, list.size)
        assertEquals("a${CX_SIGN_HISTORY_LIMIT + 4}", list.first().activeId)
        assertEquals("a5", list.last().activeId)
    }

    @Test
    fun encodeDecodeRoundTripsAwkwardNames() {
        val original = listOf(
            entry(
                activeName = "签到|带竖线",
                courseName = "第一行\n第二行",
                activeId = "a|1",
            ),
            entry(activeId = "a2", result = CxSignHistoryResult.ENDED, atMs = 2_000),
        )

        assertEquals(original, decodeSignHistory(encodeSignHistory(original)))
    }

    @Test
    fun malformedLinesAreDroppedNotFatal() {
        val raw = listOf(
            "",
            "only|two",
            "a1|NOT_A_KIND|SUCCESS|1000",
            "a1|CHECKIN|NOT_A_RESULT|1000",
            "a1|CHECKIN|SUCCESS|not-a-number",
            "a2|CHECKIN|SUCCESS|2000|%E9%AB%98%E7%AD%89%E6%95%B0%E5%AD%A6|%E7%AD%BE%E5%88%B0||k1",
            "a3|CHECKIN|FAILED|3000|%ZZ-bad-encoding|",
        ).joinToString("\n")

        val decoded = decodeSignHistory(raw)

        assertEquals("坏行全部跳过，只留合法行", listOf("a2", "a3"), decoded.map { it.activeId })
        assertEquals("高等数学", decoded.first().courseName)
        assertEquals("k1", decoded.first().clazzId)
        assertEquals("缺字段或坏转义退化为空串", "", decoded.last().clazzId)
    }

    @Test
    fun manualSuccessAndFailureWriteBackWithDetail() {
        val success = signResultHistory(
            activeId = "a1",
            kind = CxSignKind.CHECKIN,
            result = CxSignHistoryResult.SUCCESS,
            atMs = 10,
            courseName = "高等数学",
            activeName = "位置签到",
            courseId = "c1",
            clazzId = "k1",
            detail = "位置签到成功（偏移 9m）",
        )
        val failure = signResultHistory(
            activeId = "a2",
            kind = CxSignKind.CHECKIN,
            result = CxSignHistoryResult.FAILED,
            atMs = 11,
            detail = "不在签到范围",
        )

        assertEquals("位置签到成功（偏移 9m）", success.detail)
        assertEquals("失败原因保留", "不在签到范围", failure.detail)
        assertEquals(
            "编码回环保留附言",
            listOf(success, failure),
            decodeSignHistory(encodeSignHistory(listOf(success, failure))),
        )
    }

    @Test
    fun successfulCheckinIdsOnlySuccessCheckins() {
        val history = listOf(
            entry(activeId = "ok", result = CxSignHistoryResult.SUCCESS),
            entry(activeId = "checkout", kind = CxSignKind.CHECKOUT, result = CxSignHistoryResult.SUCCESS),
            entry(activeId = "manual", result = CxSignHistoryResult.MANUAL),
            entry(activeId = "failed", result = CxSignHistoryResult.FAILED),
            entry(activeId = "", result = CxSignHistoryResult.SUCCESS),
        )

        assertEquals(setOf("ok"), successfulCheckinIds(history))
    }

    @Test
    fun gestureSuccessBuildsCheckinHistory() {
        val entry = gestureSuccessHistory(
            activeId = "a9",
            courseId = "c9",
            clazzId = "k9",
            courseName = "大学英语",
            activeName = "手势签到",
            nowMs = 42,
        )

        assertEquals(CxSignKind.CHECKIN, entry.kind)
        assertEquals(CxSignHistoryResult.SUCCESS, entry.result)
        assertEquals(42L, entry.atMs)
        assertEquals("大学英语 · 手势签到", entry.displayTitle)
        assertEquals("c9", entry.courseId)
        assertEquals("k9", entry.clazzId)

        val fromNotification = gestureSuccessHistory("a9", "", "", "", "", 43)
        assertEquals("学习通签到", fromNotification.displayTitle)
        assertEquals("", fromNotification.courseId)
    }

    @Test
    fun displayTitleFallsBackToAppName() {
        assertEquals("高等数学 · 课堂签到", entry().displayTitle)
        assertEquals("高等数学", entry(activeName = "").displayTitle)
        assertTrue("全空时不能出现空标题", entry(courseName = "", activeName = "").displayTitle.isNotBlank())
    }
}