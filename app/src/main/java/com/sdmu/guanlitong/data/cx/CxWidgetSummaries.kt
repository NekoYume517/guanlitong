package com.sdmu.guanlitong.data.cx

/** 收件箱标题摘要，供概览 / 桌面小组件展示。 */
data class CxNoticeBrief(
    val title: String,
    val atMs: Long,
    val ntype: String = "",
)

/** 收件箱计数的三个大类，堆叠条按它们着色。 */
enum class CxInboxGroup(val label: String) {
    Checkin("签到"),
    Work("作业"),
    Notice("通知"),
}

/** 收件箱本地快照派生出的摘要：未读数 + 最新几条标题 + 按大类计数。 */
data class CxInboxSummary(
    val unread: Int,
    val latest: List<CxNoticeBrief>,
    val syncedAtMs: Long = 0,
    /** 未读消息按签到/作业/通知三类计数，堆叠条用。 */
    val unreadByGroup: Map<CxInboxGroup, Int> = emptyMap(),
) {
    val hasData: Boolean get() = latest.isNotEmpty() || syncedAtMs > 0
}

fun inboxGroupOf(ntype: String): CxInboxGroup = when (typeOfNtype(ntype)) {
    CxInboxType.CHECKIN -> CxInboxGroup.Checkin
    CxInboxType.WORK -> CxInboxGroup.Work
    else -> CxInboxGroup.Notice
}

/**
 * 从本地收件箱条目算出未读数与最新标题。
 *
 * 只读本地库，不发请求；未读判定与收件箱页面一致（`localRead` 为 false）。
 * [notices] 已按时间倒序，这里仍显式排序，避免上游顺序变化影响小组件。
 */
fun summarizeInbox(
    notices: List<CxNoticeEntity>,
    syncedAtMs: Long = 0,
    limit: Int = 3,
): CxInboxSummary = CxInboxSummary(
    unread = notices.count { !it.localRead },
    latest = notices.asSequence()
        .sortedByDescending { it.timeMs }
        .take(limit.coerceAtLeast(0))
        .map { CxNoticeBrief(title = it.title.ifBlank { "学习通通知" }, atMs = it.timeMs, ntype = it.ntype) }
        .toList(),
    syncedAtMs = syncedAtMs,
    unreadByGroup = CxInboxGroup.entries.associateWith { group ->
        notices.count { !it.localRead && inboxGroupOf(it.ntype) == group }
    }.filterValues { it > 0 },
)

/** 收件箱类型映射：未知类型按「通知」计，与收件箱页的展示口径一致。 */
fun typeOfNtype(ntype: String): CxInboxType =
    CxInboxType.entries.firstOrNull { it.ntype == ntype } ?: CxInboxType.OTHER

/**
 * 进行中签到的剩余时间文案。
 *
 * [endsAtMs] 为 0 表示上游没给结束时间，界面只显示活动名，不硬猜剩余时长。
 */
fun cxSignRemainingLabel(endsAtMs: Long, nowMs: Long): String {
    if (endsAtMs <= 0L) return ""
    val left = endsAtMs - nowMs
    if (left <= 0L) return "已截止"
    val minutes = (left + 59_999L) / 60_000L
    if (minutes <= 1L) return "剩不到 1 分钟"
    if (minutes < 60L) return "剩 $minutes 分钟"
    val hours = minutes / 60L
    val rest = minutes % 60L
    return if (rest == 0L) "剩 $hours 小时" else "剩 $hours 小时 $rest 分"
}

/** 签到快照是否属于当前登录账号；换账号后旧账号的数据不能串到小组件上。 */
fun CxSignSnapshot.belongsTo(accountKey: String): Boolean =
    accountKey.isNotBlank() && this.accountKey == accountKey