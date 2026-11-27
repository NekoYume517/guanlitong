package com.sdmu.guanlitong.data.pu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CachedPu(
    val activities: List<PuActivity>,
    val totalCredit: Double,
    val savedAt: Long,
)

/**
 * 第二课堂本地缓存（桌面小组件的慢速预热用）。
 *
 * 保存首页活动列表与总学时；只负责落盘与读取，不发请求。
 * [peek] 永远只读本地，[save] 由调用方在拉取成功后调用。
 */
object PuCache {

    private const val PREFS = "pu_snapshot_cache"
    private const val KEY = "snapshot"

    @Volatile
    private var memory: CachedPu? = null

    fun peek(context: Context): CachedPu? {
        synchronized(this) {
            memory?.let { return it }
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
                ?: return null
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val decoded = runCatching { decode(root) }.getOrNull() ?: return null
            memory = decoded
            return decoded
        }
    }

    fun save(
        context: Context,
        activities: List<PuActivity>,
        totalCredit: Double,
    ): CachedPu {
        val cached = CachedPu(
            activities = activities,
            totalCredit = totalCredit,
            savedAt = System.currentTimeMillis(),
        )
        synchronized(this) {
            memory = cached
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY, encode(cached).toString())
                    .apply()
            }
        }
        return cached
    }

    fun clear(context: Context) {
        synchronized(this) { memory = null }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun encode(cached: CachedPu): JSONObject = JSONObject().apply {
        put("savedAt", cached.savedAt)
        put("totalCredit", cached.totalCredit)
        put("activities", JSONArray().apply {
            cached.activities.forEach { put(it.raw) }
        })
    }

    private fun decode(root: JSONObject): CachedPu {
        val array = root.optJSONArray("activities") ?: JSONArray()
        val activities = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(item.toActivity())
            }
        }
        return CachedPu(
            activities = activities,
            totalCredit = root.optDouble("totalCredit", 0.0),
            savedAt = root.optLong("savedAt", 0L),
        )
    }
}