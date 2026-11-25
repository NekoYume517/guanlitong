package com.sdmu.guanlitong.data.pu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Collator
import java.util.Locale

/**
 * 官方学校条目（`pocketuni.net` 的 Sitelist/getSchools，见
 * `docs/integrations` 以外的学校协议笔记：一次取全量，本地搜索）。
 *
 * [goId] 才是登录用的学校标识（请求体 `sid`）；[numericId] 是旧业务的短 ID，
 * 两者不能混用。
 */
data class PuSchool(
    val goId: String,
    val name: String,
    val displayOrder: String = "",
    val numericId: String = "",
    val isGo: String = "",
) : java.io.Serializable {
    /** 首字母分组：display_order 的首字符，不是字母时归到「#」。 */
    val group: String
        get() = displayOrder.firstOrNull()
            ?.uppercaseChar()
            ?.takeIf { it.isLetter() }
            ?.toString()
            ?: "#"
}

internal const val PU_DEFAULT_SCHOOL_GO_ID = "208791531159552"
internal const val PU_DEFAULT_SCHOOL_NAME = "山东管理学院"

/** 默认学校：山东管理学院；用户选过别的学校后以保存的为准。 */
internal fun defaultPuSchool(): PuSchool = PuSchool(
    goId = PU_DEFAULT_SCHOOL_GO_ID,
    name = PU_DEFAULT_SCHOOL_NAME,
)

/**
 * 已选学校 + 学校列表缓存。
 *
 * 学校列表与登录凭据分开存：换学校、清 token 都不影响这份选择；
 * 列表本身带 ETag，304 时直接用上次的 JSON。
 */
class PuSchoolStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun selected(): PuSchool {
        val goId = prefs.getString(KEY_SELECTED_GO_ID, "").orEmpty().trim()
        val name = prefs.getString(KEY_SELECTED_NAME, "").orEmpty().trim()
        if (goId.isBlank() || name.isBlank()) return defaultPuSchool()
        return PuSchool(goId = goId, name = name)
    }

    fun select(school: PuSchool) {
        prefs.edit()
            .putString(KEY_SELECTED_GO_ID, school.goId)
            .putString(KEY_SELECTED_NAME, school.name)
            .apply()
    }

    private fun etag(): String = prefs.getString(KEY_ETAG, "").orEmpty()

    private fun cached(): List<PuSchool> = runCatching {
        parseSchools(JSONArray(prefs.getString(KEY_CACHE, "").orEmpty()))
    }.getOrDefault(emptyList())

    private fun saveCache(etag: String, schools: List<PuSchool>) {
        val array = JSONArray()
        schools.forEach { school ->
            array.put(
                JSONObject()
                    .put("go_id", school.goId)
                    .put("name", school.name)
                    .put("display_order", school.displayOrder)
                    .put("school", school.numericId)
                    .put("is_go", school.isGo),
            )
        }
        prefs.edit()
            .putString(KEY_ETAG, etag)
            .putString(KEY_CACHE, array.toString())
            .apply()
    }

    internal fun cachedSchools(): List<PuSchool> = cached()

    internal fun requestEtag(): String = etag().ifBlank { "1" }

    internal fun storeResponse(etag: String, schools: List<PuSchool>) = saveCache(etag, schools)

    private companion object {
        const val PREF = "guanlitong_pu_schools"
        const val KEY_SELECTED_GO_ID = "selected_go_id"
        const val KEY_SELECTED_NAME = "selected_name"
        const val KEY_ETAG = "schools_etag"
        const val KEY_CACHE = "schools_json"
    }
}

/**
 * 学校列表接口：`POST /index.php?app=api&mod=Sitelist&act=getSchools`。
 *
 * 没有分页、没有关键字参数；`If-None-Match` 走 ETag，304 时回退到本地缓存。
 */
object PuSchoolsApi {

    private const val SCHOOLS_URL = "https://pocketuni.net/index.php?app=api&mod=Sitelist&act=getSchools"
    private const val CONNECT_MS = 12000
    private const val READ_MS = 20000

    suspend fun fetch(context: Context): List<PuSchool> = withContext(Dispatchers.IO) {
        val store = PuSchoolStore(context)
        val conn = (URL(SCHOOLS_URL).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.doInput = true
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", PuSign.userAgent())
            conn.setRequestProperty("If-None-Match", store.requestEtag())
            PuSign.xSign()?.takeIf { it.isNotBlank() }?.let {
                conn.setRequestProperty("X-Sign", it)
            }

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                val cached = store.cachedSchools()
                if (cached.isNotEmpty()) return@withContext cached
                // 缓存丢了（清数据）而服务端仍返回 304：清掉 ETag 重取一次。
                store.storeResponse("", emptyList())
                return@withContext fetch(context)
            }
            val text = readAll(if (status >= 400) conn.errorStream else conn.inputStream)
            if (status >= 400) {
                throw PuException("学校列表请求失败: HTTP $status", 502)
            }
            val array = runCatching { JSONArray(text) }.getOrElse {
                throw PuException("学校列表返回的不是 JSON 数组", 502)
            }
            val schools = parseSchools(array)
            if (schools.isEmpty()) throw PuException("学校列表为空", 502)
            store.storeResponse(conn.getHeaderField("ETag").orEmpty(), schools)
            return@withContext schools
        } catch (e: PuException) {
            throw e
        } catch (e: IOException) {
            throw PuException("网络错误: ${e.message ?: e.javaClass.simpleName}", 502)
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        return runCatching { stream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
            .getOrDefault("")
    }
}

/** 本地搜索：纯字母查 display_order，其余（中文、数字、混合）查校名。 */
internal fun filterSchools(all: List<PuSchool>, query: String): List<PuSchool> {
    val q = query.trim()
    if (q.isBlank()) return sortSchools(all)
    return if (q.matches(PURE_LETTERS)) {
        sortSchools(all.filter { it.displayOrder.contains(q, ignoreCase = true) })
    } else {
        sortSchools(all.filter { it.name.contains(q) })
    }
}

private val PURE_LETTERS = Regex("[A-Za-z]+")

/** 与官方一致：按 display_order / 校名做中文排序，空查询时分段展示。 */
internal fun sortSchools(all: List<PuSchool>): List<PuSchool> {
    val collator = Collator.getInstance(Locale.CHINA)
    return all.sortedWith(
        compareBy<PuSchool, String>(collator) { it.displayOrder.ifBlank { it.name } }
            .thenBy(collator) { it.name },
    )
}

private fun parseSchools(array: JSONArray): List<PuSchool> = buildList {
    for (index in 0 until array.length()) {
        val obj = array.optJSONObject(index) ?: continue
        val goId = obj.string("go_id")
        val name = obj.string("name")
        if (goId.isBlank() || name.isBlank()) continue
        add(
            PuSchool(
                goId = goId,
                name = name,
                displayOrder = obj.string("display_order"),
                numericId = obj.string("school"),
                isGo = obj.string("is_go"),
            ),
        )
    }
}

/** 上游字段有时是数字；[JSONObject.optString] 遇到数字只会给默认值。 */
private fun JSONObject.string(key: String): String = when (val value = opt(key)) {
    null, JSONObject.NULL -> ""
    is String -> value.trim()
    else -> value.toString().trim()
}