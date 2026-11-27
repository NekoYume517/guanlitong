package com.sdmu.guanlitong.data.cx

/** 收件箱同步节奏（手动刷新不受此限制）。 */
const val CX_NOTICE_REFRESH_INTERVAL_MINUTES = 30L
const val CX_INBOX_PAGE_SIZE = 20

/** 收件箱消息类型：`sourceType` 映射沿用老项目 `_INBOX_SOURCE_TYPE_MAP` / `_INBOX_TYPE_EMOJI`。 */
enum class CxInboxType(val ntype: String, val emoji: String, val label: String) {
    WORK("inbox_work", "📝", "作业"),
    EXAM("inbox_exam", "📋", "考试"),
    CHECKIN("inbox_checkin", "✅", "签到"),
    QA("inbox_qa", "🙋", "互动"),
    NOTICE("inbox_notice", "📢", "通知"),
    OTHER("inbox_other", "📬", "其他"),
    ;

    companion object {
        fun fromSourceType(sourceType: String): CxInboxType = when (sourceType.trim()) {
            "14" -> WORK
            "12" -> EXAM
            "2" -> CHECKIN
            "3", "5" -> QA
            "0" -> NOTICE
            else -> OTHER
        }
    }
}

/** 一条收件箱消息。`remoteRead` 只是超星端已读状态的镜像，本地已读另存。 */
data class CxInboxItem(
    val idCode: String,
    val type: CxInboxType,
    val title: String,
    val content: String,
    val creator: String,
    val remoteRead: Boolean,
    val insertTimeMs: Long?,
    val actionUrl: String,
    val logoUrl: String,
) {
    /** 老项目在标题前加类型 emoji；已经带了就不重复加。 */
    val displayTitle: String
        get() = if (title.startsWith(type.emoji)) title else "${type.emoji} $title".trim()
}

/**
 * 收件箱一页：`topNotices` 与 `list` 已合并、按 `idCode` 去重。
 *
 * [statusOk] 为 false 表示接口返回了 falsy 的 status：按「无消息」处理，但要记 WARN
 * 日志（[rawSnippet] 是正文前 200 字），便于真机分辨是「真没消息」还是接口变了。
 */
data class CxInboxPage(
    val items: List<CxInboxItem>,
    val lastGetId: String,
    val lastPage: Boolean,
    val statusOk: Boolean = true,
    val rawSnippet: String = "",
)

/**
 * 收件箱在本地的落库条目。
 *
 * [remoteRead] 是超星端已读镜像，[localRead] 是用户在 App 里点开后的已读；两者独立。
 * [actionUrl] 用于详情页按类型路由（签到/作业/普通链接）。
 */
data class CxNoticeEntity(
    val id: String,
    val accountKey: String,
    val ntype: String,
    val title: String,
    val content: String,
    val timeMs: Long,
    val remoteRead: Boolean? = null,
    val localRead: Boolean = false,
    val cxInboxId: String? = null,
    val actionUrl: String? = null,
)

const val CX_NOTICE_MAX = 200
const val CX_NOTICE_UNREAD_HARD_MAX = 300