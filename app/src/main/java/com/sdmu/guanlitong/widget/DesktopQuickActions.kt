package com.sdmu.guanlitong.widget

import android.content.Context
import android.content.Intent
import com.sdmu.guanlitong.MainActivity
import com.sdmu.guanlitong.data.CxPage
import com.sdmu.guanlitong.data.FeatureAction
import com.sdmu.guanlitong.data.FeatureCatalog
import com.sdmu.guanlitong.service.CxSignManualNotify

/**
 * 桌面「快捷功能」里的一个入口。
 *
 * 只从本地 [FeatureCatalog] 取标题，不读取也不请求任何服务数据；
 * [cxPage] 非空时复用 MainActivity 现有的 `cx_page` 深链参数直达学习通页面。
 */
data class DesktopQuickAction(
    val featureId: String,
    val title: String,
    /** [CxSignManualNotify.PAGE_SIGN] / [CxSignManualNotify.PAGE_INBOX]；其它入口为空。 */
    val cxPage: String? = null,
)

/**
 * 桌面快捷功能的固定顺序：先放这轮新增的学习通、第二课堂入口，
 * 再保留课表、成绩这些老入口。小号取前 2 个，中号 4 个，大号 8 个。
 */
object DesktopQuickActions {

    private val order = listOf(
        "cx.sign",
        "cx.notices",
        "cx.courses",
        "cx.checkin",
        "pu.activities",
        "jwxt.timetable",
        "jwxt.grades",
        "pu.credit",
    )

    fun all(): List<DesktopQuickAction> = order.mapNotNull { id ->
        FeatureCatalog.byId(id)?.let { feature ->
            DesktopQuickAction(
                featureId = feature.id,
                title = feature.title,
                cxPage = cxPageOf(feature.action),
            )
        }
    }

    fun forSize(size: DesktopSize): List<DesktopQuickAction> = all().take(limitFor(size))

    fun limitFor(size: DesktopSize): Int = when (size) {
        DesktopSize.Small -> 2
        DesktopSize.Medium -> 4
        DesktopSize.Large -> 8
    }

    /** 只有学习通签到页与收件箱有现成的深链参数，其余入口打开 App 首页。 */
    fun cxPageOf(action: FeatureAction): String? = when (action) {
        is FeatureAction.Cx -> when (action.page) {
            CxPage.Sign -> CxSignManualNotify.PAGE_SIGN
            CxPage.Inbox -> CxSignManualNotify.PAGE_INBOX
            else -> null
        }

        else -> null
    }
}

/** 桌面卡片点击后的启动 Intent；有深链参数时交给 MainActivity 现有的解析入口。 */
fun quickActionIntent(context: Context, action: DesktopQuickAction): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        action.cxPage?.let { putExtra(CxSignManualNotify.EXTRA_PAGE, it) }
    }