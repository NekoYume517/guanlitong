package com.sdmu.guanlitong.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sdmu.guanlitong.MainActivity
import com.sdmu.guanlitong.R
import java.util.Collections

/**
 * 签到里的「重要提醒」：成功结果、需要用户当场处理的活动，以及自动关闭时
 * 「发现签到 · 点击去处理」的实时提醒。
 *
 * 与前台常驻通知、普通失败通知分渠道：这里用 [CHANNEL_ID]「重要提醒」
 * （IMPORTANCE_HIGH，默认横幅 + 声音），用户需要立刻看到并动手。渠道一旦创建
 * 重要性就固定，因此创建时直接给 HIGH，之后不要再尝试修改。
 *
 * 手势签到没有固定密码：通知带上 activeId/courseId/classId，点开后由 App 跳到
 * 九宫格让用户当场划；二维码/拍照等其它手动类型与实时提醒只带 cx_page=inbox，
 * 打开收件箱里的置顶签到区块。
 */
object CxSignManualNotify {

    /** 重要提醒渠道：签到成功、需要手动完成。 */
    const val CHANNEL_ID = "cx_important"

    /** 通知点击后交给 [MainActivity] 的路由参数。 */
    const val EXTRA_ACTIVE_ID = "cx_gesture_active_id"
    const val EXTRA_COURSE_ID = "cx_gesture_course_id"
    const val EXTRA_CLASS_ID = "cx_gesture_class_id"

    /** 通知点击后的落地页；这两个值与 CxDeepLink 的识别口径一致。 */
    const val EXTRA_PAGE = "cx_page"
    const val PAGE_INBOX = "inbox"

    /** 「发现签到 · 点击去处理」打开独立的签到页，而不是收件箱。 */
    const val PAGE_SIGN = "sign"

    private const val CHANNEL_NAME = "重要提醒"
    private const val CHANNEL_DESC = "签到成功、需要你当场完成的手势签到"

    /** 成功通知：一条结果一个 id，避免互相覆盖。 */
    private const val SUCCESS_ID_BASE = 2200

    /** 手动处理通知：同一活动一条，进度里会覆盖同一条而不是刷屏。 */
    private const val MANUAL_ID_BASE = 2300

    /**
     * 实时提醒通知：「发现签到 · 点击去处理」，同一活动一条。
     *
     * 基址与手动的 2300 段拉开，避免两者算出同一个 requestCode 后 PendingIntent
     * 合并、把「去手划」的 extra 覆盖成「去收件箱」。
     */
    private const val PROMPT_ID_BASE = 9000

    private const val PENDING_FLAGS =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /**
     * 同一活动只提示一次。进程重启后集合清空，但签到台账里这条活动已是 MANUAL，
     * 轮询不会再发，双层兜底。
     */
    private val notifiedManual: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    /** 实时提醒的进程内去重；台账里的 PROMPTED 是跨进程的那层。 */
    private val notifiedPrompt: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    /** 自动签到/签退成功：「签到成功 - 课程名」；点开进收件箱。 */
    fun notifySuccess(context: Context, title: String, body: String) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            SUCCESS_ID_BASE + (title.hashCode() and 0x7FF),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(inboxIntent(context, requestCode = 20))
                .build(),
        )
    }

    /**
     * 需要用户当场完成（手势/二维码/拍照）：点通知回到 App 处理。
     *
     * @param reason 给用户看的原因，同时决定标题里的手势/扫码/拍照措辞。
     * @param gesture true 时带活动/课程/班级 extra，直达九宫格；否则打开收件箱。
     */
    fun notifyManual(
        context: Context,
        activeId: String,
        courseId: String,
        classId: String,
        courseName: String,
        reason: String,
        gesture: Boolean,
    ) {
        if (activeId.isBlank()) return
        if (!notifiedManual.add(activeId)) return

        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val intent = if (gesture) {
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ACTIVE_ID, activeId)
                .putExtra(EXTRA_COURSE_ID, courseId)
                .putExtra(EXTRA_CLASS_ID, classId)
        } else {
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_PAGE, PAGE_INBOX)
        }
        val pending = PendingIntent.getActivity(
            context,
            MANUAL_ID_BASE + (activeId.hashCode() and 0xFF),
            intent,
            PENDING_FLAGS,
        )
        manager.notify(
            MANUAL_ID_BASE + (activeId.hashCode() and 0xFF),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(manualTitle(courseName, reason))
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build(),
        )
    }

    /**
     * 监控开、自动关时发现新签到：「发现签到 · 点击去处理」，点开进签到页，
     * 那里有进行中的签到和最近记录，比翻收件箱更直接。
     *
     * 同一活动只在进程内去重一次；跨进程去重由签到台账的 PROMPTED 负责。
     */
    fun notifyPrompt(
        context: Context,
        activeId: String,
        title: String,
        body: String,
    ) {
        if (activeId.isBlank()) return
        if (!notifiedPrompt.add(activeId)) return

        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = PROMPT_ID_BASE + (activeId.hashCode() and 0xFF)
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pageIntent(context, PAGE_SIGN, requestCode = id))
                .build(),
        )
    }

    /** 「需要手势签到 - 课程名」这类人话标题，按原因选措辞。 */
    private fun manualTitle(courseName: String, reason: String): String {
        val action = when {
            "手势" in reason -> "手势签到"
            "二维码" in reason || "扫码" in reason -> "扫码签到"
            "拍照" in reason -> "拍照签到"
            "签退" in reason -> "手动签退"
            else -> "手动签到"
        }
        return "需要$action - $courseName"
    }

    private fun inboxIntent(context: Context, requestCode: Int): PendingIntent =
        pageIntent(context, PAGE_INBOX, requestCode)

    private fun pageIntent(context: Context, page: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_PAGE, page),
            PENDING_FLAGS,
        )

    /** 渠道首次创建即定重要性 HIGH：默认横幅 + 声音，之后不再改。 */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = CHANNEL_DESC
            },
        )
    }
}