package com.sdmu.guanlitong.data.jwxt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CachedTimetable(
    val timetable: Timetable,
    val savedAt: Long,
)

/**
 * 课表本地缓存。
 *
 * 课表数据量很小（一学期几十条），直接序列化成一段 JSON 存在 SharedPreferences：
 * 进页面先显示上次结果，再在后台向教务系统刷新；网络失败或登录失效时仍可离线查看。
 * 缓存与教务系统根地址绑定，换地址后不会串数据。
 */
object JwxtCache {

    private const val PREFS = "jwxt_timetable_cache"
    private const val KEY_PREFIX = "snapshot_"

    /** 官方与抢课助手各存一份缓存，切换来源互不覆盖。 */
    private fun keyFor(baseUrl: String): String =
        KEY_PREFIX + Integer.toHexString(baseUrl.hashCode())

    @Volatile
    private var memory: CachedTimetable? = null

    @Volatile
    private var memoryBaseUrl: String? = null

    fun load(context: Context, baseUrl: String): CachedTimetable? {
        if (baseUrl.isBlank()) return null
        synchronized(this) {
            if (memoryBaseUrl == baseUrl) return memory
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = keyFor(baseUrl)
            // 兼容按来源分 key 之前的单份缓存。
            val raw = prefs.getString(key, null) ?: prefs.getString("snapshot", null)
                ?: return null
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (root.optString("baseUrl") != baseUrl) return null
            val decoded = runCatching { decode(root) }.getOrNull() ?: return null
            if (prefs.getString(key, null) == null) {
                prefs.edit().putString(key, raw).apply()
            }
            memory = decoded
            memoryBaseUrl = baseUrl
            return decoded
        }
    }

    fun save(context: Context, baseUrl: String, timetable: Timetable): CachedTimetable {
        val cached = CachedTimetable(timetable = timetable, savedAt = System.currentTimeMillis())
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

    // ── 序列化 ────────────────────────────────────────────

    private fun encode(baseUrl: String, cached: CachedTimetable): JSONObject {
        val timetable = cached.timetable
        return JSONObject().apply {
            put("baseUrl", baseUrl)
            put("savedAt", cached.savedAt)
            put("xnm", timetable.xnm)
            put("xqm", timetable.xqm)
            put("currentWeek", timetable.currentWeek)
            put("studentName", timetable.studentName)
            put("studentId", timetable.studentId)

            put("weeks", JSONArray().apply {
                timetable.weeks.forEach { week ->
                    put(JSONObject().apply {
                        put("zs", week.zs)
                        put("zcrq", week.zcrq)
                    })
                }
            })

            put("courses", JSONArray().apply {
                timetable.courses.forEach { course ->
                    put(JSONObject().apply {
                        put("name", course.name)
                        put("teacher", course.teacher)
                        put("className", course.className)
                        put("room", course.room)
                        put("campus", course.campus)
                        put("day", course.dayOfWeek)
                        put("start", course.startPeriod)
                        put("end", course.endPeriod)
                        put("weeks", JSONArray(course.weeks.sorted()))
                        put("weeksDesc", course.weeksDesc)
                        put("rawWeeks", course.rawWeeks)
                    })
                }
            })

            put("jcTimes", JSONArray().apply {
                timetable.jcTimes.values.sortedBy { it.period }.forEach { time ->
                    put(JSONObject().apply {
                        put("period", time.period)
                        put("start", time.start)
                        put("end", time.end)
                        put("section", time.section)
                    })
                }
            })

            put("headerDates", JSONObject().apply {
                timetable.headerDates.forEach { (day, label) ->
                    put(day.toString(), label)
                }
            })
        }
    }

    private fun decode(root: JSONObject): CachedTimetable {
        val weeks = root.optJSONArray("weeks")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                WeekInfo(
                    zs = item.optString("zs"),
                    zcrq = item.optString("zcrq"),
                )
            }
        }.orEmpty()

        val courses = root.optJSONArray("courses")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name")
                if (name.isBlank()) return@mapNotNull null
                Course(
                    name = name,
                    teacher = item.optString("teacher"),
                    className = item.optString("className"),
                    room = item.optString("room"),
                    campus = item.optString("campus"),
                    dayOfWeek = item.optInt("day"),
                    startPeriod = item.optInt("start", 1),
                    endPeriod = item.optInt("end", 1),
                    weeks = item.optJSONArray("weeks")?.let { set ->
                        (0 until set.length()).mapNotNull { set.optInt(it).takeIf { w -> w > 0 } }.toSet()
                    }.orEmpty(),
                    weeksDesc = item.optString("weeksDesc"),
                    rawWeeks = item.optString("rawWeeks"),
                )
            }
        }.orEmpty()

        val jcTimes = root.optJSONArray("jcTimes")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val period = item.optInt("period")
                if (period <= 0) return@mapNotNull null
                period to JcTime(
                    period = period,
                    start = item.optString("start"),
                    end = item.optString("end"),
                    section = item.optString("section"),
                )
            }.toMap()
        }.orEmpty()

        val headerDates = root.optJSONObject("headerDates")?.let { objectValue ->
            objectValue.keys().asSequence()
                .mapNotNull { key -> key.toIntOrNull()?.let { it to objectValue.optString(key) } }
                .toMap()
        }.orEmpty()

        return CachedTimetable(
            timetable = Timetable(
                xnm = root.optString("xnm"),
                xqm = root.optString("xqm"),
                weeks = weeks,
                currentWeek = root.optInt("currentWeek", 1),
                courses = courses,
                jcTimes = jcTimes,
                headerDates = headerDates,
                studentName = root.optString("studentName"),
                studentId = root.optString("studentId"),
            ),
            savedAt = root.optLong("savedAt"),
        )
    }
}