package com.sdmu.guanlitong.data.zhs

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 刷课上报的纯算法（无网络、无 Android），逐项对照参考实现
 * `D:\code\zhihuishu\zhs_server.py` 的 `normalize_speed_choice` /
 * `plan_brush_calls` 与 `brush_lesson` 里的 `Z()`、`ewssw`、`zwsds`。
 */

/** 倍速档位：默认（固定 6 个请求 / 4 秒）或自定义倍率。 */
data class ZhsSpeedChoice(
    val custom: Boolean = false,
    val rate: Double = 1.0,
) {
    companion object {
        val DEFAULT = ZhsSpeedChoice()

        /** 界面可选的倍率，对齐参考实现的 `BRUSH_RATES`。 */
        val RATES: List<Double> = listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0)

        /** 自定义倍率夹在 1..64（参考实现的归一化口径）。 */
        fun custom(rate: Double): ZhsSpeedChoice =
            ZhsSpeedChoice(custom = true, rate = rate.coerceIn(1.0, 64.0))
    }
}

/**
 * 刷课中可随时改的倍速档位。
 * 界面直接写 [choice]，正在运行的刷课在下一个上报前读取，立即按新倍率重排剩余请求。
 */
class ZhsSpeedController(initial: ZhsSpeedChoice = ZhsSpeedChoice.DEFAULT) {
    @Volatile
    var choice: ZhsSpeedChoice = initial
}

/** 一次刷课的上报节奏：[calls] 个请求、间隔 [intervalSec] 秒、第 i 个请求的会话秒数。 */
class ZhsBrushPlan internal constructor(
    val calls: Int,
    val intervalSec: Double,
    val label: String,
    private val sessions: (Int) -> Int,
) {
    fun sessionAt(index: Int): Int = sessions(index)

    /** 间隔毫秒数（用于 `delay`）。 */
    val intervalMs: Long get() = (intervalSec * 1000).toLong()
}

internal object ZhsBrushPlanner {

    /** 每次上报推进的视频进度（秒），与真实播放器节奏一致。 */
    const val HEARTBEAT_VIDEO_STEP = 30

    /** 两次上报的最小真实间隔（秒），防请求过密。 */
    const val MIN_INTERVAL_SEC = 0.35

    /** 两次上报的最大真实间隔（秒），1x 也不至于慢于真实播放。 */
    const val MAX_INTERVAL_SEC = 30.0

    /**
     * 对照 `plan_brush_calls`：
     * - 默认：`step = max(30, sec/6)`，最多 6 个请求、间隔 4 秒，会话秒数取每档中点；
     * - 自定义：请求数 = `ceil(sec/30)` 夹在 1..120，间隔 = `sec/倍率/请求数`
     *   夹在 0.35..30，会话秒数按请求数均分。
     */
    fun plan(videoSec: Int, choice: ZhsSpeedChoice, offset: Int = 0, cap: Int = 0): ZhsBrushPlan {
        val sec = max(1, videoSec)
        // cap>0 时把会话秒数压回视频总长（改速重排用到）；首次规划保持参考实现的原始上限行为。
        fun bound(value: Int): Int = if (cap > 0) min(max(1, cap), value) else value
        if (choice.custom) {
            val rate = choice.rate.coerceIn(1.0, 64.0)
            val calls = max(1, min(120, ceil(sec.toDouble() / HEARTBEAT_VIDEO_STEP).toInt()))
            val wall = sec / rate
            val interval = max(MIN_INTERVAL_SEC, min(MAX_INTERVAL_SEC, wall / calls))
            val effective = sec / (interval * calls)
            var label = "自定义 ${formatG(rate)}x · ${calls}个请求"
            if (rate > 0 && abs(effective - rate) / rate > 0.15) {
                label += " (实际≈${format1(effective)}x)"
            }
            return ZhsBrushPlan(calls, interval, label) { i ->
                bound(max(5, offset + pyRound((i + 1).toDouble() * sec / calls)))
            }
        }
        val step = max(30, sec / 6)
        val calls = max(1, min(6, sec / step))
        return ZhsBrushPlan(calls, 4.0, "默认 · ${calls}个请求") { i ->
            bound(max(5, offset + (i + 1) * step - step / 2))
        }
    }

    /**
     * 运行中改速后的剩余节奏，对照 `brush_lesson` 的 speed_cmd 分支：
     * 以「本节剩余秒数」重排请求，会话秒数从已推进的位置继续累加。
     */
    fun replan(
        videoSec: Int,
        callsDone: Int,
        current: ZhsBrushPlan,
        choice: ZhsSpeedChoice,
    ): ZhsBrushPlan {
        val base = if (callsDone > 0) current.sessionAt(callsDone - 1) else 0
        val remaining = if (callsDone > 0) max(1, videoSec - base) else videoSec
        return plan(remaining, choice, offset = base, cap = videoSec)
    }

    /** Python `round()` 是四舍六入五成双；照抄，避免会话秒数与参考实现差 1 秒。 */
    internal fun pyRound(value: Double): Int {
        val base = floor(value)
        val diff = value - base
        return when {
            diff > 0.5 -> (base + 1).toInt()
            diff < 0.5 -> base.toInt()
            base % 2.0 == 0.0 -> base.toInt()
            else -> (base + 1).toInt()
        }
    }

    /** Python `f"{rate:g}"`：去掉尾随 0。倍率范围 1..64，不存在科学计数法。 */
    internal fun formatG(value: Double): String =
        if (value == floor(value)) value.toInt().toString()
        else BigDecimal(value).stripTrailingZeros().toPlainString()

    private fun format1(value: Double): String =
        BigDecimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString()
}

internal object ZhsBrushCodec {

    private const val MASK = "zzpttjd"

    /** CASLOGC 解析失败时的兜底 uuid（参考实现 `EYwpN3D3` + `zhs`）。 */
    const val FALLBACK_UUID = "EYwpN3D3zhs"

    /**
     * 参考实现的 `Z(arr)`：元素用 `;` 拼接后逐字符与 `zzpttjd` 循环异或，
     * 结果按两位小写十六进制输出。
     */
    fun z(values: List<Any>): String {
        val joined = values.joinToString(";") { it.toString() }
        val out = StringBuilder(joined.length * 2)
        for ((index, ch) in joined.withIndex()) {
            val mixed = ch.code xor MASK[index % MASK.length].code
            val hex = mixed.toString(16)
            if (hex.length < 2) out.append('0')
            out.append(hex)
        }
        return out.toString()
    }

    /** 参考实现的 `ewssw`：观看中始终非空，格式 `0,1,a,b,c,c`。 */
    fun ewssw(sess: Int): String =
        "0,1,${max(1, sess / 3)},${max(2, sess / 2)},$sess,$sess"

    /** `studiedLessonDto.id` → base64；id 缺失时返回 null（由调用方用兜底值）。 */
    fun zwsds(studiedLessonId: Long): String? {
        if (studiedLessonId <= 0) return null
        return Base64.getEncoder().encodeToString(studiedLessonId.toString().toByteArray())
    }

    /** 从 URL 编码的 CASLOGC JSON 里取 uuid（不带后缀）；解析失败回落到参考实现的兜底值。 */
    fun casUuid(caslogc: String?): String {
        val raw = caslogc?.takeIf { it.isNotBlank() } ?: return FALLBACK_UUID.removeSuffix("zhs")
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val uuid = runCatching {
            org.json.JSONObject(decoded).optString("uuid")
        }.getOrDefault("")
        return uuid.takeIf { it.isNotBlank() } ?: FALLBACK_UUID.removeSuffix("zhs")
    }

    /** 刷课 `sdsew` 用的 uuid，参考实现固定拼 `"zhs"` 后缀。 */
    fun uuid(caslogc: String?): String = casUuid(caslogc) + "zhs"

    internal fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
        append('"')
    }

    /** `saveCacheIntervalTimeV2` / `saveDatabaseIntervalTimeV2` 的明文。 */
    fun brushPayload(courseId: Long, ewssw: String, sdsew: String, zwsds: String, date: Long): String =
        "{\"ewssw\":${jsonString(ewssw)},\"sdsew\":${jsonString(sdsew)}," +
            "\"zwsds\":${jsonString(zwsds)},\"courseId\":$courseId,\"dateFormate\":$date}"

    /** `loadVideoPointerInfo` 的明文。 */
    fun pointerPayload(lessonId: Long, recruitId: Long, courseId: Long, lessonVideoId: Long, date: Long): String =
        "{\"lessonId\":$lessonId,\"recruitId\":$recruitId,\"courseId\":$courseId," +
            "\"lessonVideoId\":$lessonVideoId,\"dateFormate\":$date}"

    /** `prelearningNote` 的明文。 */
    fun prelearningPayload(
        courseId: Long,
        chapterId: Long,
        lessonId: Long,
        lessonVideoId: Long,
        recruitId: Long,
        videoId: Long,
        date: Long,
    ): String =
        "{\"ccCourseId\":$courseId,\"chapterId\":$chapterId,\"isApply\":1," +
            "\"lessonId\":$lessonId,\"lessonVideoId\":$lessonVideoId,\"recruitId\":$recruitId," +
            "\"videoId\":$videoId,\"dateFormate\":$date}"

    /** `lessonPopupExam` 的明文。 */
    fun popupExamPayload(
        chapterId: Long,
        lessonId: Long,
        lessonVideoId: Long,
        questionIds: String,
        timeSec: Int,
        date: Long,
    ): String =
        "{\"date\":$date,\"id\":$chapterId,\"lessonId\":$lessonId,\"lessonVideoId\":$lessonVideoId," +
            "\"questionIds\":${jsonString(questionIds)},\"time\":$timeSec,\"dateFormate\":$date}"

    /** `batchSaveLessonPopupExamSaveAnswer` 的明文（单题）。 */
    fun answerPayload(
        courseId: Long,
        recruitId: Long,
        testQuestionId: Long,
        lessonId: Long,
        lessonVideoId: Long,
        answerId: String,
        date: Long,
    ): String =
        "{\"result\":[{\"courseId\":$courseId,\"recruitId\":$recruitId," +
            "\"testQuestionId\":$testQuestionId,\"lessonId\":$lessonId," +
            "\"lessonVideoId\":$lessonVideoId,\"answer\":${jsonString(answerId)},\"isCurrent\":0}]," +
            "\"dateFormate\":$date}"

    /** `learning/videolist` 的明文。 */
    fun videolistPayload(secret: String, date: Long): String =
        "{\"recruitAndCourseId\":${jsonString(secret)},\"dateFormate\":$date}"
}

/** 服务端要求滑块验证：必须立即停止刷课，等用户去智慧树 App 完成验证。 */
class ZhsCaptchaException(
    message: String = "智慧树要求滑块验证，请前往智慧树 App 完成验证后重试。",
) : IOException(message)