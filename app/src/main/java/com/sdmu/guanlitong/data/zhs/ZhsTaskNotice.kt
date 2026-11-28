package com.sdmu.guanlitong.data.zhs

/**
 * 知到刷课终态通知的内容与投递口径（纯函数，便于 JVM 单测）。
 *
 * 运行中的进度通知走前台服务的低打扰频道；达标 / 结束 / 滑块 / 异常走独立的
 * 社交频道，拿到最强提醒，且不能被进度更新反复响铃。
 */
data class ZhsTaskNotice(
    val channelId: String,
    val category: String,
    val priority: Int,
    val title: String,
    val text: String,
    val social: Boolean,
)

object ZhsTaskNoticeBuilder {

    /** 前台服务常驻进度频道（IMPORTANCE_LOW）。 */
    const val PROGRESS_CHANNEL_ID = "zhs_task"

    /** 终态提醒专用社交频道（IMPORTANCE_HIGH）。 */
    const val SOCIAL_CHANNEL_ID = "zhs_brush_social"

    /** 与 NotificationCompat.CATEGORY_SOCIAL 一致。 */
    const val CATEGORY_SOCIAL = "android.intent.category.SOCIAL"

    /** 与 NotificationCompat.PRIORITY_MAX 一致。 */
    const val PRIORITY_MAX = 2

    /** 任务结束（含滑块、异常、达标/未达标）时的通知。 */
    fun complete(state: ZhsTaskState): ZhsTaskNotice {
        val course = state.courseName.ifBlank { "知到课程" }
        val gained = formatClock(state.gained)
        val title: String
        val text: String
        when {
            state.captcha -> {
                title = "知到 · $course · 需要滑块验证"
                text = "本次新增 $gained。请前往智慧树 App 完成验证后再重新开始。"
            }

            state.error != null -> {
                title = "知到 · $course · 刷课异常终止"
                text = "本次新增 $gained，已终止：${state.error}"
            }

            state.target > 0 -> {
                val met = state.gained >= state.target
                title = "知到 · $course · 刷课${if (met) "达标" else "未达标结束"}"
                text = "本次新增 $gained / 目标 ${formatClock(state.target)}，" +
                    if (met) "已达标" else "未达标"
            }

            else -> {
                title = "知到 · $course · 刷课完成"
                text = "本次新增 $gained"
            }
        }
        return ZhsTaskNotice(
            channelId = SOCIAL_CHANNEL_ID,
            category = CATEGORY_SOCIAL,
            priority = PRIORITY_MAX,
            title = title,
            text = text,
            social = true,
        )
    }
}