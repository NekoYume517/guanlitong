package com.sdmu.guanlitong.data.jwxt

import android.content.Context
import com.sdmu.guanlitong.data.CacheShelf
import com.sdmu.guanlitong.data.CacheVersion
import com.sdmu.guanlitong.data.SharedPrefsShelf
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class CachedGrades(
    val grades: List<GradeItem>,
    val savedAt: Long,
)

/**
 * 成绩本地缓存（桌面小组件与概览卡片的慢速读取口）。
 *
 * 成绩页拉取成功时写、桌面预热也写；[peek] 永远只读本地，不发请求。
 * 与 [JwxtCache] 一样按教务数据根地址分 key，官方与抢课助手互不覆盖；
 * 读取时还会校验落盘里的 `baseUrl`，来源切换不会串数据。
 */
object JwxtGradesCache {

    private const val PREFS = "jwxt_grades_cache"

    private val versionState = CacheVersion()
    val version: StateFlow<Int> = versionState.value

    @Volatile
    private var store: JwxtGradesCacheData? = null

    private fun store(context: Context): JwxtGradesCacheData =
        store ?: synchronized(this) {
            store ?: JwxtGradesCacheData(
                shelf = SharedPrefsShelf(context, PREFS),
                version = versionState,
            ).also { store = it }
        }

    /** 只读本地缓存，不触发任何网络请求。 */
    fun peek(context: Context, baseUrl: String): CachedGrades? = store(context).peek(baseUrl)

    fun save(context: Context, baseUrl: String, grades: List<GradeItem>): CachedGrades =
        store(context).save(baseUrl, grades)

    fun clear(context: Context) = store(context).clear()
}

/**
 * 缓存本身的读写逻辑，脱离 Android 运行时，便于单测按 origin 隔离与版本号。
 */
internal class JwxtGradesCacheData(
    private val shelf: CacheShelf,
    val version: CacheVersion,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var memory: CachedGrades? = null
    private var memoryBaseUrl: String? = null

    fun peek(baseUrl: String): CachedGrades? {
        if (baseUrl.isBlank()) return null
        synchronized(this) {
            if (memoryBaseUrl == baseUrl) return memory
            val raw = shelf.get(keyFor(baseUrl)) ?: return null
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (root.optString("baseUrl") != baseUrl) return null
            val decoded = runCatching { decode(root) }.getOrNull() ?: return null
            memory = decoded
            memoryBaseUrl = baseUrl
            return decoded
        }
    }

    fun save(baseUrl: String, grades: List<GradeItem>): CachedGrades {
        val cached = CachedGrades(grades = grades, savedAt = clock())
        synchronized(this) {
            memory = cached
            memoryBaseUrl = baseUrl
            runCatching { shelf.put(keyFor(baseUrl), encode(baseUrl, cached).toString()) }
            version.bump()
        }
        return cached
    }

    fun clear() {
        synchronized(this) {
            memory = null
            memoryBaseUrl = null
            shelf.clear()
            version.bump()
        }
    }

    private fun keyFor(baseUrl: String): String =
        KEY_PREFIX + Integer.toHexString(baseUrl.hashCode())

    private fun encode(baseUrl: String, cached: CachedGrades): JSONObject = JSONObject().apply {
        put("baseUrl", baseUrl)
        put("savedAt", cached.savedAt)
        put("grades", JSONArray().apply {
            cached.grades.forEach { grade ->
                put(JSONObject().apply {
                    put("name", grade.name)
                    put("score", grade.score)
                    put("credit", grade.credit)
                    grade.point?.let { put("point", it) }
                    put("nature", grade.nature)
                    put("type", grade.type)
                    put("teacher", grade.teacher)
                    put("semester", grade.semester)
                    put("semesterKey", grade.semesterKey)
                })
            }
        })
    }

    private fun decode(root: JSONObject): CachedGrades {
        val array = root.optJSONArray("grades") ?: JSONArray()
        val grades = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    GradeItem(
                        name = item.optString("name"),
                        score = item.optString("score"),
                        credit = item.optDouble("credit", 0.0),
                        // 上游点数字段可能为 null；optDouble 对 JSON null 会返回 NaN，必须显式过滤。
                        point = if (item.has("point") && !item.isNull("point")) {
                            item.optDouble("point").takeUnless { it.isNaN() }
                        } else {
                            null
                        },
                        nature = item.optString("nature"),
                        type = item.optString("type"),
                        teacher = item.optString("teacher"),
                        semester = item.optString("semester"),
                        semesterKey = item.optString("semesterKey"),
                    )
                )
            }
        }
        return CachedGrades(grades = grades, savedAt = root.optLong("savedAt", 0L))
    }

    private companion object {
        const val KEY_PREFIX = "grades_"
    }
}