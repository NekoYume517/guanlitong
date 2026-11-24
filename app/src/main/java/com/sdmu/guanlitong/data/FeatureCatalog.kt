package com.sdmu.guanlitong.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.School
import androidx.compose.ui.graphics.vector.ImageVector

/** 教务系统里的原生页面。 */
enum class JwxtPage { Timetable, Hub, Grades, Exams, Selection, Tasks }

/** 点击功能后要去哪里。 */
sealed interface FeatureAction {
    /** 原生课表页。 */
    data object Timetable : FeatureAction

    /** 教务系统原生页面（成绩 / 考试 / 选课 / 首页）。 */
    data class Jwxt(val page: JwxtPage) : FeatureAction

    /** 打开某个服务的网页；[url] 为空时用服务配置的地址。 */
    data class Web(val serviceId: String, val url: String? = null) : FeatureAction

    /** 打开某个服务的账号页（登录 / 退出 / 记住密码）。 */
    data class Account(val serviceId: String) : FeatureAction

    /** 尝试打开独立 App，未安装时回退到网页。 */
    data class External(val serviceId: String) : FeatureAction
}

/** 功能集里的一个图标：比如「教务系统 → 课表」。 */
data class ServiceFeature(
    val id: String,
    val serviceId: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val action: FeatureAction,
    /** 只在教务数据来源是抢课助手时展示（例如抢课任务）。 */
    val grabSourceOnly: Boolean = false,
)

/** 真正要打开的服务（例如教务系统下的「抢课助手」属于 grab）。用于取品牌色。 */
val ServiceFeature.targetServiceId: String
    get() = when (val action = action) {
        is FeatureAction.Web -> action.serviceId
        is FeatureAction.Account -> action.serviceId
        is FeatureAction.External -> action.serviceId
        is FeatureAction.Jwxt -> serviceId
        FeatureAction.Timetable -> serviceId
    }

/**
 * 功能目录。
 *
 * 「功能」页左侧是服务（二级 tab），右侧是该服务的详细功能图标；
 * 概览页的「快捷功能」组件也从这里取。新增功能只需要往 [features] 里加一条。
 */
object FeatureCatalog {

    val features: List<ServiceFeature> = listOf(
        ServiceFeature(
            id = "jwxt.timetable",
            serviceId = "jwxt",
            title = "课表",
            subtitle = "按周查看 · 本地缓存",
            icon = Icons.Outlined.CalendarMonth,
            action = FeatureAction.Timetable,
        ),
        ServiceFeature(
            id = "jwxt.grades",
            serviceId = "jwxt",
            title = "成绩查询",
            subtitle = "各学期成绩与绩点",
            icon = Icons.Outlined.Assessment,
            action = FeatureAction.Jwxt(JwxtPage.Grades),
        ),
        ServiceFeature(
            id = "jwxt.exams",
            serviceId = "jwxt",
            title = "考试安排",
            subtitle = "考试时间与考场",
            icon = Icons.Outlined.EventNote,
            action = FeatureAction.Jwxt(JwxtPage.Exams),
        ),
        ServiceFeature(
            id = "jwxt.selection",
            serviceId = "jwxt",
            title = "选课",
            subtitle = "可选课程与已选课程",
            icon = Icons.Outlined.HowToReg,
            action = FeatureAction.Jwxt(JwxtPage.Selection),
        ),
        ServiceFeature(
            id = "jwxt.tasks",
            serviceId = "jwxt",
            title = "抢课任务",
            subtitle = "课满时自动蹲余量（抢课助手）",
            icon = Icons.Outlined.Bolt,
            action = FeatureAction.Jwxt(JwxtPage.Tasks),
            grabSourceOnly = true,
        ),
        ServiceFeature(
            id = "jwxt.home",
            serviceId = "jwxt",
            title = "教务首页",
            subtitle = "课表、成绩、选课入口",
            icon = Icons.Outlined.School,
            action = FeatureAction.Jwxt(JwxtPage.Hub),
        ),
    )

    fun forService(serviceId: String): List<ServiceFeature> =
        features.filter { it.serviceId == serviceId }

    fun byId(id: String): ServiceFeature? =
        features.firstOrNull { it.id == id }

    /** 概览页「快捷功能」默认展示的 6 个入口。 */
    val quickFeatures: List<ServiceFeature> = listOf(
        "jwxt.timetable",
        "jwxt.grades",
        "jwxt.selection",
        "jwxt.home",
    ).mapNotNull(::byId)
}