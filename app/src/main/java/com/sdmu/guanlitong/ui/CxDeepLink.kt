package com.sdmu.guanlitong.ui

import android.content.Intent
import com.sdmu.guanlitong.service.CxSignManualNotify
import kotlinx.coroutines.flow.MutableStateFlow

/** 通知 / 外部启动带进来的学习通页面。 */
sealed interface CxRoute {
    /** 收件箱：活动通知点击（`cx_page=inbox`）。 */
    data object Inbox : CxRoute

    /** 签到页：「发现签到 · 点击去处理」（`cx_page=sign`）。 */
    data object Sign : CxRoute

    /**
     * 需要当场手划的签到：通知带上活动、课程、班级 id。
     *
     * 课程名/活动名只用于写「最近记录」，通知里没有就留空，界面回退显示
     * 「学习通签到」。
     */
    data class Gesture(
        val activeId: String,
        val courseId: String,
        val classId: String,
        val courseName: String = "",
        val activeName: String = "",
    ) : CxRoute
}

/**
 * 学习通深链的唯一入口，冷启动和前台 `onNewIntent` 走同一条路。
 *
 * [MainActivity] 只负责把 intent 解析成 [CxRoute] 塞进 [pending]；[AppRoot] 用
 * LaunchedEffect 消费一次并置空，再按当前页面栈决定是否前进。这样不再保留
 * 「启动时读 extra」和「onNewIntent」两套逻辑。
 */
object CxDeepLink {

    val pending = MutableStateFlow<CxRoute?>(null)

    /**
     * intent 版：只读 extra，识别规则全部交给纯函数 [routeOf]，方便单测。
     */
    fun routeOf(intent: Intent?): CxRoute? {
        intent ?: return null
        return routeOf(
            page = intent.getStringExtra(CxSignManualNotify.EXTRA_PAGE),
            activeId = intent.getStringExtra(CxSignManualNotify.EXTRA_ACTIVE_ID),
            courseId = intent.getStringExtra(CxSignManualNotify.EXTRA_COURSE_ID),
            classId = intent.getStringExtra(CxSignManualNotify.EXTRA_CLASS_ID),
        )
    }

    /**
     * 识别规则（不依赖 Android，可直接单测）：
     * - `cx_gesture_active_id` 非空 → 手势签到，优先于页面参数；
     * - `cx_page == "sign"` → 签到页；
     * - `cx_page == "inbox"` → 收件箱；
     * - 其它 intent（如签到成功通知只是打开 App）返回 null。
     */
    fun routeOf(
        page: String?,
        activeId: String?,
        courseId: String? = null,
        classId: String? = null,
    ): CxRoute? {
        if (!activeId.isNullOrBlank()) {
            return CxRoute.Gesture(
                activeId = activeId,
                courseId = courseId.orEmpty(),
                classId = classId.orEmpty(),
            )
        }
        return when (page) {
            CxSignManualNotify.PAGE_SIGN -> CxRoute.Sign
            CxSignManualNotify.PAGE_INBOX -> CxRoute.Inbox
            else -> null
        }
    }
}