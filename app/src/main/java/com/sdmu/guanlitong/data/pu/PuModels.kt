package com.sdmu.guanlitong.data.pu

import org.json.JSONArray
import org.json.JSONObject

/**
 * PU 领域模型。
 *
 * 字段名与 docs/INTEGRATION-PU.md §6.3 冻结签名逐字一致，不要改名、不要增减字段。
 * 所有带 `raw` 的模型都保留上游原始 JSON，页面需要未建模字段时直接读 `raw`。
 */

data class PuSession(
    val token: String,
    val goId: String,
    val name: String,
    val account: String,
)

data class PuPage(
    val list: List<PuActivity>,
    val count: Int,
    val totalPages: Int,
)

data class PuActivity(
    val id: Long,
    val name: String,
    val startTime: String,
    val endTime: String,
    val joinStartTime: String,
    val joinEndTime: String,
    val signStartTime: String,
    val signOutStartTime: String,
    val address: String,
    val signPlace: String,
    val credit: Double,
    val canSignOutTime: Int,
    val raw: JSONObject,
)

data class PuCreditTotal(
    val total: Double,
    val raw: JSONObject,
)

data class PuCreditYear(
    val year: String,
    val count: Int,
    val sum: Double,
)

data class PuCreditSummary(
    val total: PuCreditTotal,
    val finished: List<PuActivity>,
    val unfinished: List<PuActivity>,
    val years: List<PuCreditYear>,
    val rawSum: Double,
)

data class PuUser(
    val id: String,
    val username: String,
    val realname: String,
    val mobile: String,
    val school: String,
    val grade: String,
)

data class PuFriend(
    val uid: String,
    val nickname: String,
    val avatar: String,
    val isFollower: Boolean,
    val isFollowee: Boolean,
    val raw: JSONObject,
)

data class PuPersonalCode(
    val uid: String,
    val username: String,
    val code: String,
    val ts: Long,
    val refresh: Int,
    val name: String,
)

data class PuSignStatus(
    val isSignIn: Boolean,
    val signInTime: String,
    val signInAddress: String,
    val isSignOut: Boolean,
    val signOutTime: String,
)

data class PuSignCode(
    val code: String,
    val mid: String,
    val payload: String,
    val level: String,
    val src: String,
    val refresh: Int,
)

sealed interface PuScanResult {
    data class User(
        val uid: String,
        val ts: String,
        val username: String,
        val plain: String,
    ) : PuScanResult

    data class Activity(
        val mid: String,
        val id: Long,
        val title: String,
        val type: Int,
    ) : PuScanResult

    data class Text(val raw: String) : PuScanResult
}

data class PuSignState(
    val libLoaded: Boolean,
    val sign: String,
    val ua: String,
)

// ---------------------------------------------------------------------------
// 上游 JSON → 模型
//
// 上游字段类型不统一（同一字段有时是数字、有时是字符串），下面三个 `opt*Loose`
// 统一按「数字 / 数字字符串 / 其余置默认值」处理，避免 `optDouble` 拿到 NaN。
// ---------------------------------------------------------------------------

internal fun JSONObject.optStringLoose(key: String): String {
    if (!has(key) || isNull(key)) return ""
    return optString(key, "").trim()
}

internal fun JSONObject.optDoubleLoose(key: String): Double {
    if (!has(key) || isNull(key)) return 0.0
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}

internal fun JSONObject.optIntLoose(key: String): Int {
    if (!has(key) || isNull(key)) return 0
    return when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: 0
        is Boolean -> if (v) 1 else 0
        else -> 0
    }
}

internal fun JSONObject.optBoolLoose(key: String): Boolean {
    if (!has(key) || isNull(key)) return false
    return when (val v = opt(key)) {
        is Boolean -> v
        is Number -> v.toInt() == 1
        is String -> v.trim().equals("1") || v.trim().equals("true", ignoreCase = true)
        else -> false
    }
}

/**
 * 列表项 / `baseInfo` → [PuActivity]。
 *
 * `id` 先取 `id`，再取 `activityId`，都取不到用 [idFallback]（详情页会把请求参数传进来）。
 */
internal fun JSONObject.toActivity(idFallback: Long = 0L): PuActivity {
    val id = optLong("id", 0L).takeIf { it > 0L }
        ?: optLong("activityId", 0L).takeIf { it > 0L }
        ?: idFallback
    return PuActivity(
        id = id,
        name = optStringLoose("name").ifBlank { optStringLoose("title") },
        startTime = optStringLoose("startTime"),
        endTime = optStringLoose("endTime"),
        joinStartTime = optStringLoose("joinStartTime"),
        joinEndTime = optStringLoose("joinEndTime"),
        signStartTime = optStringLoose("signStartTime"),
        signOutStartTime = optStringLoose("signOutStartTime"),
        address = optStringLoose("address"),
        signPlace = optStringLoose("signPlace"),
        credit = optDoubleLoose("credit"),
        canSignOutTime = optIntLoose("canSignOutTime"),
        raw = this,
    )
}

/** `/uc/user/infocurr`（或登录返回里的 `baseUserInfo`）→ [PuUser]。手机号在这里就脱敏。 */
internal fun JSONObject.toUser(): PuUser {
    val base = optJSONObject("baseUserInfo") ?: this
    return PuUser(
        id = base.optStringLoose("id").ifBlank { base.optStringLoose("uid") },
        username = base.optStringLoose("username").ifBlank { base.optStringLoose("account") },
        realname = base.optStringLoose("realname").ifBlank { base.optStringLoose("nickname") },
        mobile = mask(base.optStringLoose("mobile").ifBlank { base.optStringLoose("phone") }),
        school = base.optStringLoose("school").ifBlank { base.optStringLoose("schoolName") },
        grade = base.optStringLoose("grade").ifBlank { base.optStringLoose("gradeName") },
    )
}

internal fun JSONObject.friendUid(): String = optStringLoose("uid")
    .ifBlank { optStringLoose("id") }
    .ifBlank { optStringLoose("userId") }

internal fun JSONObject.toFriend(): PuFriend = PuFriend(
    uid = friendUid(),
    nickname = optStringLoose("nickname").ifBlank { optStringLoose("name") },
    avatar = optStringLoose("avatar").ifBlank { optStringLoose("headImg") },
    isFollower = optBoolLoose("isFollower"),
    isFollowee = optBoolLoose("isFollowee"),
    raw = this,
)

/**
 * `/apis/relations/*/list` 的条目 → [PuActivity] 外壳。
 *
 * 冻结签名把 `friends()` 的返回类型定为 `PuPage`（元素只能是 [PuActivity]），
 * 所以这里把好友压成「id = 数字 uid 或 0，name = 昵称，raw = 好友 JSON」的外壳，
 * 页面用 [asFriend] 取回 [PuFriend]。
 */
internal fun JSONObject.toFriendActivity(): PuActivity {
    val uid = friendUid()
    return PuActivity(
        id = uid.toLongOrNull() ?: 0L,
        name = optStringLoose("nickname").ifBlank { optStringLoose("name") },
        startTime = "",
        endTime = "",
        joinStartTime = "",
        joinEndTime = "",
        signStartTime = "",
        signOutStartTime = "",
        address = optStringLoose("school").ifBlank { optStringLoose("schoolName") },
        signPlace = optStringLoose("avatar").ifBlank { optStringLoose("headImg") },
        credit = 0.0,
        canSignOutTime = 0,
        raw = this,
    )
}

internal fun PuActivity.asFriend(): PuFriend = raw.toFriend()

/**
 * `list` + `pageInfo` → [PuPage]。
 *
 * 上游 `pageInfo.count` 是**记录总数**，`pageInfo.total` 才是**页数**（§2.6）。
 * `pageInfo` 缺失时按单页处理。
 */
internal fun pageOf(arr: JSONArray?, info: JSONObject?): PuPage {
    val items = ArrayList<PuActivity>(arr?.length() ?: 0)
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(o.toActivity())
        }
    }
    val count = info?.optInt("count", items.size) ?: items.size
    val total = info?.optInt("total", 1) ?: 1
    return PuPage(list = items, count = count, totalPages = maxOf(1, total))
}

internal fun friendPageOf(arr: JSONArray?, info: JSONObject?): PuPage {
    val items = ArrayList<PuActivity>(arr?.length() ?: 0)
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(o.toFriendActivity())
        }
    }
    val count = info?.optInt("count", items.size) ?: items.size
    val total = info?.optInt("total", 1) ?: 1
    return PuPage(list = items, count = count, totalPages = maxOf(1, total))
}