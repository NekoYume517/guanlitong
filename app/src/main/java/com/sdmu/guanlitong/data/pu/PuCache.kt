package com.sdmu.guanlitong.data.pu

import android.content.Context
import com.sdmu.guanlitong.data.CacheShelf
import com.sdmu.guanlitong.data.CacheVersion
import com.sdmu.guanlitong.data.SharedPrefsShelf
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class CachedPu(
    val activities: List<PuActivity>,
    val totalCredit: Double,
    val savedAt: Long,
)

/**
 * 第二课堂本地缓存（桌面小组件与概览卡片的慢速读取口）。
 *
 * 保存首页活动列表与总学时；活动页拉取第一页成功时写、桌面预热也写。
 * [peek] 永远只读本地，不发请求。
 */
object PuCache {

    private const val PREFS = "pu_snapshot_cache"

    private val versionState = CacheVersion()
    val version: StateFlow<Int> = versionState.value

    @Volatile
    private var store: PuCacheData? = null

    private fun store(context: Context): PuCacheData =
        store ?: synchronized(this) {
            store ?: PuCacheData(
                shelf = SharedPrefsShelf(context, PREFS),
                version = versionState,
            ).also { store = it }
        }

    fun peek(context: Context): CachedPu? = store(context).peek()

    fun save(
        context: Context,
        activities: List<PuActivity>,
        totalCredit: Double,
    ): CachedPu = store(context).save(activities, totalCredit)

    fun clear(context: Context) = store(context).clear()
}

/** 缓存读写逻辑，脱离 Android 运行时，便于单测版本号。 */
internal class PuCacheData(
    private val shelf: CacheShelf,
    val version: CacheVersion,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var memory: CachedPu? = null

    fun peek(): CachedPu? {
        synchronized(this) {
            memory?.let { return it }
            val raw = shelf.get(KEY) ?: return null
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val decoded = runCatching { decode(root) }.getOrNull() ?: return null
            memory = decoded
            return decoded
        }
    }

    fun save(activities: List<PuActivity>, totalCredit: Double): CachedPu {
        val cached = CachedPu(
            activities = activities,
            totalCredit = totalCredit,
            savedAt = clock(),
        )
        synchronized(this) {
            memory = cached
            runCatching { shelf.put(KEY, encode(cached).toString()) }
            version.bump()
        }
        return cached
    }

    fun clear() {
        synchronized(this) {
            memory = null
            shelf.clear()
            version.bump()
        }
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

    private companion object {
        const val KEY = "snapshot"
    }
}