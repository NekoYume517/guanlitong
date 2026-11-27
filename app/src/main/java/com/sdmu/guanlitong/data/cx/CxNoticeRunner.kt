package com.sdmu.guanlitong.data.cx

import android.util.Log

/**
 * 收件箱同步的一轮编排：固定拉最新首页 → 按 `idCode` 去重落库 → 记录同步时间。
 *
 * 不传 `lastValue`：那是接口「取更早一页」的游标，拿来当同步游标会让每轮刷新都往
 * 历史翻，顶部新通知永远拉不到。`lastGetId` 只落库作诊断，不参与请求。
 *
 * 不做提醒/任务日志（已按收窄范围移除），诊断信息只走 Logcat。错误原样上抛：
 * Worker 负责 401/403 与网络重试策略，UI 手动刷新负责展示失败。
 */
class CxNoticeRunner(
    private val gateway: CxNoticeGateway,
    private val notices: CxNoticeLedger,
    private val accountKey: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** @return 本次真正新增的条目（新→旧），供后台静默通知统计。 */
    suspend fun poll(): List<CxNoticeEntity> {
        val page = gateway.inbox()
        if (!page.statusOk) {
            Log.w(TAG, "收件箱返回 status=false：${page.rawSnippet}")
        }

        val fresh = page.items.filterNot { notices.hasInbox(accountKey, it.idCode) }
        val added = notices.addNotices(accountKey, fresh.map { it.toEntity() })

        // 仅记录最新首页的 lastGetId 供排查；它不是翻页器，请求永远不带它。
        notices.setInboxCursor(accountKey, page.lastGetId)
        notices.setLastSyncedAt(accountKey, nowMs())
        Log.i(TAG, "收件箱同步完成：新增 ${added.size} 条（账号 $accountKey）")
        return added
    }

    private fun CxInboxItem.toEntity(): CxNoticeEntity = CxNoticeEntity(
        id = newNoticeId(),
        accountKey = accountKey,
        ntype = type.ntype,
        title = displayTitle,
        content = content,
        timeMs = insertTimeMs ?: nowMs(),
        remoteRead = remoteRead,
        // 超星端已读的条目不在本地冒充未读。
        localRead = remoteRead,
        cxInboxId = idCode,
        actionUrl = actionUrl.ifBlank { null },
    )

    private companion object {
        const val TAG = "CxNoticeRunner"
    }
}