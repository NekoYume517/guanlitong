package com.sdmu.guanlitong.data.cx

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** 一条进行中的签到，给概览 / 桌面小组件看的最小摘要。 */
data class CxSignBrief(
    val activeId: String,
    val courseName: String,
    val modeLabel: String,
    val startsAtMs: Long = 0,
    val endsAtMs: Long = 0,
)

/**
 * 签到服务每轮扫描后落盘的摘要。
 *
 * 进程被回收后，概览与桌面仍能显示「进行中的签到 / 最近一次结果 / 受限解除时间」，
 * 不需要为小组件额外请求学习通接口。
 */
data class CxSignSnapshot(
    val accountKey: String = "",
    val updatedAtMs: Long = 0,
    val ongoing: List<CxSignBrief> = emptyList(),
    val lastResultTitle: String = "",
    val lastResultLabel: String = "",
    val lastResultDetail: String = "",
    val lastResultAtMs: Long = 0,
    val restrictionUntilMs: Long = 0,
    val restrictionMessage: String = "",
) {
    /** 按结束时间升序；没有结束时间的排最后。 */
    fun ongoingSorted(): List<CxSignBrief> =
        ongoing.sortedBy { it.endsAtMs.takeIf { ms -> ms > 0 } ?: Long.MAX_VALUE }
}

object CxSignSnapshotStore {

    private const val PREFS = "cx_sign_snapshot"
    private const val KEY = "snapshot"

    private val _state = MutableStateFlow<CxSignSnapshot?>(null)
    private val stateFlow: StateFlow<CxSignSnapshot?> = _state.asStateFlow()

    @Volatile
    private var loaded = false

    /** 概览用：进程内可订阅，服务每轮扫描写一次就会刷新。 */
    fun state(context: Context): StateFlow<CxSignSnapshot?> {
        ensureLoaded(context)
        return stateFlow
    }

    /** 只读本地快照，不触发网络。 */
    fun peek(context: Context): CxSignSnapshot? {
        ensureLoaded(context)
        return _state.value
    }

    fun save(context: Context, snapshot: CxSignSnapshot) {
        val normalized = snapshot.copy(updatedAtMs = snapshot.updatedAtMs.takeIf { it > 0 }
            ?: System.currentTimeMillis())
        _state.value = normalized
        loaded = true
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, encode(normalized).toString())
                .apply()
        }
    }

    fun clear(context: Context) {
        _state.value = null
        loaded = true
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            _state.value = raw?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
            loaded = true
        }
    }

    internal fun encode(snapshot: CxSignSnapshot): JSONObject = JSONObject().apply {
        put("accountKey", snapshot.accountKey)
        put("updatedAtMs", snapshot.updatedAtMs)
        put("lastResultTitle", snapshot.lastResultTitle)
        put("lastResultLabel", snapshot.lastResultLabel)
        put("lastResultDetail", snapshot.lastResultDetail)
        put("lastResultAtMs", snapshot.lastResultAtMs)
        put("restrictionUntilMs", snapshot.restrictionUntilMs)
        put("restrictionMessage", snapshot.restrictionMessage)
        put("ongoing", JSONArray().apply {
            snapshot.ongoing.forEach { item ->
                put(JSONObject().apply {
                    put("activeId", item.activeId)
                    put("courseName", item.courseName)
                    put("modeLabel", item.modeLabel)
                    put("startsAtMs", item.startsAtMs)
                    put("endsAtMs", item.endsAtMs)
                })
            }
        })
    }

    internal fun decode(root: JSONObject): CxSignSnapshot {
        val array = root.optJSONArray("ongoing") ?: JSONArray()
        val ongoing = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    CxSignBrief(
                        activeId = item.optString("activeId"),
                        courseName = item.optString("courseName"),
                        modeLabel = item.optString("modeLabel"),
                        startsAtMs = item.optLong("startsAtMs", 0L),
                        endsAtMs = item.optLong("endsAtMs", 0L),
                    )
                )
            }
        }
        return CxSignSnapshot(
            accountKey = root.optString("accountKey"),
            updatedAtMs = root.optLong("updatedAtMs", 0L),
            ongoing = ongoing,
            lastResultTitle = root.optString("lastResultTitle"),
            lastResultLabel = root.optString("lastResultLabel"),
            lastResultDetail = root.optString("lastResultDetail"),
            lastResultAtMs = root.optLong("lastResultAtMs", 0L),
            restrictionUntilMs = root.optLong("restrictionUntilMs", 0L),
            restrictionMessage = root.optString("restrictionMessage"),
        )
    }
}

/**
 * 刷课任务的持久化快照。
 *
 * 概览优先读进程内 [com.sdmu.guanlitong.service.CxTaskStatus]，进程被回收后回退到
 * 这里；桌面小组件只读这份快照。刷课服务在状态变化时写入，不产生任何请求。
 */
data class CxStudySnapshot(
    val updatedAtMs: Long = 0,
    val running: Boolean = false,
    val courseName: String = "",
    val phase: String = "",
    val currentIndex: Int = 0,
    val total: Int = 0,
    val currentTask: String = "",
    /** 当前任务点已播/总时长（秒），用于进度盘。 */
    val position: Int = 0,
    val duration: Int = 0,
    val message: String = "",
    val fatalError: String? = null,
    val incomplete: String? = null,
) {
    /** 已完成任务点 + 当前任务已播比例，占总任务点的百分比。 */
    val overallPercent: Int
        get() = if (total <= 0) {
            0
        } else {
            (((currentIndex - 1).coerceAtLeast(0) + taskProgressRatio()) * 100 / total)
                .roundToInt()
        }

    fun taskProgressRatio(): Float = progressRatio(position, duration)
}

/** 已播时长占比，clamp 到 0..1；时长未知时返回 0。 */
fun progressRatio(position: Int, duration: Int): Float {
    if (duration <= 0) return 0f
    return (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}

object CxStudySnapshotStore {

    private const val PREFS = "cx_study_snapshot"
    private const val KEY = "snapshot"

    private val _state = MutableStateFlow<CxStudySnapshot?>(null)
    private val stateFlow: StateFlow<CxStudySnapshot?> = _state.asStateFlow()

    @Volatile
    private var loaded = false

    fun state(context: Context): StateFlow<CxStudySnapshot?> {
        ensureLoaded(context)
        return stateFlow
    }

    /** 只读本地快照，不触发任何请求。 */
    fun peek(context: Context): CxStudySnapshot? {
        ensureLoaded(context)
        return _state.value
    }

    fun save(context: Context, snapshot: CxStudySnapshot) {
        val normalized = snapshot.copy(updatedAtMs = snapshot.updatedAtMs.takeIf { it > 0 }
            ?: System.currentTimeMillis())
        _state.value = normalized
        loaded = true
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, encode(normalized).toString())
                .apply()
        }
    }

    fun clear(context: Context) {
        _state.value = null
        loaded = true
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            _state.value = raw?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
            loaded = true
        }
    }

    internal fun encode(snapshot: CxStudySnapshot): JSONObject = JSONObject().apply {
        put("updatedAtMs", snapshot.updatedAtMs)
        put("running", snapshot.running)
        put("courseName", snapshot.courseName)
        put("phase", snapshot.phase)
        put("currentIndex", snapshot.currentIndex)
        put("total", snapshot.total)
        put("currentTask", snapshot.currentTask)
        put("position", snapshot.position)
        put("duration", snapshot.duration)
        put("message", snapshot.message)
        snapshot.fatalError?.let { put("fatalError", it) }
        snapshot.incomplete?.let { put("incomplete", it) }
    }

    internal fun decode(root: JSONObject): CxStudySnapshot = CxStudySnapshot(
        updatedAtMs = root.optLong("updatedAtMs", 0L),
        running = root.optBoolean("running", false),
        courseName = root.optString("courseName"),
        phase = root.optString("phase"),
        currentIndex = root.optInt("currentIndex", 0),
        total = root.optInt("total", 0),
        currentTask = root.optString("currentTask"),
        position = root.optInt("position", 0),
        duration = root.optInt("duration", 0),
        message = root.optString("message"),
        fatalError = root.optString("fatalError").takeIf { it.isNotBlank() },
        incomplete = root.optString("incomplete").takeIf { it.isNotBlank() },
    )
}