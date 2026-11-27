package com.sdmu.guanlitong.data.cx

import org.json.JSONObject

/**
 * 超星接口的成功标记：同一个 `result` / `status` 字段在不同接口、不同学校下可能
 * 是布尔、数字或字符串，不能只认 `optBoolean`。
 *
 * - 成功：`true`、非 0 数字（`1`）、`"1"`、`"true"`（大小写不敏感，允许首尾空白）；
 * - 失败：`false`、`0`、`"0"`、`"false"`、字段缺失或为 null、其它未知类型。
 *
 * 与收件箱解析的判定口径一致；activelist 实测返回 `{"result":1}`，用 `optBoolean`
 * 会退化成 false，导致每门课都被当成失败。
 */
internal fun cxSuccessFlag(raw: Any?): Boolean = when (raw) {
    is Boolean -> raw
    is Number -> raw.toDouble() != 0.0
    is String -> raw.trim().let { it == "1" || it.equals("true", ignoreCase = true) }
    else -> false
}

/** 读 JSON 顶层的成功标记；缺失或 null 用 [fallback]（默认失败）。 */
internal fun JSONObject.cxFlag(key: String, fallback: Boolean = false): Boolean {
    if (!has(key)) return fallback
    val raw = opt(key)
    if (raw == null || raw == JSONObject.NULL) return fallback
    return cxSuccessFlag(raw)
}