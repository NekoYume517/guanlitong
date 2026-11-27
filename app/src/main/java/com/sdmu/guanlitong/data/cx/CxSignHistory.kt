package com.sdmu.guanlitong.data.cx

import java.net.URLDecoder
import java.net.URLEncoder

/** 最近记录里一次签到的结果。 */
enum class CxSignHistoryResult(val label: String) {
    SUCCESS("成功"),
    MANUAL("需手动"),
    FAILED("失败"),
    ENDED("已结束"),
}

/**
 * 「签到 → 最近记录」里的一条事件。
 *
 * 与去重台账 [CxSignRecord] 分开：台账按「账号 + 活动 + 类型」只留最新状态，用来
 * 决定还要不要提交；这里只追加历史，用来展示最近发生过什么。课程名/活动名是给人
 * 看的快照，课程/班级 id 用来点击回课程详情。
 */
data class CxSignHistoryEntry(
    val activeId: String,
    val kind: CxSignKind,
    val result: CxSignHistoryResult,
    val atMs: Long,
    val courseName: String = "",
    val activeName: String = "",
    val courseId: String = "",
    val clazzId: String = "",
    /** 结果附言，如「位置签到成功（偏移 9m）」或失败原因；没有为空。 */
    val detail: String = "",
) {
    /** 没有课程名时列表里显示的人话标题。 */
    val displayTitle: String
        get() = listOf(courseName, activeName).filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "学习通签到" }
}

/** 最近记录最多保留 200 条，超出丢最旧的。 */
const val CX_SIGN_HISTORY_LIMIT = 200

/**
 * 追加一条历史，并做去重与截断：
 *
 * - 同一条活动、同一类型、同一结果只留一条——结果通知是「状态变化才发」，这里再
 *   兜一层，避免进程重启、界面重复提交把同一次签到写成两条；
 * - 成功之后再补签同类结果不会重复追加；不同结果（如先「需手动」后「成功」）都保留；
 * - 最新的在最前，超过 [limit] 条丢最旧的。
 */
fun appendSignHistory(
    existing: List<CxSignHistoryEntry>,
    entry: CxSignHistoryEntry,
    limit: Int = CX_SIGN_HISTORY_LIMIT,
): List<CxSignHistoryEntry> {
    val duplicate = existing.any {
        it.activeId == entry.activeId && it.kind == entry.kind && it.result == entry.result
    }
    if (duplicate) return existing
    return (listOf(entry) + existing).take(limit.coerceAtLeast(1))
}

/**
 * 自动签到与手动签到共用的历史条目构造：结果 + 服务端附言（偏移、失败原因）。
 */
fun signResultHistory(
    activeId: String,
    kind: CxSignKind,
    result: CxSignHistoryResult,
    atMs: Long,
    courseName: String = "",
    activeName: String = "",
    courseId: String = "",
    clazzId: String = "",
    detail: String = "",
): CxSignHistoryEntry = CxSignHistoryEntry(
    activeId = activeId,
    kind = kind,
    result = result,
    atMs = atMs,
    courseName = courseName,
    activeName = activeName,
    courseId = courseId,
    clazzId = clazzId,
    detail = detail,
)

/**
 * 九宫格提交成功后写「最近记录」的条目：签到成功，带上入口处已知的课程名/活动名。
 *
 * 从通知直达时没有名字，保留空串，列表用 [CxSignHistoryEntry.displayTitle] 回退。
 */
fun gestureSuccessHistory(
    activeId: String,
    courseId: String,
    clazzId: String,
    courseName: String,
    activeName: String,
    nowMs: Long,
): CxSignHistoryEntry = signResultHistory(
    activeId = activeId,
    kind = CxSignKind.CHECKIN,
    result = CxSignHistoryResult.SUCCESS,
    atMs = nowMs,
    courseName = courseName,
    activeName = activeName,
    courseId = courseId,
    clazzId = clazzId,
)

/**
 * 本账号已经签到成功的 activeId：扫描时把这些活动从「进行中」里排掉。
 *
 * activelist 不会因为已签到改 status，服务端仍一直返回 status=1；本地成功记录
 * 是目前唯一确定的依据。历史里成功/需手动/失败都可能有，只挑签到成功。
 */
fun successfulCheckinIds(history: List<CxSignHistoryEntry>): Set<String> =
    history.asSequence()
        .filter { it.kind == CxSignKind.CHECKIN && it.result == CxSignHistoryResult.SUCCESS }
        .map { it.activeId }
        .filter { it.isNotBlank() }
        .toSet()

/**
 * 历史记录编码成一行一个条目，字段内用 URLEncoder 转义，字段间用 `|`。
 *
 * 课程名来自学习通页面，可能带换行、竖线等任意字符；不转义的话解析会被名字里的
 * 分隔符带偏。不改动旧台账的 `状态首字母|次数|时间戳` 格式。
 */
fun encodeSignHistory(entries: List<CxSignHistoryEntry>): String =
    entries.joinToString("\n") { entry ->
        listOf(
            entry.activeId,
            entry.kind.name,
            entry.result.name,
            entry.atMs.toString(),
            entry.courseName,
            entry.activeName,
            entry.courseId,
            entry.clazzId,
            entry.detail,
        ).joinToString("|") { encodeField(it) }
    }

/** 坏条目直接丢弃：历史只是展示，不值得让一条脏数据拖垮整页。 */
fun decodeSignHistory(raw: String): List<CxSignHistoryEntry> {
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size < 4) return@mapNotNull null
        val kind = CxSignKind.entries.firstOrNull { it.name == parts[1] }
            ?: return@mapNotNull null
        val result = CxSignHistoryResult.entries.firstOrNull { it.name == parts[2] }
            ?: return@mapNotNull null
        val atMs = parts[3].toLongOrNull() ?: return@mapNotNull null
        CxSignHistoryEntry(
            activeId = decodeField(parts[0]),
            kind = kind,
            result = result,
            atMs = atMs,
            courseName = parts.getOrNull(4)?.let(::decodeField).orEmpty(),
            activeName = parts.getOrNull(5)?.let(::decodeField).orEmpty(),
            courseId = parts.getOrNull(6)?.let(::decodeField).orEmpty(),
            clazzId = parts.getOrNull(7)?.let(::decodeField).orEmpty(),
            detail = parts.getOrNull(8)?.let(::decodeField).orEmpty(),
        )
    }.toList()
}

private fun encodeField(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun decodeField(value: String): String = try {
    URLDecoder.decode(value, "UTF-8")
} catch (e: IllegalArgumentException) {
    ""
}