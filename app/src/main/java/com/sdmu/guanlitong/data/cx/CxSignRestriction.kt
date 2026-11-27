package com.sdmu.guanlitong.data.cx

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 解析不出解除时间时的退避：至少 30 分钟不再请求。 */
const val CX_SIGN_RESTRICTION_FALLBACK_MS = 30 * 60_000L

/** 两次完整扫描之间的最小间隔：手动刷新在间隔内复用上次结果。 */
const val CX_SIGN_SCAN_MIN_GAP_MS = 60_000L

/**
 * 学习通「限制访问」状态。
 *
 * [untilMs] 是服务端给的解除时间；解析不出时按 [CX_SIGN_RESTRICTION_FALLBACK_MS]
 * 退避。[message] 是服务端原文，用于横幅和日志。
 */
data class CxSignRestriction(val untilMs: Long, val message: String)

/** 遇到账号受限时上抛，让扫描立刻停止、轮询退避。 */
class CxSignRestrictedException(val restriction: CxSignRestriction) :
    Exception(restriction.message)

/**
 * 从 activelist 响应里识别「限制访问」。
 *
 * 典型响应：`{"result":0,"errorMsg":"您已被限制访问，解除时间 2026-11-28 14:28:03"}`。
 * 只有出现「限制访问」/「解除时间」才按受限处理；普通的 result=false 仍走原来的
 * 空列表语义。解除时间解析失败就退避 30 分钟。
 */
fun parseSignRestriction(body: String, nowMs: Long = System.currentTimeMillis()): CxSignRestriction? {
    val json = runCatching { JSONObject(body) }.getOrNull()
    val messages = if (json != null) {
        listOf("errorMsg", "error", "message", "msg").mapNotNull { key ->
            json.optString(key).takeIf { it.isNotBlank() }
        }
    } else {
        emptyList()
    }
    val hint = messages.joinToString(" ").ifBlank { body }
    val restricted = "限制访问" in hint || "解除时间" in hint
    if (!restricted) return null

    val stamp = Regex("""\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}""")
        .find(hint)?.value
    val parsed = stamp?.let { CxDateTime.parseMs(it, nowMs) }
    // 服务端给的解除时间已经过去（时钟/时区偏差）也至少退避 30 分钟再试。
    val until = parsed?.takeIf { it > nowMs } ?: (nowMs + CX_SIGN_RESTRICTION_FALLBACK_MS)
    return CxSignRestriction(untilMs = until, message = hint.take(200))
}

/** 横幅文案：`学习通限制访问，解除时间 11-28 14:28`。 */
fun restrictionBannerText(restriction: CxSignRestriction): String =
    "学习通限制访问，解除时间 ${formatRestrictionTime(restriction.untilMs)}"

fun formatRestrictionTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))

/** [nowMs] 时限制是否仍在生效。 */
fun CxSignRestriction.isActiveAt(nowMs: Long): Boolean = untilMs > nowMs