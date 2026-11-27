package com.sdmu.guanlitong.data.cx

import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 种子坐标默认点，与老项目一致；用户可在设置里改成学校实际位置。 */
const val CX_SIGN_DEFAULT_SEED_LAT = 36.0
const val CX_SIGN_DEFAULT_SEED_LNG = 117.0

/** 位置签到/位置签退。两者走同一条链，只是文案不同。 */
enum class CxSignKind(val label: String) {
    CHECKIN("签到"),
    CHECKOUT("签退"),
}

/**
 * 教师三点定位的纯算法部分（无网络、无 Android 依赖），对照老项目
 * `chaoxing-fenbushi/server/app/cx_api.py:204-416`：
 *
 * 种子坐标 → 沿 0°/120°/240° 各偏移 3000m 得到三个探针 → 每个探针真实提交一次
 * 签到、从响应里读「距…N米」→ 以 p1 为原点投影到局部米制平面，两两相减消去
 * 二次项解线性方程组 → 得到教师中心点。
 *
 * 所有公式与老项目一致，改动前先看它的注释。
 */
object CxSignGeo {

    /** 地球半径（米），与老项目一致。 */
    const val EARTH_RADIUS_M = 6_371_000.0

    /** 探针相对种子点的偏移距离；越大三点越分散、解算越稳。 */
    const val PROBE_OFFSET_M = 3_000.0

    /** 三个探针的方位角：正北、东南、西南。 */
    val PROBE_BEARINGS = listOf(0.0, 120.0, 240.0)

    /** 最终提交时在教师中心点附近随机抖动的距离范围（米）。 */
    const val SIGN_OFFSET_MIN_M = 5.0
    const val SIGN_OFFSET_MAX_M = 50.0

    /** 行列式小于这个值就当三点共线，退化为重心。 */
    private const val CENTROID_DET_EPSILON = 1e-6

    /** 球面距离（米）。 */
    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLam = Math.toRadians(lng2 - lng1)
        val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLam / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * 从 (lat, lng) 沿方位角 [bearingDeg] 走 [distanceM] 米后的经纬度（大圆偏移）。
     * 返回 (lat, lng)。
     */
    fun offsetPoint(
        lat: Double,
        lng: Double,
        distanceM: Double,
        bearingDeg: Double,
    ): Pair<Double, Double> {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(lat)
        val lam1 = Math.toRadians(lng)
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lam2 = lam1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2),
        )
        return Math.toDegrees(phi2) to Math.toDegrees(lam2)
    }

    /**
     * 三边测量：给定三个参考点与到目标的距离，返回目标 (lat, lng)。
     *
     * 把三点投影到以 p1 为原点的局部米制平面（纬度 1° = R·π/180，
     * 经度 1° = R·cos(lat0)·π/180），三个圆方程两两相减消去二次项，解二元一次
     * 方程组。三点共线（行列式 ~0）时退化为三点重心。
     */
    fun trilaterate(
        p1: Pair<Double, Double>, d1: Double,
        p2: Pair<Double, Double>, d2: Double,
        p3: Pair<Double, Double>, d3: Double,
    ): Pair<Double, Double> {
        val mPerLat = EARTH_RADIUS_M * PI / 180.0
        val mPerLng = EARTH_RADIUS_M * cos(Math.toRadians(p1.first)) * PI / 180.0

        val x1 = 0.0
        val y1 = 0.0
        val x2 = (p2.second - p1.second) * mPerLng
        val y2 = (p2.first - p1.first) * mPerLat
        val x3 = (p3.second - p1.second) * mPerLng
        val y3 = (p3.first - p1.first) * mPerLat

        val a = 2 * (x2 - x1)
        val b = 2 * (y2 - y1)
        val c = d1 * d1 - d2 * d2 + x2 * x2 - x1 * x1 + y2 * y2 - y1 * y1
        val d = 2 * (x3 - x1)
        val e = 2 * (y3 - y1)
        val f = d1 * d1 - d3 * d3 + x3 * x3 - x1 * x1 + y3 * y3 - y1 * y1

        val det = a * e - b * d
        if (abs(det) < CENTROID_DET_EPSILON) {
            return (p1.first + p2.first + p3.first) / 3 to
                (p1.second + p2.second + p3.second) / 3
        }

        val px = (c * e - f * b) / det
        val py = (a * f - d * c) / det
        return (p1.first + py / mPerLat) to (p1.second + px / mPerLng)
    }

    /** 在教师中心点附近随机抖动 [SIGN_OFFSET_MIN_M]–[SIGN_OFFSET_MAX_M] 米。 */
    fun jittered(
        teacherLat: Double,
        teacherLng: Double,
        random: Random = Random.Default,
    ): Pair<Double, Double> {
        val bearing = random.nextDouble(0.0, 360.0)
        val offset = random.nextDouble(SIGN_OFFSET_MIN_M, SIGN_OFFSET_MAX_M)
        return offsetPoint(teacherLat, teacherLng, offset, bearing)
    }
}

/** 超星签到/签退响应的判定，全部是老项目里实际见过的字符串。 */
object CxSignFeedback {

    /** 「距教师指定签到地点1234米」「距签退地点1.2515441E7米」这类。 */
    private val DISTANCE_RE = Regex("""距.*?([0-9.]+(?:[eE][0-9]+)?)\s*米""")

    /** 成功或「已经签过/退过」都算这条活动不用再做。 */
    fun succeeded(body: String): Boolean {
        val lower = body.lowercase()
        return "success" in lower ||
            "签到成功" in body ||
            "签退成功" in body ||
            "已签" in body ||
            "已退" in body
    }

    /** 解析「距…N米」，支持 `1.2515441E7` 这种科学计数法；解析不到返回 null。 */
    fun distanceM(body: String): Double? =
        DISTANCE_RE.find(body)?.groupValues?.get(1)?.toDoubleOrNull()

    /** 教师认为目标不在允许范围内。 */
    fun outOfRange(body: String): Boolean = "范围" in body || "不在" in body

    /**
     * 服务端要求提交位置信息（普通签退的兜底：otherId 没标 4 但实际要位置）。
     */
    fun needsLocation(body: String): Boolean =
        LOCATION_HINTS.any { it in body }

    private val LOCATION_HINTS = listOf(
        "未提交位置", "位置信息", "经纬度", "不在范围",
        "超出范围", "距教师", "距签退",
    )
}

/**
 * 超星位置签到/签退要的 `location` / `locationResult` 字段。
 *
 * 只传 latitude/longitude 会被判定「未提交位置信息」，服务端认的是这两个 JSON。
 * 老项目签名里留过 mockData 开关但从未用过，这里不实现。
 */
object CxLocPayload {
    fun fields(lat: Double, lng: Double, address: String): Pair<String, String> {
        val addr = address.replace("\"", "\\\"")
        val json = """{"result":1,"latitude":$lat,"longitude":$lng,"address":"$addr"}"""
        return json to json
    }
}

/** 一次探针的实测记录：点、解析出的距离、原始响应（日志用）。 */
data class CxProbe(
    val lat: Double,
    val lng: Double,
    val distanceM: Double?,
    val body: String,
)

/** 三点定位 + 最终提交的结果。 */
data class CxLocateResult(
    val success: Boolean,
    val message: String,
    val teacherLat: Double? = null,
    val teacherLng: Double? = null,
    val probes: List<CxProbe> = emptyList(),
)

/**
 * 位置签到/签退的执行链：三个探针 → 三边测量 → 抖动后提交 → 越界回退精确坐标。
 *
 * [submit] 每次都是**真实的一次签到提交**（超星没有只探测距离的接口），所以任何
 * 一次探测返回成功就必须立刻停，不能继续发；这里不做重试。
 *
 * 抽成独立类是为了用假 submit、假时钟做数值单测。
 */
class CxLocateChain(
    private val submit: suspend (lat: Double, lng: Double, address: String) -> String,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
    private val probeOffsetM: Double = CxSignGeo.PROBE_OFFSET_M,
    private val probeGapMs: Long = PROBE_GAP_MS,
) {

    suspend fun run(
        seedLat: Double,
        seedLng: Double,
        address: String,
        kind: CxSignKind,
    ): CxLocateResult {
        val label = kind.label
        val probes = CxSignGeo.PROBE_BEARINGS.map { bearing ->
            CxSignGeo.offsetPoint(seedLat, seedLng, probeOffsetM, bearing)
        }
        val logged = mutableListOf<CxProbe>()
        val distances = mutableListOf<Double>()

        for ((index, probe) in probes.withIndex()) {
            if (index > 0) pause(probeGapMs)
            val (lat, lng) = probe
            val body = submit(lat, lng, address)
            if (CxSignFeedback.succeeded(body)) {
                logged += CxProbe(lat, lng, 0.0, body)
                return CxLocateResult(
                    success = true,
                    message = "位置${label}成功（探测时直接完成）",
                    probes = logged,
                )
            }
            val distance = CxSignFeedback.distanceM(body)
            if (distance == null) {
                logged += CxProbe(lat, lng, null, body)
                return CxLocateResult(
                    success = false,
                    message = "位置${label}探测失败：无法解析距离响应",
                    probes = logged,
                )
            }
            logged += CxProbe(lat, lng, distance, body)
            distances += distance
        }

        val teacher = CxSignGeo.trilaterate(
            probes[0], distances[0],
            probes[1], distances[1],
            probes[2], distances[2],
        )
        val bearing = random.nextDouble(0.0, 360.0)
        val offset = random.nextDouble(CxSignGeo.SIGN_OFFSET_MIN_M, CxSignGeo.SIGN_OFFSET_MAX_M)
        val finalPoint = CxSignGeo.offsetPoint(teacher.first, teacher.second, offset, bearing)

        // 老项目每个探针（含第三个）之后都 sleep(0.5)，所以最终提交也要隔 500ms。
        pause(probeGapMs)
        val body = submit(finalPoint.first, finalPoint.second, address)
        if (CxSignFeedback.succeeded(body)) {
            return CxLocateResult(
                success = true,
                message = "位置${label}成功（偏移 ${offset.toInt()}m）",
                teacherLat = teacher.first,
                teacherLng = teacher.second,
                probes = logged,
            )
        }
        if (CxSignFeedback.outOfRange(body)) {
            // 定位有偏差，用教师精确坐标再提交一次；只回退这一次。
            val exact = submit(teacher.first, teacher.second, address)
            if (CxSignFeedback.succeeded(exact)) {
                return CxLocateResult(
                    success = true,
                    message = "位置${label}成功（精确坐标）",
                    teacherLat = teacher.first,
                    teacherLng = teacher.second,
                    probes = logged,
                )
            }
            return CxLocateResult(
                success = false,
                message = "位置${label}失败（超出范围）：${exact.take(80)}",
                teacherLat = teacher.first,
                teacherLng = teacher.second,
                probes = logged,
            )
        }
        return CxLocateResult(
            success = false,
            message = "位置${label}失败：${body.take(100)}",
            teacherLat = teacher.first,
            teacherLng = teacher.second,
            probes = logged,
        )
    }

    companion object {
        /** 探针之间的间隔：老项目 time.sleep(0.5)。 */
        const val PROBE_GAP_MS = 500L
    }
}