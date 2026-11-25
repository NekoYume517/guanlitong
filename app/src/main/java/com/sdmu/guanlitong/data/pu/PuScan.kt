package com.sdmu.guanlitong.data.pu

import org.json.JSONObject

/**
 * 扫码分类（§2.5）。
 *
 * 分类规则（顺序即优先级）：
 * 1. `{` 开头且能解析出 `code`/`mid` 或 `id`/`activityId`/`aid` → [PuScanResult.Activity]；
 * 2. 纯数字 10~16 位 → [PuScanResult.Activity]（mid = 原文，id = 0，交给页面让用户选活动）；
 * 3. `xyhui://user/` 明文 → [PuScanResult.User]（uid 必须是纯数字）；
 * 4. 合法 Base64 且 DES 解密后是 `xyhui://user/` → [PuScanResult.User]；
 * 5. 其余 → [PuScanResult.Text]（原文）。
 *
 * 这里只做**本地**判定，不发请求、不抛异常；活动归属校验在 `PuActions.scanSignIn` 里做。
 */
object PuScan {

    private const val USER_PREFIX = "xyhui://user/"
    private val UID_RE = Regex("\\d+")
    private val MID_RE = Regex("\\d{10,16}")

    fun classify(raw: String): PuScanResult {
        val s = raw.trim()
        if (s.isEmpty()) return PuScanResult.Text(raw)

        if (s.startsWith("{")) {
            parseJsonActivity(s)?.let { return it }
            return PuScanResult.Text(raw)
        }
        if (MID_RE.matches(s)) {
            return PuScanResult.Activity(mid = s, id = 0L, title = "", type = 1)
        }
        parseUser(s)?.let { return it }
        PuPersonalCode.decode(s).let { plain ->
            if (plain.isNotEmpty()) parseUser(plain)?.let { return it }
        }
        return PuScanResult.Text(raw)
    }

    /** 个人码 Base64 → 明文；非法输入返回 `""`。 */
    fun decryptPersonalCode(b64: String): String = PuPersonalCode.decode(b64)

    private fun parseUser(plain: String): PuScanResult.User? {
        if (!plain.startsWith(USER_PREFIX)) return null
        val rest = plain.removePrefix(USER_PREFIX)
        val parts = rest.split("/", limit = 3)
        val uid = parts.getOrNull(0).orEmpty().trim()
        if (!uid.matches(UID_RE)) return null
        val ts = parts.getOrNull(1).orEmpty().trim()
        val username = parts.getOrNull(2).orEmpty()
        return PuScanResult.User(uid = uid, ts = ts, username = username, plain = plain)
    }

    private fun parseJsonActivity(s: String): PuScanResult.Activity? = runCatching {
        val j = JSONObject(s)
        val mid = j.optString("code", j.optString("mid", "")).trim()
        val aid = j.optString("id", j.optString("activityId", j.optString("aid", ""))).trim()
        if (mid.isEmpty() && aid.isEmpty()) null
        else PuScanResult.Activity(
            mid = mid,
            id = aid.toLongOrNull() ?: 0L,
            title = j.optString("title", "").ifBlank { j.optString("name", "") },
            type = j.optInt("type", 1),
        )
    }.getOrNull()
}