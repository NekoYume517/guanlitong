package com.sdmu.guanlitong.data.pu

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** 服务端时间格式（§6.4）。 */
internal const val PU_FMT = "yyyy-MM-dd HH:mm:ss"

/**
 * 上游 `Actions.java` / `ApiProxy.java` 的业务动作移植。
 *
 * 冻结契约把函数体列成顶层 suspend 函数，但阶段 B 的 UI 按 `PuActions.xxx()`
 * 调用（见 ui/pu/PuCommon.kt、PuHubScreen.kt），所以这里是 `object`，
 * 函数名、参数、返回类型与 §6.3 完全一致。
 *
 * 判定文案（未报名 / 报名未开始 / 已签到，请勿重复 / 签退已截止 …）逐字沿用上游。
 */
object PuActions {

    private const val BATCH_MAX = 50

    // ------------------------------------------------------------------
    // 报名 / 签到 / 签退
    // ------------------------------------------------------------------

    /** `/apis/activity/join` 需要 X-Sign，签名库不可用时直接拒绝（§6.5）。 */
    suspend fun join(id: Long): JSONObject {
        ensureJoinSupported()
        val api = PuRuntime.requireApi()
        val result = api.api("POST", "/apis/activity/join", JSONObject().put("activityId", id))
        return JSONObject().put("ok", true).put("id", id).put("data", result)
    }

    suspend fun cancel(id: Long): JSONObject {
        val api = PuRuntime.requireApi()
        val result = api.api("POST", "/apis/activity/cancel", JSONObject().put("activityId", id))
        return JSONObject().put("ok", true).put("id", id).put("data", result)
    }

    /**
     * GPS 签到。先取详情做本地判定（`whySignIn`），判定不通过返回
     * `{ok:false, id, name, reason}`，不请求服务端——与上游一致。
     */
    suspend fun signIn(id: Long, address: String? = null): JSONObject {
        val api = PuRuntime.requireApi()
        val detail = api.api("POST", "/apis/activity/info", JSONObject().put("id", id))
        val base = detail.optJSONObject("baseInfo") ?: JSONObject()
        val status = detail.optJSONObject("userStatus") ?: JSONObject()
        val name = base.optStringLoose("name")
        val why = whySignIn(base, status)
        if (why.isNotEmpty()) {
            return JSONObject().put("ok", false).put("id", id).put("name", name).put("reason", why)
        }

        var addr = address?.trim().orEmpty()
        if (addr.isEmpty()) addr = base.optStringLoose("signPlace")
        if (addr.isEmpty()) addr = base.optStringLoose("address")
        if (addr.isEmpty()) addr = "定位签到"

        val body = JSONObject().put("activityId", id).put("signInAddress", addr)
        val lat = base.optStringLoose("signLat")
        val lng = base.optStringLoose("signLong")
        if (lat.isNotEmpty() && lng.isNotEmpty()) {
            body.put("signLat", lat).put("signLong", lng)
        }
        val result = api.api("POST", "/apis/activity/signInGps", body)
        return JSONObject().put("ok", true).put("id", id).put("name", name)
            .put("addr", addr).put("result", result)
    }

    suspend fun signOut(id: Long, address: String? = null): JSONObject {
        val api = PuRuntime.requireApi()
        val detail = api.api("POST", "/apis/activity/info", JSONObject().put("id", id))
        val base = detail.optJSONObject("baseInfo") ?: JSONObject()
        val status = detail.optJSONObject("userStatus") ?: JSONObject()
        val name = base.optStringLoose("name")
        val why = whySignOut(base, status)
        if (why.isNotEmpty()) {
            return JSONObject().put("ok", false).put("id", id).put("name", name).put("reason", why)
        }

        var addr = address?.trim().orEmpty()
        if (addr.isEmpty()) addr = base.optStringLoose("signPlace")
        if (addr.isEmpty()) addr = base.optStringLoose("address")
        if (addr.isEmpty()) addr = "定位签到"

        val body = JSONObject().put("activityId", id).put("signInAddress", addr)
        val result = api.api("POST", "/apis/activity/signOutGps", body)
        return JSONObject().put("ok", true).put("id", id).put("name", name)
            .put("addr", addr).put("result", result)
    }

    suspend fun signStatus(id: Long): PuSignStatus {
        val api = PuRuntime.requireApi()
        val data = api.api("POST", "/apis/activity/sign/result", JSONObject().put("activityId", id))
        return PuSignStatus(
            isSignIn = data.optIntLoose("isSignIn") == 1,
            signInTime = data.optStringLoose("signInTime"),
            signInAddress = data.optStringLoose("signInAddress"),
            isSignOut = data.optIntLoose("isSignOut") == 1,
            signOutTime = data.optStringLoose("signOutTime"),
        )
    }

    suspend fun canSignList(): List<PuActivity> {
        val api = PuRuntime.requireApi()
        val data = api.api("GET", "/apis/activity/canSignList", null)
        val arr = data.optJSONArray("list") ?: data.optJSONArray("activityList") ?: return emptyList()
        val out = ArrayList<PuActivity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.toActivity())
        }
        return out
    }

    /**
     * 主办方签到码。
     *
     * - `mid` 优先用服务端毫秒时间戳（`/apis/system/time`），拿不到才用本机时钟（`src=local`）；
     * - payload 按「full → 截断 title（level=cut）→ slim」逐级降级，保证 UTF-8 ≤ 120 字节（§6.4）；
     * - `code` 是 payload 里的 `code` 值（毫秒时间戳字符串），`payload` 是要渲染进二维码的 JSON。
     */
    suspend fun signCode(id: Long): PuSignCode {
        val api = PuRuntime.requireApi()
        val detail = api.api("POST", "/apis/activity/info", JSONObject().put("id", id))
        val base = detail.optJSONObject("baseInfo") ?: JSONObject()
        val name = base.optStringLoose("name")

        var mid = sysTimeMs(api)
        val src = if (mid > 0L) "server" else "local"
        if (mid <= 0L) mid = System.currentTimeMillis()
        val refresh = qrRefresh(api)

        val full = JSONObject()
            .put("code", mid.toString())
            .put("id", id.toString())
            .put("title", name)
            .put("type", 1)
        val slim = JSONObject()
            .put("code", mid.toString())
            .put("id", id.toString())
            .put("type", 1)
        val fitted = fitSignPayload(full, slim)

        return PuSignCode(
            code = mid.toString(),
            mid = mid.toString(),
            payload = fitted.json,
            level = fitted.level,
            src = src,
            refresh = refresh,
        )
    }

    /**
     * 扫签到码签到：签到码里的活动 id（如果有）必须与当前活动一致；
     * `mid` 能转数字时按 int64 发，否则按字符串发（§2.6 第 8 条）。
     */
    suspend fun scanSignIn(id: Long, raw: String): JSONObject {
        val api = PuRuntime.requireApi()
        val detail = api.api("POST", "/apis/activity/info", JSONObject().put("id", id))
        val base = detail.optJSONObject("baseInfo") ?: JSONObject()
        val status = detail.optJSONObject("userStatus") ?: JSONObject()
        val name = base.optStringLoose("name")
        val why = whySignIn(base, status)
        if (why.isNotEmpty()) {
            return JSONObject().put("ok", false).put("id", id).put("name", name).put("reason", why)
        }

        val (mid, aid) = parseMid(raw)
        if (mid.isEmpty()) throw PuException("签到码为空", 400)
        if (aid.isNotEmpty() && aid != id.toString()) {
            throw PuException("签到码属于活动 $aid，与当前活动 $id 不一致", 400)
        }

        val body = JSONObject().put("activityId", id)
        val midNum = mid.toLongOrNull() ?: 0L
        if (midNum > 0L) body.put("mid", midNum) else body.put("mid", mid)
        val result = api.api("POST", "/apis/activity/signIn", body)
        return JSONObject().put("ok", true).put("id", id).put("name", name)
            .put("mode", "qr").put("mid", mid).put("result", result)
    }

    /** 活动码（数字时间戳）签到，走 GET + 查询串，地址 / 经纬度 / 地点按上游顺序补齐。 */
    suspend fun codeSignIn(id: Long, code: String, address: String? = null): JSONObject {
        val api = PuRuntime.requireApi()
        val detail = api.api("POST", "/apis/activity/info", JSONObject().put("id", id))
        val base = detail.optJSONObject("baseInfo") ?: JSONObject()
        val status = detail.optJSONObject("userStatus") ?: JSONObject()
        val name = base.optStringLoose("name")
        val why = whySignIn(base, status)
        if (why.isNotEmpty()) {
            return JSONObject().put("ok", false).put("id", id).put("name", name).put("reason", why)
        }

        val ts = tsMs(code)
        if (ts <= 0L) throw PuException("活动码必须是数字时间戳（10/13 位）", 400)

        var addr = address?.trim().orEmpty()
        if (addr.isEmpty()) addr = base.optStringLoose("signPlace")
        if (addr.isEmpty()) addr = base.optStringLoose("address")
        if (addr.isEmpty()) addr = "定位签到"

        val qs = StringBuilder("activityId=").append(id)
            .append("&timestamp=").append(ts)
            .append("&signInAddress=").append(encode(addr))
        val lat = base.optStringLoose("signLat")
        val lng = base.optStringLoose("signLong")
        if (lat.isNotEmpty()) qs.append("&latitude=").append(lat)
        if (lng.isNotEmpty()) qs.append("&longitude=").append(lng)
        val place = base.optStringLoose("signPlace")
        if (place.isNotEmpty()) qs.append("&signPlace=").append(encode(place))

        val result = api.api("GET", "/apis/activity/signIn/activity/code?$qs", null)
        return JSONObject().put("ok", true).put("id", id).put("name", name)
            .put("mode", "code").put("timestamp", ts).put("result", result)
    }

    /** 批量签到：`ids` 为空时自动取「未签到活动」；结果逐条汇总。 */
    suspend fun autoSign(ids: Long[]?, address: String? = null): JSONObject {
        val targets: List<Long> = if (!ids.isNullOrEmpty()) {
            ids.filter { it > 0L }
        } else {
            canSignList().map { it.id }.filter { it > 0L }
        }

        val results = JSONArray()
        var okCount = 0
        var failCount = 0
        var skipCount = 0

        for (id in targets) {
            val r = try {
                signIn(id, address)
            } catch (e: PuException) {
                if (e.code == 401) throw e
                JSONObject().put("ok", false).put("id", id)
                    .put("reason", e.message?.takeIf { it.isNotBlank() } ?: "签到失败")
            }
            results.put(r)
            when {
                r.optBoolean("ok", false) -> okCount++
                r.optString("reason", "").startsWith("已签到") -> skipCount++
                else -> failCount++
            }
        }

        return JSONObject().put("ok", true).put("total", targets.size)
            .put("okCount", okCount).put("failCount", failCount)
            .put("skipCount", skipCount).put("results", results)
    }

    /**
     * 批量报名：先逐条取详情跑 `whyJoin`，已报名记 skip、其余记 fail；
     * 通过判定才真正 POST join。`join` 依赖 X-Sign，库不可用时整批拒绝（§6.5）。
     */
    suspend fun autoJoin(ids: Long[]): JSONObject {
        ensureJoinSupported()
        if (ids.isEmpty()) throw PuException("缺少要报名的活动", 400)
        if (ids.size > BATCH_MAX) throw PuException("一次最多 $BATCH_MAX 个活动", 400)

        val api = PuRuntime.requireApi()
        val results = JSONArray()
        var okCount = 0
        var failCount = 0
        var skipCount = 0
        val targets = ids.filter { it > 0L }.distinct()

        for (id in targets) {
            try {
                val detail = api.api("POST", "/apis/activity/info", JSONObject().put("id", id))
                val base = detail.optJSONObject("baseInfo") ?: JSONObject()
                val status = detail.optJSONObject("userStatus") ?: JSONObject()
                val name = base.optStringLoose("name")
                val why = whyJoin(base, status)
                if (why.isNotEmpty()) {
                    results.put(
                        JSONObject().put("ok", false).put("id", id)
                            .put("name", name).put("reason", why)
                    )
                    if (why == "已报名") skipCount++ else failCount++
                    continue
                }
                api.api("POST", "/apis/activity/join", JSONObject().put("activityId", id))
                results.put(JSONObject().put("ok", true).put("id", id).put("name", name))
                okCount++
            } catch (e: PuException) {
                if (e.code == 401) throw e
                results.put(
                    JSONObject().put("ok", false).put("id", id)
                        .put("reason", e.message?.takeIf { it.isNotBlank() } ?: "报名失败")
                )
                failCount++
            }
        }

        return JSONObject().put("ok", true).put("total", targets.size)
            .put("okCount", okCount).put("failCount", failCount)
            .put("skipCount", skipCount).put("results", results)
    }

    // ------------------------------------------------------------------
    // 账号 / 个人码 / 学时 / 列表
    // ------------------------------------------------------------------

    /** `/uc/user/infocurr` + `POST /apis/personal/credit`。`PuUser.mobile` 已脱敏。 */
    suspend fun me(): Pair<PuUser, PuCreditTotal> {
        val api = PuRuntime.requireApi()
        val profile = api.api("GET", "/uc/user/infocurr", null)
        val creditRaw = api.api("POST", "/apis/personal/credit", JSONObject())
        return profile.toUser() to PuCreditTotal(creditRaw.optDoubleLoose("totalCredit"), creditRaw)
    }

    /** 个人二维码：uid / username 取 `infocurr`，时间戳取服务端秒。 */
    suspend fun personalCode(): PuPersonalCode {
        val api = PuRuntime.requireApi()
        val profile = api.api("GET", "/uc/user/infocurr", null)
        val uid = profile.optStringLoose("id").ifBlank { profile.optStringLoose("uid") }
        val username = profile.optStringLoose("username")
        if (uid.isEmpty() || username.isEmpty()) {
            throw PuException("用户信息缺少 id/username，无法生成个人码", 400)
        }
        val ts = serverSeconds(api)
        val refresh = qrRefresh(api)
        val code = try {
            PuPersonalCode.encode(uid, username, ts)
        } catch (e: Throwable) {
            throw PuException("个人码加密失败: ${e.message ?: e.javaClass.simpleName}", 500)
        }
        return PuPersonalCode(
            uid = uid,
            username = username,
            code = code,
            ts = ts,
            refresh = refresh,
            name = profile.optStringLoose("realname"),
        )
    }

    /**
     * 学时汇总。
     *
     * 上游两个数不一致是已知事实：`totalCredit`（53.9）≠ 明细求和（58），
     * 所以两个都返回（`total.total` 与 `rawSum`），页面标注来源（§2.6）。
     */
    suspend fun credit(): PuCreditSummary {
        val api = PuRuntime.requireApi()
        val totalRaw = api.api("POST", "/apis/personal/credit", JSONObject())
        val total = PuCreditTotal(totalRaw.optDoubleLoose("totalCredit"), totalRaw)
        val finished = api.collectMine(4).list.sortedWith(compareByDescending { it.startTime })
        val unfinished = api.collectMine(3).list
        val rawSum = finished.sumOf { it.credit }

        val counts = HashMap<String, Int>()
        val sums = HashMap<String, Double>()
        finished.forEach { a ->
            val year = a.startTime.take(4).takeIf { it.matches(Regex("\\d{4}")) } ?: "未知"
            counts[year] = (counts[year] ?: 0) + 1
            sums[year] = (sums[year] ?: 0.0) + a.credit
        }
        val years = counts.keys.sortedDescending().map {
            PuCreditYear(year = it, count = counts.getValue(it), sum = sums[it] ?: 0.0)
        }

        return PuCreditSummary(
            total = total,
            finished = finished,
            unfinished = unfinished,
            years = years,
            rawSum = rawSum,
        )
    }

    /** 我的活动。`all = true` 时拉全量（学时明细用，内部有 100 页上限）。 */
    suspend fun mine(type: Int, page: Int = 1, limit: Int = 20, all: Boolean = false): PuPage {
        val api = PuRuntime.requireApi()
        return if (all) api.collectMine(type) else api.minePage(type, page, limit)
    }

    /**
     * 活动列表。`grep` 非空时按上游做「全量扫描 + 名称包含」：
     * 逐页取 200 条直到 `已取 >= count` 或 `page >= total`，再按 [page]/[limit] 切片。
     */
    suspend fun activities(page: Int = 1, limit: Int = 20, grep: String = ""): PuPage {
        val api = PuRuntime.requireApi()
        val pg = page.coerceAtLeast(1)
        val lm = limit.coerceIn(1, 100)
        val key = grep.trim()

        if (key.isEmpty()) {
            val body = JSONObject().put("page", pg).put("limit", lm)
            return api.fetch("/apis/activity/list", body)
        }

        val hits = ArrayList<PuActivity>()
        var p = 1
        var fetchedTotal = 0
        while (p <= 100) {
            val data = api.api(
                "POST",
                "/apis/activity/list",
                JSONObject().put("page", p).put("limit", 200),
            )
            val arr = data.optJSONArray("list") ?: JSONArray()
            val info = data.optJSONObject("pageInfo")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                fetchedTotal++
                val activity = o.toActivity()
                if (activity.name.contains(key)) hits.add(activity)
            }
            val count = info?.optInt("count", 0) ?: 0
            val total = maxOf(1, info?.optInt("total", 1) ?: 1)
            if (arr.length() == 0) break
            if (count > 0 && fetchedTotal >= count) break
            if (p >= total) break
            p++
        }

        val count = hits.size
        val from = ((pg - 1) * lm).coerceAtMost(count)
        val to = (from + lm).coerceAtMost(count)
        return PuPage(
            list = hits.subList(from, to),
            count = count,
            totalPages = maxOf(1, ceil(count.toDouble() / lm).toInt()),
        )
    }

    /** 活动详情：`baseInfo` → [PuActivity]，`id` 按 int64 数字发送（§2.6）。 */
    suspend fun activityDetail(id: Long): PuActivity {
        val api = PuRuntime.requireApi()
        val data = api.api("POST", "/apis/activity/info", JSONObject().put("id", id))
        val base = data.optJSONObject("baseInfo") ?: data
        return base.toActivity(idFallback = id)
    }

    // ------------------------------------------------------------------
    // 好友
    // ------------------------------------------------------------------

    suspend fun otherUser(uid: String): PuFriend {
        val api = PuRuntime.requireApi()
        val u = uid.trim()
        if (!u.matches(Regex("\\d+"))) throw PuException("缺少或非法的参数 uid", 400)
        val data = api.api("GET", "/apis/other/index/$u", null)
        val user = data.optJSONObject("user") ?: data
        val friend = user.toFriend()
        return friend.copy(uid = friend.uid.ifBlank { u })
    }

    suspend fun follow(uid: String): Boolean {
        val api = PuRuntime.requireApi()
        val u = uid.trim()
        if (u.isEmpty()) throw PuException("缺少或非法的参数 uid", 400)
        api.api("GET", "/apis/relations/follow/$u", null)
        return true
    }

    suspend fun unfollow(uid: String): Boolean {
        val api = PuRuntime.requireApi()
        val u = uid.trim()
        if (u.isEmpty()) throw PuException("缺少或非法的参数 uid", 400)
        api.api("GET", "/apis/relations/unfollow/$u", null)
        return true
    }

    /**
     * 好友列表。`type` ∈ friend / followee / follower，`page` 必填，`limit` 1..100。
     *
     * 返回类型是冻结契约的 [PuPage]（元素只能是 [PuActivity] 外壳），
     * 页面用 `PuActivity.asFriend()` 取回 [PuFriend]；需要强类型可直接用 [friendList]。
     */
    suspend fun friends(type: String = "friend", page: Int = 1, limit: Int = 30): PuPage {
        val api = PuRuntime.requireApi()
        val t = type.trim()
        if (t != "friend" && t != "followee" && t != "follower") {
            throw PuException("type 必须是 friend / followee / follower", 400)
        }
        val pg = page.coerceAtLeast(1)
        val lm = limit.coerceIn(1, 100)
        val data = api.api("GET", "/apis/relations/$t/list?page=$pg&limit=$lm", null)
        return friendPageOf(data.optJSONArray("list"), data.optJSONObject("pageInfo"))
    }

    /** [friends] 的强类型版本。 */
    suspend fun friendList(type: String = "friend", page: Int = 1, limit: Int = 30): List<PuFriend> =
        friends(type, page, limit).list.map { it.asFriend() }

    // ------------------------------------------------------------------
    // 内部工具（internal 供单元测试直接调用）
    // ------------------------------------------------------------------

    /** 毫秒时间戳归一：10 位以下数字 ×1000；13 位原样；≥19 字符按 `yyyy-MM-dd HH:mm:ss` 解析。 */
    internal fun tsMs(value: String?): Long {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return 0L
        s.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000L else it }
        if (s.length >= 19) {
            return runCatching {
                val f = SimpleDateFormat(PU_FMT, Locale.CHINA)
                f.isLenient = false
                f.parse(s.substring(0, 19))?.time ?: 0L
            }.getOrDefault(0L)
        }
        return 0L
    }

    /**
     * 解析签到码 → `(mid, aid)`。
     *
     * - JSON：`code`/`mid` + `id`/`activityId`；
     * - 逗号 / 竖线：**在最后一个分隔符处切**，后面的当 mid、前面的当 aid
     *   （上游 `Actions.java` 的实际行为，即真实格式是 `aid,mid`；
     *   注意 §7.5 的示例把顺序写反了，这里以上游代码为准）；
     * - 纯数字：整串当 mid，aid 为空。
     */
    internal fun parseMid(raw: String?): Pair<String, String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return "" to ""
        if (s.startsWith("{") || s.startsWith("[")) {
            return runCatching {
                val j = JSONObject(s)
                val mid = j.optString("code", j.optString("mid", "")).trim()
                val aid = j.optString("id", j.optString("activityId", "")).trim()
                mid to aid
            }.getOrDefault("" to "")
        }
        for (sep in listOf(",", "|")) {
            val k = s.lastIndexOf(sep)
            if (k > 0) return s.substring(k + 1).trim() to s.substring(0, k).trim()
        }
        return s to ""
    }

    /** 刷新间隔钳制 5..600，其余一律 30（§6.4）。 */
    internal fun clampRefresh(value: Int): Int = if (value in 5..600) value else 30

    internal fun utf8Bytes(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    internal data class FittedSignPayload(val json: String, val level: String)

    /**
     * 签到码 payload 降级：full → 截断 title（`cut`）→ slim。
     * 保证返回的 JSON UTF-8 字节数 ≤ [maxBytes]（默认 120）。
     */
    internal fun fitSignPayload(
        full: JSONObject,
        slim: JSONObject,
        maxBytes: Int = 120,
    ): FittedSignPayload {
        val fullText = full.toString()
        if (utf8Bytes(fullText) <= maxBytes) return FittedSignPayload(fullText, "full")

        val slimText = slim.toString()
        if (utf8Bytes(slimText) > maxBytes) return FittedSignPayload(slimText, "slim")

        var title = full.optString("title", "")
        while (title.length > 1) {
            val cut = title.substring(0, title.length - 1) + "…"
            val candidate = JSONObject(full.toString()).put("title", cut).toString()
            if (utf8Bytes(candidate) <= maxBytes) return FittedSignPayload(candidate, "cut")
            var end = title.length - 1
            if (end > 0 && Character.isLowSurrogate(title[end])) end--
            if (end <= 0) break
            title = title.substring(0, end)
        }
        return FittedSignPayload(slimText, "slim")
    }

    // ------------------------------------------------------------------
    // 私有：上游判定逻辑
    // ------------------------------------------------------------------

    private fun ensureJoinSupported() {
        if (!PuSign.available()) {
            throw PuException("当前设备架构不支持报名，请使用官方 App 报名", 0)
        }
    }

    private fun whyJoin(base: JSONObject, status: JSONObject): String {
        if (status.optIntLoose("hasJoin") == 1) return "已报名"
        val now = now()
        val start = base.optStringLoose("joinStartTime")
        val end = base.optStringLoose("joinEndTime")
        if (start.isNotEmpty() && now < start) return "报名未开始（$start 起）"
        if (end.isNotEmpty() && now > end) return "报名已截止（$end）"
        return ""
    }

    private fun whySignIn(base: JSONObject, status: JSONObject): String {
        if (status.optIntLoose("hasJoin") != 1) return "未报名"
        if (status.optIntLoose("hasSignIn") == 1) return "已签到，请勿重复"
        return signWindow(base)
    }

    private fun whySignOut(base: JSONObject, status: JSONObject): String {
        if (status.optIntLoose("hasJoin") != 1) return "未报名"
        if (status.optIntLoose("hasSignIn") != 1) return "尚未签到"
        if (status.optIntLoose("hasSignOut") == 1) return "已签退，请勿重复"

        val end = base.optStringLoose("endTime")
        var start = base.optStringLoose("signOutStartTime")
        if (start.isEmpty() && end.isNotEmpty()) {
            start = shift(end, -base.optIntLoose("canSignOutTime"))
        }
        // 签退截止时间固定为「活动结束 + 60 分钟」（§6.4 SIGN_OUT_GRACE_MIN）。
        val stop = if (end.isEmpty()) "" else shift(end, 60)
        val now = now()
        if (start.isNotEmpty() && now < start) return "签退未开始（$start 起）"
        if (stop.isNotEmpty() && now > stop) return "签退已截止（$stop）"
        return ""
    }

    private fun signWindow(base: JSONObject): String {
        val start = base.optStringLoose("signStartTime").ifBlank { base.optStringLoose("startTime") }
        val end = base.optStringLoose("endTime")
        val now = now()
        if (start.isNotEmpty() && now < start) return "签到未开始（$start 起）"
        if (end.isNotEmpty() && now > end) return "活动已结束（$end）"
        return ""
    }

    private fun now(): String = SimpleDateFormat(PU_FMT, Locale.CHINA).format(Date())

    /** 时间字符串 ± 分钟；解析失败原样返回（上游 `shift` 的行为）。 */
    private fun shift(ts: String, minutes: Int): String {
        if (ts.isBlank()) return ts
        return runCatching {
            val f = SimpleDateFormat(PU_FMT, Locale.CHINA)
            f.isLenient = false
            val d = f.parse(ts) ?: return@runCatching ts
            val cal = Calendar.getInstance()
            cal.time = d
            cal.add(Calendar.MINUTE, minutes)
            SimpleDateFormat(PU_FMT, Locale.CHINA).format(cal.time)
        }.getOrDefault(ts)
    }

    private suspend fun sysTimeMs(api: PuApi): Long = try {
        tsMs(api.api("GET", "/apis/system/time", null).optString("timestamp", ""))
    } catch (e: PuException) {
        if (e.code == 401) throw e
        0L
    }

    private suspend fun qrRefresh(api: PuApi): Int = try {
        clampRefresh(api.api("GET", "/apis/activity/qrCode/time", null).optInt("time", 0))
    } catch (e: PuException) {
        if (e.code == 401) throw e
        30
    }

    private suspend fun serverSeconds(api: PuApi): Long {
        val ms = sysTimeMs(api)
        return if (ms > 0L) ms / 1000L else System.currentTimeMillis() / 1000L
    }

    /** `Uri.encode` 的等价实现（空格用 %20，不用 +），避免依赖 android 框架。 */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}