package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收件箱同步：固定拉最新首页、去重、已读镜像、status falsy 空页、错误上抛。
 *
 * 「永远拉最新」是这里的核心语义：重复同一页不能再有新增，接口若带 `lastValue`
 * 就会变成每轮往后翻历史，新通知永远进不来。
 */
class CxNoticeRunnerTest {

    private val now = 1_000_000_000L

    private fun item(id: String, read: Boolean = false) = CxInboxItem(
        idCode = id,
        type = CxInboxType.CHECKIN,
        title = "签到$id",
        content = "内容",
        creator = "老师",
        remoteRead = read,
        insertTimeMs = now - 1_000,
        actionUrl = "https://mobilelearn.chaoxing.com/page/active/show?id=$id",
        logoUrl = "",
    )

    private class FakeGateway : CxNoticeGateway {
        var page = CxInboxPage(emptyList(), "", true)
        /** 依次返回的页面；空了就回落到 [page]。 */
        val nextPages = mutableListOf<CxInboxPage>()
        var error: CxException? = null
        var calls = 0

        override suspend fun inbox(): CxInboxPage {
            calls++
            error?.let { throw it }
            return if (nextPages.isNotEmpty()) nextPages.removeAt(0) else page
        }
    }

    private class FakeLedger : CxNoticeLedger {
        val byAccount = mutableMapOf<String, MutableList<CxNoticeEntity>>()
        val cursors = mutableMapOf<String, String>()
        val synced = mutableMapOf<String, Long>()

        private fun list(accountKey: String) =
            byAccount.getOrPut(accountKey) { mutableListOf() }

        override fun notices(accountKey: String) = list(accountKey).toList()

        override fun hasInbox(accountKey: String, idCode: String) =
            list(accountKey).any { it.cxInboxId == idCode }

        override fun addNotices(accountKey: String, items: List<CxNoticeEntity>): List<CxNoticeEntity> {
            val target = list(accountKey)
            val seen = target.mapNotNull { it.cxInboxId }.toMutableSet()
            val added = items.filter { it.cxInboxId == null || seen.add(it.cxInboxId) }
            target.addAll(0, added)
            return added
        }

        override fun markRead(accountKey: String, id: String) = Unit
        override fun markAllRead(accountKey: String) = Unit
        override fun clearRead(accountKey: String) = Unit
        override fun delete(accountKey: String, id: String) = Unit
        override fun clear(accountKey: String) = Unit

        override fun inboxCursor(accountKey: String) = cursors[accountKey].orEmpty()
        override fun setInboxCursor(accountKey: String, cursor: String) {
            cursors[accountKey] = cursor
        }

        override fun lastSyncedAt(accountKey: String) = synced[accountKey] ?: 0
        override fun setLastSyncedAt(accountKey: String, atMs: Long) {
            synced[accountKey] = atMs
        }
    }

    private fun runner(gateway: FakeGateway, ledger: FakeLedger) =
        CxNoticeRunner(gateway, ledger, "u1", { now })

    @Test
    fun addsNewItemsAndSkipsDuplicates() = runBlocking {
        val gateway = FakeGateway().apply {
            page = CxInboxPage(listOf(item("a"), item("b", read = true)), "cursor-1", true)
        }
        val ledger = FakeLedger()
        val runner = runner(gateway, ledger)

        val first = runner.poll()
        assertEquals(2, first.size)
        assertEquals("cursor-1", ledger.inboxCursor("u1"))
        assertEquals(now, ledger.lastSyncedAt("u1"))
        // 超星端已读的条目不在本地冒充未读。
        assertTrue(ledger.notices("u1").first { it.cxInboxId == "b" }.localRead)

        val second = runner.poll()
        assertEquals(0, second.size)
        assertEquals(2, ledger.notices("u1").size)
    }

    @Test
    fun repeatedLatestPageAddsNothing() = runBlocking {
        val gateway = FakeGateway().apply {
            page = CxInboxPage(listOf(item("a"), item("b")), "", true)
        }
        val ledger = FakeLedger()
        val runner = runner(gateway, ledger)

        assertEquals(2, runner.poll().size)
        // 最新首页不变：再刷、再刷都不该再有新增，更不能每轮翻更早一页。
        assertEquals(0, runner.poll().size)
        assertEquals(0, runner.poll().size)
        assertEquals(2, ledger.notices("u1").size)
        assertEquals(3, gateway.calls)
    }

    @Test
    fun laterLatestPageOnlyAddsNewItems() = runBlocking {
        val gateway = FakeGateway().apply {
            nextPages += CxInboxPage(listOf(item("a"), item("b")), "", true)
            nextPages += CxInboxPage(listOf(item("c"), item("a"), item("b")), "", true)
        }
        val ledger = FakeLedger()
        val runner = runner(gateway, ledger)

        assertEquals(2, runner.poll().size)
        val second = runner.poll()
        assertEquals(1, second.size)
        assertEquals("c", second.single().cxInboxId)
        assertEquals(3, ledger.notices("u1").size)
    }

    @Test
    fun falsyStatusIsTreatedAsEmptyPage() = runBlocking {
        val gateway = FakeGateway().apply {
            page = CxInboxPage(emptyList(), "", false, statusOk = false, rawSnippet = "暂无数据")
        }
        val added = runner(gateway, FakeLedger()).poll()
        assertTrue(added.isEmpty())
    }

    @Test
    fun errorsArePropagated() {
        val gateway = FakeGateway().apply { error = CxException("登录已失效", CX_CODE_AUTH) }
        assertThrows(CxException::class.java) {
            runBlocking { runner(gateway, FakeLedger()).poll() }
        }
    }

    @Test
    fun accountsAreIsolated() = runBlocking {
        val gateway = FakeGateway().apply {
            page = CxInboxPage(listOf(item("a")), "", true)
        }
        val ledger = FakeLedger()
        CxNoticeRunner(gateway, ledger, "u1", { now }).poll()
        // 同一条消息在另一个账号下仍算新增（去重键带 accountKey）。
        val second = CxNoticeRunner(gateway, ledger, "u2", { now }).poll()
        assertEquals(1, second.size)
        assertEquals(1, ledger.notices("u2").size)
    }
}