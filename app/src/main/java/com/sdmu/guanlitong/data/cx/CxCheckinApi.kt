package com.sdmu.guanlitong.data.cx

import android.util.Log
import com.sdmu.guanlitong.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** activeType=74 是签退活动，2 是签到活动。 */
private const val ACTIVE_TYPE_CHECKIN = 2
private const val ACTIVE_TYPE_CHECKOUT = 74

/**
 * 一条签到/签退活动。
 *
 * [otherId] 表示签到方式（0 普通 / 2 二维码 / 3 手势 / 4 位置 / 5 拍照）；
 * [address] 是活动自带的签到地点，可能为空。
 */
data class CxActive(
    val id: String,
    val name: String,
    val activeType: Int,
    val status: Int,
    val otherId: String,
    val startTime: String,
    val endTime: String,
    val address: String = "",
) {
    val isCheckout: Boolean get() = activeType == ACTIVE_TYPE_CHECKOUT
    val isCheckin: Boolean get() = activeType == ACTIVE_TYPE_CHECKIN

    /** otherId 缺省按普通签到（0）处理，与老项目 `int(act.get('otherId') or 0)` 一致。 */
    val otherIdValue: Int get() = otherId.trim().toIntOrNull() ?: 0
}

/** 这条活动该怎么处理。 */
enum class CxSignMode {
    /** 普通签到/签退（otherId 0 或未标注）。 */
    NORMAL,

    /** 位置签到/签退：三点定位。 */
    LOCATION,

    /**
     * 二维码/拍照/手势签到等只能在手机 App 上由用户当场完成；手势签到不再自动
     * 提交，统一走 [CxGestureSignScreen] 手划。
     */
    MANUAL,
}

/** 一条签到结果。 */
data class CxSignResult(
    val success: Boolean,
    val message: String,
    val needManual: Boolean = false,
)

/**
 * 按 otherId 决定处理方式，对照老项目 `automation.py:160-190`：
 * 手势签到图案每次都不同，不做固定密码、永远不自动提交，和签退、二维码、拍照
 * 一起归为手动；otherId==4 走位置链路，普通签退的响应要位置时再由
 * [CxCheckinApi.checkout] 内部回退。
 */
fun plannedSignMode(otherId: Int): CxSignMode = when {
    otherId == 2 || otherId == 3 || otherId == 5 -> CxSignMode.MANUAL
    otherId == 4 -> CxSignMode.LOCATION
    else -> CxSignMode.NORMAL
}

/** 签到方式给用户看的标签：普通 / 位置 / 手势 / 二维码 / 拍照。 */
fun cxSignModeLabel(otherId: Int): String = when (otherId) {
    2 -> "二维码"
    3 -> "手势"
    4 -> "位置"
    5 -> "拍照"
    else -> "普通"
}

/** 需要用户手动完成时的说明文案（不再提任何「密码」）。 */
fun manualSignReason(otherId: Int, kind: CxSignKind): String = when (otherId) {
    2 -> "二维码${kind.label}需要在手机 App 上扫码完成"
    3 -> if (kind == CxSignKind.CHECKIN) {
        "老师给的手势图案需要你当场划出来"
    } else {
        "手势签退需要在手机 App 上手动完成"
    }
    5 -> "拍照${kind.label}需要在手机 App 上手动完成"
    else -> "${kind.label}方式不支持，请在手机 App 上手动完成"
}

/**
 * 位置签到要提交的地址：活动自带地址 > 设置里的「签到地点」 > 兜底
 * 「教师指定地点」。少了这一项超星会以「未提交位置信息」拒绝。
 */
fun signAddress(activeAddress: String, settingsAddress: String): String =
    activeAddress.trim().ifBlank { settingsAddress.trim() }.ifBlank { "教师指定地点" }

/**
 * 解析 activelist 响应，字段兜底照老项目 `get_active_list`：
 * `result` 为 false → 空；`data` 可能是数组，也可能是含
 * `activeList` / `records` / `list` 的对象；id 兜底 `activePrimaryId`，
 * 标题兜底 `nameOne` / `activeName` / `name` / 「签到」，类型兜底 `type`。
 */
internal fun parseActiveList(body: String): List<CxActive> {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
    // 服务端实测返回数字 1，不能只认 optBoolean（见 cxFlag）。
    if (!json.cxFlag("result")) return emptyList()

    val items: JSONArray = when (val data = json.opt("data")) {
        is JSONArray -> data
        is JSONObject -> data.optJSONArray("activeList")
            ?: data.optJSONArray("records")
            ?: data.optJSONArray("list")
        else -> null
    } ?: return emptyList()

    return (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        CxActive(
            id = firstNonBlank(item, "id", "activePrimaryId"),
            name = firstNonBlank(item, "nameOne", "activeName", "name").ifBlank { "签到" },
            activeType = item.intOf("activeType").takeIf { it != 0 } ?: item.intOf("type"),
            status = item.intOf("status"),
            otherId = item.optString("otherId"),
            startTime = item.optString("startTime"),
            endTime = item.optString("endTime"),
            address = item.optString("address"),
        )
    }
}

/**
 * activelist 响应的顶层结构摘要，只用于诊断日志，不参与筛选。
 *
 * 把 `result` 原样（bool / 数字）、`data` 的类型、activeList/records/list 的长度
 * 和 msg/error/message 拼成一行，用来判断「接口成功但列表解析为空」到底是结构变了
 * 还是真的没有活动。
 */
internal fun activeListDiagnostics(body: String): String {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return "json=invalid"
    val result = json.opt("result")
    val resultText = when (result) {
        is Boolean -> "bool:$result"
        is Number -> "num:$result"
        null -> "null"
        else -> result.toString().take(50)
    }
    val data = json.opt("data")
    val dataType = when (data) {
        is JSONObject -> "object"
        is JSONArray -> "array"
        null -> "null"
        else -> data::class.java.simpleName
    }
    val counts = buildString {
        if (data is JSONObject) {
            for (key in listOf("activeList", "records", "list")) {
                data.optJSONArray(key)?.let { append(" $key=${it.length()}") }
            }
        } else if (data is JSONArray) {
            append(" data=${data.length()}")
        }
    }
    val messages = listOf("msg", "error", "message").mapNotNull { key ->
        json.optString(key).takeIf { it.isNotBlank() }?.let { "$key=${it.take(200)}" }
    }.joinToString(" ")
    return "result=$resultText data=$dataType$counts${if (messages.isNotEmpty()) " $messages" else ""}"
}

/** 响应体像登录页/SSO 跳转页时返回 true；用于把「空列表」和「被踢回登录」区分开。 */
internal fun looksLikeLoginBody(body: String): Boolean =
    "<title>" in body || "登录" in body || "passport" in body.lowercase()

/** 老项目 `act.get('id') or act.get('activePrimaryId') or ''` 这类字段兜底。 */
private fun firstNonBlank(obj: JSONObject, vararg keys: String): String =
    keys.firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }.orEmpty()

private fun JSONObject.intOf(key: String): Int =
    when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

/**
 * 签到协议层（直连），对照老项目 `chaoxing-fenbushi/server/app/cx_api.py`：
 * `get_active_list`、`pre_sign`、`do_checkin`、`do_checkout`、`do_gesture_checkin`。
 *
 * [checkin] / [checkout] 是自动路径的入口：先做一次 `preSign`（超星要求所有签到类
 * 接口前先预签到），再按 [plannedSignMode] 分派。[gestureCheckin] 只由用户在
 * [CxGestureSignScreen] 当场划出图案后调用，图案不落盘、不复用。位置链路的三个
 * 探针都是真实的提交，成功即短路；401 交给 [CxRepository.withSession]，这里不吞。
 */
class CxCheckinApi(
    private val api: CxApi,
    private val uid: String = "",
) {

    suspend fun activeList(
        courseId: String,
        clazzId: String,
    ): List<CxActive> = withContext(Dispatchers.IO) {
        // 参数名照老项目 get_active_list：未开始的活动也要返回，签退发现靠它；
        // `_` 是防缓存的时间戳。老项目不传 cpi，这里也不传。
        val url = "$ACTIVELIST?fid=0&courseId=$courseId&classId=$clazzId" +
            "&showNotStartedActive=1&_=${System.currentTimeMillis()}"
        val (status, body) = api.getFollowing(url, REF_MOBILE)
        val bytes = body.toByteArray(Charsets.UTF_8).size
        Log.i(TAG, "activelist url=$url status=$status bytes=$bytes")
        if (body.isBlank()) {
            Log.w(TAG, "activelist body empty course=$courseId/$clazzId status=$status")
        }
        api.ensurePageOk(status, body, "获取活动列表")
        // 风控响应是 status=200 + result=0 + errorMsg，不能当成「成功但 0 条」。
        parseSignRestriction(body)?.let { restriction ->
            Log.w(
                TAG,
                "activelist restricted until=${restriction.untilMs} msg=${restriction.message}",
            )
            throw CxSignRestrictedException(restriction)
        }
        val actives = parseActiveList(body)
        Log.i(
            TAG,
            "activelist parsed course=$courseId/$clazzId count=${actives.size} " +
                activeListDiagnostics(body),
        )
        if (actives.isEmpty()) logEmptyActiveList(body)
        actives
    }

    /**
     * 解析为 0 条时的留痕：先看结构（上面那行），再看响应体；像登录页就明确
     * `Log.w`，不要当成「确实没有签到」悄悄吞掉。换行压成空格，截 400 字符。
     */
    private fun logEmptyActiveList(body: String) {
        val flat = body.replace(Regex("\\s+"), " ").take(400)
        if (looksLikeLoginBody(body)) {
            val title = Regex("""<title>([^<]*)</title>""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.getOrNull(1).orEmpty()
            // 登录页标识始终打；400 字响应体只在 debug 构建里带出去。
            Log.w(
                TAG,
                "activelist 返回登录页 title=$title " +
                    "hasPassport=${body.contains("passport", ignoreCase = true)}" +
                    if (BuildConfig.DEBUG) " body=$flat" else "",
            )
        } else if (BuildConfig.DEBUG) {
            Log.d(TAG, "activelist parsed 0 body=$flat")
        } else {
            Log.v(TAG, "activelist parsed 0（release 不打响应体）")
        }
    }

    /**
     * 签到入口（只处理 [CxSignMode.NORMAL] / [CxSignMode.LOCATION]）。
     *
     * 手势签到不从这里走：图案因活动而异，由用户在 [CxGestureSignScreen] 当场
     * 划出后再调 [gestureCheckin]。
     *
     * @param settingsLat/[settingsLng] 设置里的种子坐标（位置签到用）。
     */
    suspend fun checkin(
        active: CxActive,
        courseId: String,
        clazzId: String,
        settingsLat: Double = CX_SIGN_DEFAULT_SEED_LAT,
        settingsLng: Double = CX_SIGN_DEFAULT_SEED_LNG,
        settingsAddress: String = "",
    ): CxSignResult = withContext(Dispatchers.IO) {
        val kind = CxSignKind.CHECKIN
        val mode = plannedSignMode(active.otherIdValue)
        if (mode == CxSignMode.MANUAL) {
            return@withContext CxSignResult(
                false,
                manualSignReason(active.otherIdValue, kind),
                needManual = true,
            )
        }

        preSign(active.id, courseId, clazzId)
        val address = signAddress(active.address, settingsAddress)
        when (mode) {
            CxSignMode.LOCATION -> {
                val located = locate(active.id, settingsLat, settingsLng, address, kind)
                CxSignResult(located.success, located.message)
            }
            else -> checkinNormal(active.id, courseId, clazzId)
        }
    }

    /**
     * 签退入口。手势签退不自动（老项目也不自动）；普通签退响应要求位置时
     * 内部改走三点定位。
     */
    suspend fun checkout(
        active: CxActive,
        courseId: String,
        clazzId: String,
        settingsLat: Double = CX_SIGN_DEFAULT_SEED_LAT,
        settingsLng: Double = CX_SIGN_DEFAULT_SEED_LNG,
        settingsAddress: String = "",
    ): CxSignResult = withContext(Dispatchers.IO) {
        val kind = CxSignKind.CHECKOUT
        val otherId = active.otherIdValue
        val mode = plannedSignMode(otherId)
        if (mode == CxSignMode.MANUAL) {
            return@withContext CxSignResult(false, manualSignReason(otherId, kind), needManual = true)
        }

        preSign(active.id, courseId, clazzId)
        val address = signAddress(active.address, settingsAddress)
        if (mode == CxSignMode.LOCATION) {
            val located = locate(active.id, settingsLat, settingsLng, address, kind)
            return@withContext CxSignResult(located.success, located.message)
        }
        checkoutNormal(active.id, courseId, clazzId, settingsLat, settingsLng, address)
    }

    /** 签到/签退前必须先调一次，老项目对返回值只打日志、不中断流程。 */
    private suspend fun preSign(activeId: String, courseId: String, clazzId: String): Boolean {
        val url = "$BASE/newsign/preSign?courseId=$courseId&classId=$clazzId" +
            "&activePrimaryId=$activeId&general=1&sys=1&ls=1&appType=15" +
            "&tid=&uid=${uidOf()}&ut=s"
        val pair = try {
            api.get(url, REF_MOBILE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CxException) {
            // 登录失效与风控不能吞：withSession 的重登、风控提示都靠这两个码。
            if (e.code == CX_CODE_AUTH || e.code == CX_CODE_RISK) throw e
            Log.w(TAG, "preSign failed active=$activeId code=${e.code}")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "preSign failed active=$activeId ${e.javaClass.simpleName}")
            return false
        }
        val (status, body) = pair
        val lower = body.lowercase()
        val ok = status == 200 &&
            ("true" in lower || "success" in lower || body.trimStart().startsWith("{"))
        Log.i(TAG, "preSign active=$activeId status=$status ok=$ok")
        return ok
    }

    /** 普通签到：latitude/longitude 固定 -1。 */
    private suspend fun checkinNormal(
        activeId: String,
        courseId: String,
        clazzId: String,
    ): CxSignResult {
        val url = "$SIGN_AJAX?activeId=$activeId&uid=${uidOf()}" +
            "&clientip=&latitude=-1&longitude=-1&appType=15&fid=&objectId="
        val (status, body) = api.get(url, activityRef(activeId, courseId, clazzId))
        Log.i(TAG, "checkin normal active=$activeId status=$status len=${body.length}")
        return signVerdict(body, CxSignKind.CHECKIN)
    }

    /**
     * 手势签到：用户在九宫格上当场划出图案后，把点序列（如 "12369"）作为
     * signCode 提交。全字段与签退同一套，只多 signCode；图案不落盘、不复用。
     */
    suspend fun gestureCheckin(
        activeId: String,
        courseId: String,
        clazzId: String,
        signCode: String,
    ): CxSignResult = withContext(Dispatchers.IO) {
        require(signCode.isNotBlank()) { "signCode 不能为空" }
        preSign(activeId, courseId, clazzId)
        val form = signForm(activeId, courseId, clazzId, extraKey = "signCode", extraValue = signCode)
        val (status, body) = api.postForm(
            SIGN_POST_URL,
            form,
            activityRef(activeId, courseId, clazzId),
        )
        Log.i(TAG, "checkin gesture active=$activeId status=$status len=${body.length}")
        val verdict = signVerdict(body, CxSignKind.CHECKIN)
        // 非 200 时响应体往往是错误页，signVerdict 只能给「结果未知」；把状态码
        // 直接带进文案，用户和日志都能看出是服务端 500 而不是图案划错。
        if (!verdict.success && status != 200) {
            CxSignResult(false, "提交失败（HTTP $status）")
        } else {
            verdict
        }
    }

    /**
     * 普通签退：POST 固定字段（general/sys/ls = 6）。
     *
     * 签退活动经常没标 otherId=4，但服务端仍要求位置；响应里出现「未提交位置」
     * 「不在范围」等提示时，自动改走三点定位再试一次。preSign 已在入口做过。
     */
    private suspend fun checkoutNormal(
        activeId: String,
        courseId: String,
        clazzId: String,
        seedLat: Double,
        seedLng: Double,
        address: String,
    ): CxSignResult {
        val form = signForm(activeId, courseId, clazzId)
        val (status, body) = api.postForm(
            SIGN_POST_URL,
            form,
            activityRef(activeId, courseId, clazzId),
        )
        Log.i(TAG, "checkout normal active=$activeId status=$status len=${body.length}")
        if (CxSignFeedback.succeeded(body)) {
            return CxSignResult(true, if ("已退" in body) "已签退过" else "签退成功")
        }
        if (CxSignFeedback.needsLocation(body)) {
            Log.i(TAG, "checkout needs location, falling back active=$activeId")
            val located = locate(activeId, seedLat, seedLng, address, CxSignKind.CHECKOUT)
            return CxSignResult(located.success, located.message)
        }
        return CxSignResult(false, "签退失败：${body.take(100)}")
    }

    /** 位置签到/签退：三个探针 + 三边测量 + 抖动提交（+越界回退）。 */
    private suspend fun locate(
        activeId: String,
        seedLat: Double,
        seedLng: Double,
        address: String,
        kind: CxSignKind,
    ): CxLocateResult {
        // 老项目探针与最终提交的 referer 都是根域，不是活动页。
        val chain = CxLocateChain(
            submit = { lat, lng, addr -> submitLocation(activeId, lat, lng, addr, REF_MOBILE) },
        )
        val result = chain.run(seedLat, seedLng, address, kind)
        Log.i(
            TAG,
            "locate ${kind.label} active=$activeId ok=${result.success} " +
                "probes=${result.probes.joinToString { "${it.distanceM}" }} msg=${result.message}",
        )
        return result
    }

    /** 一次位置提交（探针与最终提交共用）。 */
    private suspend fun submitLocation(
        activeId: String,
        lat: Double,
        lng: Double,
        address: String,
        referer: String,
    ): String {
        val (loc, locResult) = CxLocPayload.fields(lat, lng, address)
        val latText = formatCoord(lat)
        val lngText = formatCoord(lng)
        val url = "$SIGN_AJAX?activeId=$activeId&uid=${uidOf()}" +
            "&clientip=&latitude=$latText&longitude=$lngText" +
            "&address=${enc(address)}&appType=15&fid=&objectId=&ifTiJiao=1" +
            "&location=${enc(loc)}&locationResult=${enc(locResult)}"
        val (status, body) = api.get(url, referer)
        if (status != 200) Log.w(TAG, "locate submit status=$status active=$activeId")
        return body
    }

    /** 手势签到与签退共用的 POST 字段集（对照老项目 do_checkout / do_gesture_checkin）。 */
    private fun signForm(
        activeId: String,
        courseId: String,
        clazzId: String,
        extraKey: String? = null,
        extraValue: String = "",
    ): LinkedHashMap<String, String> {
        val form = linkedMapOf(
            "courseId" to courseId,
            "classId" to clazzId,
            "activePrimaryId" to activeId,
            "uid" to uidOf(),
            "general" to "6",
            "sys" to "6",
            "ls" to "6",
            "appType" to "15",
            "isTeacherViewOpen" to "0",
            "clientip" to "",
            "vpProbability" to "-1",
            "vpStrategy" to "",
            "faceCode" to "",
            "faceEncAid" to "",
            "ifTiJiao" to "1",
        )
        if (extraKey != null) form[extraKey] = extraValue
        return form
    }

    private fun signVerdict(body: String, kind: CxSignKind): CxSignResult = when {
        "已签" in body || "已退" in body -> CxSignResult(true, "已${kind.label}过")
        CxSignFeedback.succeeded(body) -> CxSignResult(true, "${kind.label}成功")
        "fail" in body.lowercase() || "失败" in body ->
            CxSignResult(false, "${kind.label}失败：${body.take(80)}")
        else -> CxSignResult(false, "${kind.label}结果未知：${body.take(80)}")
    }

    private fun uidOf(): String = uid.ifBlank { api.cookie("UID") }

    private fun activityRef(activeId: String, courseId: String, clazzId: String): String =
        "$ACTIVITY_REF?id=$activeId&courseId=$courseId&classId=$clazzId"

    private fun formatCoord(value: Double): String = String.format(Locale.US, "%.6f", value)

    private companion object {
        const val TAG = "CxCheckinApi"
        const val REF_MOBILE = "https://mobilelearn.chaoxing.com/"
        const val BASE = "https://mobilelearn.chaoxing.com"
        const val ACTIVELIST = "$BASE/v2/apis/active/student/activelist"
        const val SIGN_AJAX = "$BASE/pptSign/stuSignajax"

        /** 手势签到与签退的 POST 地址：query 固定为空字段，真正的值都在 body。 */
        const val SIGN_POST_URL =
            "$SIGN_AJAX?&clientip=&appType=15&ifTiJiao=1&vpProbability=-1&vpStrategy="
        const val ACTIVITY_REF = "$BASE/page/active/show"

        fun enc(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}