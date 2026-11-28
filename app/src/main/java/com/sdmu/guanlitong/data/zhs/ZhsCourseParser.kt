package com.sdmu.guanlitong.data.zhs

import org.json.JSONArray
import org.json.JSONObject

/**
 * 课程列表 JSON 解析（纯函数，JVM 单测直接跑）。
 *
 * 两个列表的响应结构不同：
 * - 共享课 `POST …/share/queryShareCourseInfo` → `result.courseOpenDtos[]`；
 * - 翻转课 `GET …/online/course/getCourseList` → `rt.pageList[]`。
 *
 * 字段容错口径：
 * - `courseId` / `recruitId` 可能是数字或字符串，缺失或 ≤0 的条目直接丢弃（无法继续）；
 * - 课程名、教师、学校缺失时给固定占位文案，保证列表不出现空行；
 * - 进度取 `progress`，没有再看 `studyRate`，非数字视为未知，范围夹在 0..100。
 */
internal object ZhsCourseParser {

    fun parseShared(body: String): List<ZhsCourse> = parse(body, shared = true) { json ->
        json.optJSONObject("result")?.optJSONArray("courseOpenDtos")
    }

    fun parseFlipped(body: String): List<ZhsCourse> = parse(body, shared = false) { json ->
        json.optJSONObject("rt")?.optJSONArray("pageList")
    }

    internal fun parse(
        body: String,
        shared: Boolean,
        arrayOf: (JSONObject) -> JSONArray?,
    ): List<ZhsCourse> {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ZhsException("课程数据格式异常，请稍后重试", ZhsErrorKind.PARSE)
        val array = arrayOf(json)
        if (array == null) {
            // 有错误提示按错误处理；否则视为「这一类课程为空」。
            extractMessage(json)?.let { throw ZhsException(it, ZhsErrorKind.PARSE) }
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { courseOf(it, shared) }
        }
    }

    internal fun courseOf(raw: JSONObject, shared: Boolean): ZhsCourse? {
        val courseId = optId(raw.opt("courseId")) ?: return null
        val recruitId = optId(raw.opt("recruitId")) ?: return null
        return ZhsCourse(
            courseId = courseId,
            recruitId = recruitId,
            name = firstNonBlank(raw, "courseName", "title") ?: "未命名课程",
            teacher = firstNonBlank(raw, "teacherName") ?: "授课教师待更新",
            school = firstNonBlank(raw, "schoolName") ?: "智慧树课程",
            progress = optProgress(raw),
            shared = shared,
            smart = optId(raw.opt("courseType")) == 7L || optId(raw.opt("mapCourse")) == 7L,
            secret = firstNonBlank(raw, "secret") ?: "",
        )
    }

    /** 数字或数字字符串 → Long；其余（含 0、负数）返回 null。 */
    internal fun optId(value: Any?): Long? {
        val number = when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        } ?: return null
        return number.takeIf { it > 0 }
    }

    /** `progress` 优先，其次 `studyRate`；四舍五入并夹在 0..100。 */
    internal fun optProgress(raw: JSONObject): Int? {
        val value = raw.opt("progress") ?: raw.opt("studyRate")
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (!number.isFinite()) return null
        return number.coerceIn(0.0, 100.0).let { Math.round(it).toInt() }
    }

    private fun firstNonBlank(raw: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = raw.optString(key).trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return null
    }

    internal fun extractMessage(json: JSONObject): String? =
        json.optString("msg").ifBlank { json.optString("message") }.takeIf { it.isNotBlank() }
}