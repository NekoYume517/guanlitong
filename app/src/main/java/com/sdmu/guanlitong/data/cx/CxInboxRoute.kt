package com.sdmu.guanlitong.data.cx

import java.net.URI
import java.net.URLDecoder

/** 收件箱详情页按类型走哪条路。 */
enum class CxInboxAction {
    /** 签到活动：展示活动信息 +「点击签到」（开了自动签到则展示自动处理状态）。 */
    SIGN,

    /** 作业：交给独立的作业答题页（由集成轮接路由）。 */
    WORK,

    /** 其它带链接的通知：只读展示 + 打开链接。 */
    LINK,

    /** 没有链接也没有特殊类型：只读。 */
    NONE,
}

/**
 * 路由规则（验收口径）：`sourceType=2`（`inbox_checkin`）或 `actionUrl` 含 `sign`
 * 走签到；作业（`inbox_work`）走作业页；其余带链接的只读。
 */
fun inboxAction(ntype: String, actionUrl: String?): CxInboxAction {
    val url = actionUrl.orEmpty()
    if (ntype == CxInboxType.CHECKIN.ntype || url.contains("sign", ignoreCase = true)) {
        return CxInboxAction.SIGN
    }
    if (ntype == CxInboxType.WORK.ntype) return CxInboxAction.WORK
    return if (url.isNotBlank()) CxInboxAction.LINK else CxInboxAction.NONE
}

/** 详情页展示用的类型名。 */
fun inboxTypeLabel(ntype: String): String =
    CxInboxType.entries.firstOrNull { it.ntype == ntype }?.label ?: "通知"

/** 从签到通知的 actionUrl 里解析课程/班级/活动 id。 */
data class CxSignTarget(
    val courseId: String,
    val clazzId: String,
    val activeId: String,
)

/**
 * 兼容老项目与 H5 页面常见的参数名：`courseId/courseid`、`classId/clazzId/clazzid`、
 * `activeId/id/activePrimaryId`。缺课程或班级就返回 null（详情页会提示到课程里签到）。
 */
fun parseSignTarget(actionUrl: String?): CxSignTarget? {
    val url = actionUrl?.trim().orEmpty()
    if (url.isEmpty()) return null
    val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
    if (query.isEmpty()) return null

    val params = LinkedHashMap<String, String>()
    for (part in query.split('&')) {
        val index = part.indexOf('=')
        if (index <= 0) continue
        val key = decode(part.substring(0, index))
        val value = decode(part.substring(index + 1))
        if (key.isNotBlank() && value.isNotBlank()) params[key] = value
    }

    fun pick(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> params[key]?.takeIf { it.isNotBlank() } }

    val courseId = pick("courseId", "courseid", "course_id") ?: return null
    val clazzId = pick("classId", "clazzid", "clazzId", "classid") ?: return null
    val activeId = pick("activeId", "activeid", "id", "activePrimaryId", "activePrimaryid").orEmpty()
    return CxSignTarget(courseId, clazzId, activeId)
}

private fun decode(raw: String): String =
    runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)