package com.sdmu.guanlitong.data.pu

import com.sdmu.guanlitong.data.completionRatio

/** 第二课堂活动摘要，时间统一为 epoch 毫秒。 */
data class PuActivityBrief(
    val id: Long,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val credit: Double,
)

/** 学校没有下发毕业要求学时时的中性目标，界面上要注明「按 100 学时估算」。 */
const val PU_TARGET_DEFAULT_CREDIT = 100.0

/** 活动分类占比的一个切片。上游没有分类字段时按活动状态分组。 */
data class PuCategorySlice(val label: String, val count: Int, val credit: Double)

/**
 * 第二课堂缓存的小组件摘要。
 *
 * 只读 [PuCache]，不发请求；[ongoing] 是此刻正在进行的活动，[upcoming] 是按开始时间
 * 最近的未来活动，[latest] 是没有进行中/未来活动时回退显示的最近结束活动。
 */
data class PuSummary(
    val totalCredit: Double,
    val targetCredit: Double,
    val ongoing: List<PuActivityBrief>,
    val upcoming: List<PuActivityBrief>,
    val latest: List<PuActivityBrief>,
    val categories: List<PuCategorySlice> = emptyList(),
) {
    val headline: PuActivityBrief?
        get() = ongoing.firstOrNull() ?: upcoming.firstOrNull() ?: latest.firstOrNull()

    /** 学时完成度，水杯液面高度。 */
    fun creditRatio(): Float = completionRatio(totalCredit, targetCredit)
}

fun summarizePu(
    activities: List<PuActivity>,
    totalCredit: Double,
    nowMs: Long,
    limit: Int = 3,
    targetCredit: Double = PU_TARGET_DEFAULT_CREDIT,
): PuSummary {
    val briefs = activities.map { it.toBrief() }
    val ongoing = briefs.filter { it.isOngoing(nowMs) }.sortedBy { it.endMs.takeIf { ms -> ms > 0 } ?: Long.MAX_VALUE }
    val upcoming = briefs.filter { it.startMs > nowMs }.sortedBy { it.startMs }
    val latest = briefs.filter { it.endMs in 1 until nowMs }
        .sortedByDescending { it.endMs }
        .take(limit.coerceAtLeast(1))
    return PuSummary(
        totalCredit = totalCredit,
        targetCredit = targetCredit,
        ongoing = ongoing,
        upcoming = upcoming,
        latest = latest,
        categories = puCategorySlices(activities, nowMs),
    )
}

/** 分类占比：优先读上游原始 JSON 的分类名，没有就按活动状态分组，保证条形有真实含义。 */
fun puCategorySlices(activities: List<PuActivity>, nowMs: Long): List<PuCategorySlice> =
    activities.groupBy { activity ->
        listOf("typeName", "type_name", "categoryName", "classifyName")
            .firstNotNullOfOrNull { key -> activity.raw.optString(key).trim().takeIf { it.isNotEmpty() } }
            ?: activity.stateLabel(nowMs)
    }.map { (label, items) ->
        PuCategorySlice(label = label, count = items.size, credit = items.sumOf { it.credit })
    }.sortedByDescending { it.count }

private fun PuActivity.stateLabel(nowMs: Long): String {
    val start = PuActions.tsMs(startTime)
    val end = PuActions.tsMs(endTime)
    return when {
        start > nowMs -> "即将开始"
        end > 0 && end > nowMs -> "进行中"
        else -> "已结束"
    }
}

fun PuActivity.toBrief(): PuActivityBrief = PuActivityBrief(
    id = id,
    name = name,
    startMs = PuActions.tsMs(startTime),
    endMs = PuActions.tsMs(endTime),
    credit = credit,
)

private fun PuActivityBrief.isOngoing(nowMs: Long): Boolean {
    if (startMs <= 0L || startMs > nowMs) return false
    return endMs <= 0L || endMs > nowMs
}