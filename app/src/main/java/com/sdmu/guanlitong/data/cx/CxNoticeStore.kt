package com.sdmu.guanlitong.data.cx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 收件箱本地库，存 SharedPreferences（不引 Room）。
 *
 * - `notices:<accountKey>`：JSON 数组，上限 [CX_NOTICE_MAX]（先淘汰最旧已读，
 *   未读硬顶 [CX_NOTICE_UNREAD_HARD_MAX]）；
 * - `inbox_cursor:<accountKey>` / `inbox_synced_at:<accountKey>`：同步游标与时间。
 *
 * 账号键全部带 accountKey，换账号不串数据；退出登录不删历史。
 */
interface CxNoticeLedger {
    fun notices(accountKey: String): List<CxNoticeEntity>
    fun hasInbox(accountKey: String, idCode: String): Boolean

    /** 已存在（按 cxInboxId）的跳过；返回真正加入的条目（新→旧）。 */
    fun addNotices(accountKey: String, items: List<CxNoticeEntity>): List<CxNoticeEntity>

    fun markRead(accountKey: String, id: String)
    fun markAllRead(accountKey: String)
    fun clearRead(accountKey: String)
    fun delete(accountKey: String, id: String)
    fun clear(accountKey: String)

    fun inboxCursor(accountKey: String): String
    fun setInboxCursor(accountKey: String, cursor: String)
    fun lastSyncedAt(accountKey: String): Long
    fun setLastSyncedAt(accountKey: String, atMs: Long)
}

class CxNoticeStore(context: Context) : CxNoticeLedger {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun notices(accountKey: String): List<CxNoticeEntity> =
        decodeNotices(prefs.getString(noticesKey(accountKey), null))

    override fun hasInbox(accountKey: String, idCode: String): Boolean =
        idCode.isNotBlank() && notices(accountKey).any { it.cxInboxId == idCode }

    @Synchronized
    override fun addNotices(accountKey: String, items: List<CxNoticeEntity>): List<CxNoticeEntity> {
        if (items.isEmpty()) return emptyList()
        val existing = notices(accountKey).toMutableList()
        val seen = existing.mapNotNullTo(HashSet()) { it.cxInboxId }
        val added = items.filter { it.cxInboxId == null || seen.add(it.cxInboxId) }
        if (added.isEmpty()) return emptyList()
        val merged = trim((added + existing).sortedByDescending { it.timeMs })
        prefs.edit().putString(noticesKey(accountKey), encodeNotices(merged)).apply()
        return added
    }

    @Synchronized
    override fun markRead(accountKey: String, id: String) {
        val list = notices(accountKey)
        if (list.none { it.id == id && !it.localRead }) return
        saveNotices(accountKey, list.map { if (it.id == id) it.copy(localRead = true) else it })
    }

    @Synchronized
    override fun markAllRead(accountKey: String) {
        saveNotices(accountKey, notices(accountKey).map { it.copy(localRead = true) })
    }

    @Synchronized
    override fun clearRead(accountKey: String) {
        saveNotices(accountKey, notices(accountKey).filter { !it.localRead })
    }

    @Synchronized
    override fun delete(accountKey: String, id: String) {
        saveNotices(accountKey, notices(accountKey).filter { it.id != id })
    }

    @Synchronized
    override fun clear(accountKey: String) {
        prefs.edit()
            .remove(noticesKey(accountKey))
            .remove(cursorKey(accountKey))
            .remove(syncedKey(accountKey))
            .apply()
    }

    override fun inboxCursor(accountKey: String): String =
        prefs.getString(cursorKey(accountKey), "").orEmpty()

    override fun setInboxCursor(accountKey: String, cursor: String) {
        prefs.edit().putString(cursorKey(accountKey), cursor).apply()
    }

    override fun lastSyncedAt(accountKey: String): Long =
        prefs.getLong(syncedKey(accountKey), 0)

    override fun setLastSyncedAt(accountKey: String, atMs: Long) {
        prefs.edit().putLong(syncedKey(accountKey), atMs).apply()
    }

    private fun saveNotices(accountKey: String, list: List<CxNoticeEntity>) {
        prefs.edit()
            .putString(noticesKey(accountKey), encodeNotices(trim(list)))
            .apply()
    }

    /** 超限时先丢最旧已读；未读最多保留 [CX_NOTICE_UNREAD_HARD_MAX] 条。 */
    private fun trim(list: List<CxNoticeEntity>): List<CxNoticeEntity> {
        if (list.size <= CX_NOTICE_MAX) return list
        val sorted = list.sortedByDescending { it.timeMs }
        val unread = sorted.filter { !it.localRead }.take(CX_NOTICE_UNREAD_HARD_MAX)
        val read = sorted.filter { it.localRead }
        val room = (CX_NOTICE_MAX - unread.size).coerceAtLeast(0)
        return (unread + read.take(room)).sortedByDescending { it.timeMs }
    }

    private fun noticesKey(accountKey: String) = "notices:$accountKey"
    private fun cursorKey(accountKey: String) = "inbox_cursor:$accountKey"
    private fun syncedKey(accountKey: String) = "inbox_synced_at:$accountKey"

    companion object {
        const val PREFS = "guanlitong_cx_notice"
    }
}

fun newNoticeId(): String = UUID.randomUUID().toString()

internal fun encodeNotices(list: List<CxNoticeEntity>): String {
    val array = JSONArray()
    list.forEach { array.put(encodeNotice(it)) }
    return array.toString()
}

internal fun decodeNotices(raw: String?): List<CxNoticeEntity> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    val result = ArrayList<CxNoticeEntity>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        runCatching { decodeNotice(obj) }.getOrNull()?.let(result::add)
    }
    return result
}

private fun encodeNotice(entity: CxNoticeEntity): JSONObject = JSONObject().apply {
    put("id", entity.id)
    put("accountKey", entity.accountKey)
    put("ntype", entity.ntype)
    put("title", entity.title)
    put("content", entity.content)
    put("timeMs", entity.timeMs)
    entity.remoteRead?.let { put("remoteRead", it) }
    put("localRead", entity.localRead)
    entity.cxInboxId?.let { put("cxInboxId", it) }
    entity.actionUrl?.let { put("actionUrl", it) }
}

private fun decodeNotice(obj: JSONObject): CxNoticeEntity = CxNoticeEntity(
    id = obj.getString("id"),
    accountKey = obj.optString("accountKey", ""),
    ntype = obj.optString("ntype", ""),
    title = obj.optString("title", ""),
    content = obj.optString("content", ""),
    timeMs = obj.optLong("timeMs", 0),
    remoteRead = if (obj.has("remoteRead")) obj.optBoolean("remoteRead") else null,
    localRead = obj.optBoolean("localRead", false),
    cxInboxId = obj.optString("cxInboxId").takeIf { it.isNotBlank() },
    actionUrl = obj.optString("actionUrl").takeIf { it.isNotBlank() },
)