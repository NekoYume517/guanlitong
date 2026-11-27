package com.sdmu.guanlitong.data.jwxt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CachedGrades(
    val grades: List<GradeItem>,
    val savedAt: Long,
)

/**
 * 成绩本地缓存（桌面小组件的慢速预热用）。
 *
 * 只负责落盘与读取，不发请求；[peek] 永远只读本地，[save] 由调用方在拉取成功后调用。
 * 与 [JwxtCache] 一样按教务数据根地址分 key，官方与抢课助手互不覆盖。
 */
object JwxtGradesCache {

    private const val PREFS = "jwxt_grades_cache"
    private const val KEY_PREFIX = "grades_"

    private fun keyFor(baseUrl: String): String =
        KEY_PREFIX + Integer.toHexString(baseUrl.hashCode())

    @Volatile
    private var memory: CachedGrades? = null

    @Volatile
    private var memoryBaseUrl: String? = null

    /** 只读本地缓存，不触发任何网络请求。 */
    fun peek(context: Context, baseUrl: String): CachedGrades? {
        if (baseUrl.isBlank()) return null
        synchronized(this) {
            if (memoryBaseUrl == baseUrl) return memory
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(keyFor(baseUrl), null)
                ?: return null
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (root.optString("baseUrl") != baseUrl) return null
            val decoded = runCatching { decode(root) }.getOrNull() ?: return null
            memory = decoded
            memoryBaseUrl = baseUrl
            return decoded
        }
    }

    fun save(context: Context, baseUrl: String, grades: List<GradeItem>): CachedGrades {
        val cached = CachedGrades(grades = grades, savedAt = System.currentTimeMillis())
        synchronized(this) {
            memory = cached
            memoryBaseUrl = baseUrl
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(keyFor(baseUrl), encode(baseUrl, cached).toString())
                    .apply()
            }
        }
        return cached
    }

    fun clear(context: Context) {
        synchronized(this) {
            memory = null
            memoryBaseUrl = null
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

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
}