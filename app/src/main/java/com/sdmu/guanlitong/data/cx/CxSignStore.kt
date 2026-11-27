package com.sdmu.guanlitong.data.cx

import android.content.Context
import android.content.SharedPreferences

/**
 * 签到设置。
 *
 * [monitorEnabled] 是「签到实时提醒」总开关，默认开启：开着才轮询课程活动，
 * 发现开放中的签到/签退就提醒。[checkinEnabled] / [checkoutEnabled] 只决定
 * 发现后要不要自动提交；总开关关掉时两者都不生效。
 */
data class CxSignSettings(
    val monitorEnabled: Boolean = true,
    val checkinEnabled: Boolean = false,
    val checkoutEnabled: Boolean = false,
    val seedLat: Double = CX_SIGN_DEFAULT_SEED_LAT,
    val seedLng: Double = CX_SIGN_DEFAULT_SEED_LNG,
    val address: String = "",
)

/** 去重台账：单测用假实现，线上用 [CxSignStore]。 */
interface CxSignLedger {
    fun record(accountKey: String, activeId: String, kind: CxSignKind): CxSignRecord?
    fun save(accountKey: String, activeId: String, kind: CxSignKind, record: CxSignRecord)

    /**
     * 「最近记录」追加一条事件。与去重台账分开：台账只留最新状态，历史按时间追加。
     * 默认空实现，单测里的假台账不用管它。
     */
    fun appendHistory(accountKey: String, entry: CxSignHistoryEntry) {}

    /** 本账号当前是否处于「限制访问」退避期；过期返回 null。 */
    fun restriction(accountKey: String, nowMs: Long): CxSignRestriction? = null

    /** 记录服务端给的解除时间，后续轮询在此之前直接跳过。 */
    fun saveRestriction(accountKey: String, restriction: CxSignRestriction) {}

    fun clearRestriction(accountKey: String) {}

    /**
     * 启动清理：把本账号超时未完成的 RUNNING 转成 FAILED（见 [recoverStaleRecord]）。
     * 进程被杀后不清的话，这条活动要等逃生阈值才能重试。
     */
    fun recoverStaleRunning(accountKey: String, nowMs: Long) {}
}

/**
 * 账号指纹：优先 uid，退到手机号。去重键必须带它，否则换账号会把别人的签到
 * 记录误判成「已签」。
 */
fun signAccountKey(session: CxSession): String =
    session.uid.ifBlank { session.phone }.ifBlank { "unknown" }

/**
 * 自动签到设置与去重台账，存 SharedPreferences（与 [CxCredStore] / AccountStore
 * 同一套约定，不引 Room）。
 *
 * 手势签到不做固定密码：老师每次给的图案都不同，遇到时只提醒用户当场手划，
 * 这里只存开关、种子坐标与地点。
 *
 * 台账键 = [signRecordKey]（账号指纹 + activeId + kind），值为
 * `状态首字母|尝试次数|毫秒时间戳`。
 */
class CxSignStore(context: Context) : CxSignLedger {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun settings(): CxSignSettings = CxSignSettings(
        monitorEnabled = prefs.getBoolean(KEY_MONITOR, true),
        checkinEnabled = prefs.getBoolean(KEY_CHECKIN, false),
        checkoutEnabled = prefs.getBoolean(KEY_CHECKOUT, false),
        seedLat = prefs.getString(KEY_SEED_LAT, null)?.toDoubleOrNull()
            ?: CX_SIGN_DEFAULT_SEED_LAT,
        seedLng = prefs.getString(KEY_SEED_LNG, null)?.toDoubleOrNull()
            ?: CX_SIGN_DEFAULT_SEED_LNG,
        address = prefs.getString(KEY_ADDRESS, "").orEmpty(),
    )

    fun setMonitorEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_MONITOR, enabled).apply()

    fun setCheckinEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_CHECKIN, enabled).apply()

    fun setCheckoutEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_CHECKOUT, enabled).apply()

    fun setSeed(lat: Double, lng: Double) = prefs.edit()
        .putString(KEY_SEED_LAT, lat.toString())
        .putString(KEY_SEED_LNG, lng.toString())
        .apply()

    fun setAddress(address: String) =
        prefs.edit().putString(KEY_ADDRESS, address.trim()).apply()

    /** 保活引导是否已经提示过；跨进程持久，最多提示一次。 */
    fun hasShownKeepAliveGuide(): Boolean = prefs.getBoolean(KEY_KEEP_ALIVE_GUIDE, false)

    fun markKeepAliveGuideShown() =
        prefs.edit().putBoolean(KEY_KEEP_ALIVE_GUIDE, true).apply()

    override fun record(accountKey: String, activeId: String, kind: CxSignKind): CxSignRecord? =
        prefs.getString(signRecordKey(accountKey, activeId, kind), null)?.let(::decodeRecord)

    override fun recoverStaleRunning(accountKey: String, nowMs: Long) {
        val prefix = "$accountKey:"
        val editor = prefs.edit()
        var changed = false
        for ((key, value) in prefs.all) {
            if (!key.startsWith(prefix)) continue
            val raw = value as? String ?: continue
            val record = decodeRecord(raw) ?: continue
            val recovered = recoverStaleRecord(record, nowMs)
            if (recovered != record) {
                editor.putString(key, encodeRecord(recovered))
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    override fun save(
        accountKey: String,
        activeId: String,
        kind: CxSignKind,
        record: CxSignRecord,
    ) {
        prefs.edit()
            .putString(signRecordKey(accountKey, activeId, kind), encodeRecord(record))
            .apply()
    }

    /** 本账号最近的签到事件，最新在前；按账号各存一份，互不串台。 */
    fun history(accountKey: String): List<CxSignHistoryEntry> =
        decodeSignHistory(prefs.getString(historyKey(accountKey), null).orEmpty())

    override fun appendHistory(accountKey: String, entry: CxSignHistoryEntry) {
        val current = history(accountKey)
        val next = appendSignHistory(current, entry)
        if (next == current) return
        prefs.edit().putString(historyKey(accountKey), encodeSignHistory(next)).apply()
    }

    private fun historyKey(accountKey: String): String = "history:$accountKey"

    override fun restriction(accountKey: String, nowMs: Long): CxSignRestriction? {
        val until = prefs.getLong(restrictionKey(accountKey, "until"), 0L)
        val message = prefs.getString(restrictionKey(accountKey, "msg"), "").orEmpty()
        if (until <= nowMs) {
            if (until != 0L) clearRestriction(accountKey)
            return null
        }
        return CxSignRestriction(untilMs = until, message = message)
    }

    override fun saveRestriction(accountKey: String, restriction: CxSignRestriction) {
        prefs.edit()
            .putLong(restrictionKey(accountKey, "until"), restriction.untilMs)
            .putString(restrictionKey(accountKey, "msg"), restriction.message)
            .apply()
    }

    override fun clearRestriction(accountKey: String) {
        prefs.edit()
            .remove(restrictionKey(accountKey, "until"))
            .remove(restrictionKey(accountKey, "msg"))
            .apply()
    }

    private fun restrictionKey(accountKey: String, field: String): String =
        "restriction:$accountKey:$field"

    private fun encodeRecord(record: CxSignRecord): String =
        "${record.status.name.first()}|${record.attempts}|${record.atMs}"

    /** 坏数据当作没有记录，宁可多试一次也不要永久跳过。 */
    private fun decodeRecord(raw: String): CxSignRecord? {
        val parts = raw.split('|')
        if (parts.size != 3) return null
        val status = CxSignRecordStatus.entries.firstOrNull { it.name.first() == parts[0].firstOrNull() }
            ?: return null
        return CxSignRecord(
            status = status,
            attempts = parts[1].toIntOrNull() ?: return null,
            atMs = parts[2].toLongOrNull() ?: return null,
        )
    }

    private companion object {
        const val PREF_NAME = "guanlitong_cx_sign"
        const val KEY_MONITOR = "monitor_enabled"
        const val KEY_CHECKIN = "checkin_enabled"
        const val KEY_CHECKOUT = "checkout_enabled"
        const val KEY_SEED_LAT = "seed_lat"
        const val KEY_SEED_LNG = "seed_lng"
        const val KEY_ADDRESS = "address"
        const val KEY_KEEP_ALIVE_GUIDE = "keep_alive_guide_shown"
    }
}