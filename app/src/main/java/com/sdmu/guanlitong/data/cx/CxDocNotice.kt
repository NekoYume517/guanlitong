package com.sdmu.guanlitong.data.cx

import java.util.Locale

/** 资料刷时长的终态原因，决定通知标题与文案。 */
enum class CxDocFinishReason { STOPPED, FAILED, DONE }

/**
 * 资料刷时长终态通知的内容与投递口径（纯函数，便于 JVM 单测）。
 *
 * 跑动中的进度通知走前台服务的低打扰频道；结束（完成 / 停止 / 失败）走独立社交
 * 频道，拿到最强提醒，且只在终态发一次，不被 5 秒心跳反复响铃。
 */
data class CxDocNotice(
    val channelId: String,
    val category: String,
    val priority: Int,
    val title: String,
    val text: String,
    val social: Boolean,
)

object CxDocNoticeBuilder {

    /** 前台服务常驻进度频道（IMPORTANCE_LOW，无声无横幅）。 */
    const val PROGRESS_CHANNEL_ID = "cx_doc"

    /** 终态提醒专用社交频道（IMPORTANCE_HIGH）。 */
    const val SOCIAL_CHANNEL_ID = "cx_brush_social"

    /** 与 [androidx.core.app.NotificationCompat.CATEGORY_SOCIAL] 一致。 */
    const val CATEGORY_SOCIAL = "android.intent.category.SOCIAL"

    /** 与 [androidx.core.app.NotificationCompat.PRIORITY_MAX] 一致。 */
    const val PRIORITY_MAX = 2

    fun complete(state: CxDocTaskState, reason: CxDocFinishReason): CxDocNotice {
        val course = state.courseName.ifBlank { "学习通课程" }
        val file = state.fileName.ifBlank { "资料" }
        val minutes = formatMinutes(state.reportedSeconds)
        val title: String
        val text: String
        when (reason) {
            CxDocFinishReason.FAILED -> {
                title = "学习通 · $course · 资料刷时长异常"
                text = "《$file》已上报 $minutes 分钟，已停止：" +
                    (state.fatalError ?: "未知错误")
            }

            CxDocFinishReason.STOPPED -> {
                title = "学习通 · $course · 资料刷时长已停止"
                text = "《$file》已上报 $minutes 分钟"
            }

            CxDocFinishReason.DONE -> {
                title = "学习通 · $course · 资料刷时长结束"
                text = "《$file》已上报 $minutes 分钟 · ${state.pageNum} 页 · " +
                    state.speedLabel()
            }
        }
        return CxDocNotice(
            channelId = SOCIAL_CHANNEL_ID,
            category = CATEGORY_SOCIAL,
            priority = PRIORITY_MAX,
            title = title,
            text = text,
            social = true,
        )
    }

    private fun formatMinutes(seconds: Long): String =
        String.format(Locale.CHINA, "%.1f", seconds / 60.0)
}