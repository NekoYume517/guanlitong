package com.sdmu.guanlitong.data.cx

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CxWidgetSummariesTest {

    // ── 收件箱 ─────────────────────────────────────────

    @Test
    fun `未读计数与最新三条标题`() {
        val notices = listOf(
            notice("n1", "签到", CxInboxType.CHECKIN, 300L, read = false),
            notice("n2", "作业", CxInboxType.WORK, 200L, read = false),
            notice("n3", "通知", CxInboxType.NOTICE, 100L, read = true),
            notice("n4", "旧通知", CxInboxType.NOTICE, 50L, read = false),
        )
        val summary = summarizeInbox(notices, syncedAtMs = 999L)
        assertEquals(3, summary.unread)
        assertEquals(listOf("签到", "作业", "通知"), summary.latest.map { it.title })
        assertEquals(3, summary.latest.size)
        assertTrue(summary.hasData)
    }

    @Test
    fun `未读按签到作业通知分组`() {
        val notices = listOf(
            notice("n1", "签到", CxInboxType.CHECKIN, 300L, read = false),
            notice("n2", "作业", CxInboxType.WORK, 200L, read = false),
            notice("n3", "互动", CxInboxType.QA, 100L, read = false),
            notice("n4", "通知", CxInboxType.NOTICE, 50L, read = true),
        )
        val groups = summarizeInbox(notices).unreadByGroup
        assertEquals(1, groups[CxInboxGroup.Checkin])
        assertEquals(1, groups[CxInboxGroup.Work])
        assertEquals(1, groups[CxInboxGroup.Notice])
    }

    @Test
    fun `未知通知类型归入通知`() {
        assertEquals(CxInboxGroup.Notice, inboxGroupOf("inbox_unknown"))
    }

    @Test
    fun `空收件箱没有数据`() {
        assertFalse(summarizeInbox(emptyList()).hasData)
    }

    @Test
    fun `最新标题最多三条`() {
        val notices = (1..6).map { notice("n$it", "标题$it", CxInboxType.NOTICE, it * 10L, read = false) }
        assertEquals(3, summarizeInbox(notices).latest.size)
    }

    // ── 签到剩余时间 ────────────────────────────────────

    @Test
    fun `剩余时间文案覆盖分钟小时与截止`() {
        assertEquals("", cxSignRemainingLabel(0L, 1_000L))
        assertEquals("已截止", cxSignRemainingLabel(1_000L, 2_000L))
        assertEquals("剩不到 1 分钟", cxSignRemainingLabel(30_000L, 1_000L))
        assertEquals("剩 12 分钟", cxSignRemainingLabel(12 * 60_000L, 0L))
        assertEquals("剩 1 小时", cxSignRemainingLabel(60 * 60_000L, 0L))
        assertEquals("剩 1 小时 30 分", cxSignRemainingLabel(90 * 60_000L, 0L))
    }

    @Test
    fun `签到快照只属于相同账号`() {
        val snapshot = CxSignSnapshot(accountKey = "a")
        assertTrue(snapshot.belongsTo("a"))
        assertFalse(snapshot.belongsTo("b"))
        assertFalse(snapshot.belongsTo(""))
    }

    @Test
    fun `进行中签到按结束时间排序`() {
        val snapshot = CxSignSnapshot(
            ongoing = listOf(
                CxSignBrief("1", "A", "普通", endsAtMs = 5_000L),
                CxSignBrief("2", "B", "位置", endsAtMs = 1_000L),
                CxSignBrief("3", "C", "手势", endsAtMs = 0L),
            ),
        )
        assertEquals(listOf("2", "1", "3"), snapshot.ongoingSorted().map { it.activeId })
    }

    // ── 快照持久化 ──────────────────────────────────────

    @Test
    fun `签到快照 JSON 往返保留全部字段`() {
        val original = CxSignSnapshot(
            accountKey = "acc",
            updatedAtMs = 123L,
            ongoing = listOf(CxSignBrief("a1", "课程", "位置", startsAtMs = 1_000L, endsAtMs = 5_000L)),
            lastResultTitle = "课程 · 签到",
            lastResultLabel = "成功",
            lastResultDetail = "120m",
            lastResultAtMs = 99L,
            restrictionUntilMs = 456L,
            restrictionMessage = "限制访问",
        )
        val decoded = CxSignSnapshotStore.decode(CxSignSnapshotStore.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `刷课快照 JSON 往返并计算进度`() {
        val original = CxStudySnapshot(
            updatedAtMs = 1L,
            running = true,
            courseName = "高数",
            phase = "播放",
            currentIndex = 3,
            total = 10,
            currentTask = "视频 3",
            position = 30,
            duration = 60,
            message = "播放中",
        )
        val decoded = CxStudySnapshotStore.decode(CxStudySnapshotStore.encode(original))
        assertEquals(original, decoded)
        assertEquals(0.5f, decoded.taskProgressRatio(), 0.001f)
        assertEquals(25, decoded.overallPercent)
    }

    @Test
    fun `旧版刷课快照缺字段时按默认值读取`() {
        val legacy = JSONObject().put("courseName", "旧课程").put("running", false)
        val decoded = CxStudySnapshotStore.decode(legacy)
        assertEquals("旧课程", decoded.courseName)
        assertEquals(0, decoded.position)
        assertEquals(0, decoded.duration)
    }

    private fun notice(
        id: String,
        title: String,
        type: CxInboxType,
        timeMs: Long,
        read: Boolean,
    ) = CxNoticeEntity(
        id = id,
        accountKey = "acc",
        ntype = type.ntype,
        title = title,
        content = "",
        timeMs = timeMs,
        localRead = read,
    )
}