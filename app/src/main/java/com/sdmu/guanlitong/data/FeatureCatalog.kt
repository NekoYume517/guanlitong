package com.sdmu.guanlitong.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CastForEducation
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.ui.graphics.vector.ImageVector

/** 教务系统里的原生页面。 */
enum class JwxtPage { Timetable, Hub, Grades, Exams, Selection, Tasks }

/** 山管app 内置的原生页面。 */
enum class SdgyPage { Hub, Timetable, Pay, Power, Shot }

/** 第二课堂内置的原生页面。 */
enum class PuPage { Activities, Mine, Credit, Friends, PersonalCode, Scan, SignCode }

/** 学习通内置的原生页面。 */
enum class CxPage { Courses, CourseDetail, Materials, Sign, SignSettings, Inbox }

/** 知到（智慧树）内置的原生页面。 */
enum class ZhsPage { Courses }

/** 山管官方网页功能，使用与服务端功能表一致的免登路由。 */
data class SdgyWebAction(
    val title: String,
    val appId: String,
    val url: String,
    val lx: String,
    /** 官方用 `lx=5` 标记「跳到另一个 App」，这类条目不走内置网页。 */
    val opensExternalApp: Boolean = false,
    /** 官方 App 自己的原生功能（拍照、定位等），没有网页地址，管院通打不开。 */
    val isAppInternal: Boolean = false,
)

/** 点击功能后要去哪里。 */
sealed interface FeatureAction {
    /** 原生课表页。 */
    data object Timetable : FeatureAction

    /** 教务系统原生页面（成绩 / 考试 / 选课 / 首页）。 */
    data class Jwxt(val page: JwxtPage) : FeatureAction

    /** 山管app 内置原生页面。 */
    data class Sdgy(val page: SdgyPage) : FeatureAction

    /** 第二课堂内置原生页面。 */
    data class Pu(val page: PuPage) : FeatureAction

    /** 学习通内置原生页面。 */
    data class Cx(val page: CxPage) : FeatureAction

    /** 知到（智慧树）内置原生页面。 */
    data class Zhs(val page: ZhsPage) : FeatureAction

    /** 打开某个服务的网页；[url] 为空时用服务配置的地址。 */
    data class Web(val serviceId: String, val url: String? = null) : FeatureAction

    /** 山管官方动态应用；appId 对应服务端 YYID，复用现有网页免登路由。 */
    data class SdgyApp(val action: SdgyWebAction) : FeatureAction

    /** 校园网认证：深澜门户登录、注销与联网状态。 */
    data object Srun : FeatureAction

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
    /** 山管官方 YYTB 图标；原生功能为空。 */
    val iconUrl: String = "",
)

/** 真正要打开的服务（例如教务系统下的「抢课助手」属于 grab）。用于取品牌色。 */
val ServiceFeature.targetServiceId: String
    get() = when (val action = action) {
        is FeatureAction.Web -> action.serviceId
        is FeatureAction.Account -> action.serviceId
        is FeatureAction.External -> action.serviceId
        is FeatureAction.Jwxt -> serviceId
        is FeatureAction.Sdgy -> serviceId
        is FeatureAction.Pu -> serviceId
        is FeatureAction.Cx -> serviceId
        is FeatureAction.Zhs -> serviceId
        FeatureAction.Srun -> "srun"
        is FeatureAction.SdgyApp -> serviceId
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
            subtitle = "按周查看课程",
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
            subtitle = "可选与已选课程",
            icon = Icons.Outlined.HowToReg,
            action = FeatureAction.Jwxt(JwxtPage.Selection),
        ),
        ServiceFeature(
            id = "jwxt.tasks",
            serviceId = "jwxt",
            title = "抢课任务",
            subtitle = "满员后自动等名额",
            icon = Icons.Outlined.Bolt,
            action = FeatureAction.Jwxt(JwxtPage.Tasks),
            grabSourceOnly = true,
        ),
        ServiceFeature(
            id = "sdgy.timetable",
            serviceId = "sdgy",
            title = "课表",
            subtitle = "按周查看 · 课程详情",
            icon = Icons.Outlined.CalendarMonth,
            // 与教务课表共用同一个页面，山管数据只补充统计与名单。
            action = FeatureAction.Timetable,
        ),
        ServiceFeature(
            id = "sdgy.pay",
            serviceId = "sdgy",
            title = "一卡通",
            subtitle = "余额 · 付款码",
            icon = Icons.Outlined.CreditCard,
            action = FeatureAction.Sdgy(SdgyPage.Pay),
        ),
        ServiceFeature(
            id = "sdgy.power",
            serviceId = "sdgy",
            title = "宿舍用电",
            subtitle = "剩余电量 · 近 14 天用量",
            icon = Icons.Outlined.ElectricalServices,
            action = FeatureAction.Sdgy(SdgyPage.Power),
        ),
        ServiceFeature(
            id = "sdgy.shot",
            serviceId = "sdgy",
            title = "请销假",
            subtitle = "生成申请图片",
            icon = Icons.Outlined.EditNote,
            action = FeatureAction.Sdgy(SdgyPage.Shot),
        ),
ServiceFeature(
            id = "pu.activities",
            serviceId = "pu",
            title = "活动列表",
            subtitle = "全部活动 · 搜索",
            icon = Icons.Outlined.EventNote,
            action = FeatureAction.Pu(PuPage.Activities),
        ),
        ServiceFeature(
            id = "pu.mine",
            serviceId = "pu",
            title = "我的活动",
            subtitle = "进行中 · 已结束",
            icon = Icons.Outlined.History,
            action = FeatureAction.Pu(PuPage.Mine),
        ),
        ServiceFeature(
            id = "pu.credit",
            serviceId = "pu",
            title = "学时统计",
            subtitle = "总量 · 按年聚合",
            icon = Icons.Outlined.WorkspacePremium,
            action = FeatureAction.Pu(PuPage.Credit),
        ),
        ServiceFeature(
            id = "pu.friends",
            serviceId = "pu",
            title = "好友",
            subtitle = "关注 · 粉丝 · 互关",
            icon = Icons.Outlined.People,
            action = FeatureAction.Pu(PuPage.Friends),
        ),
        ServiceFeature(
            id = "pu.personalcode",
            serviceId = "pu",
            title = "我的二维码",
            subtitle = "个人码 · 10s 换码",
            icon = Icons.Outlined.QrCode2,
            action = FeatureAction.Pu(PuPage.PersonalCode),
        ),
        ServiceFeature(
            id = "pu.scan",
            serviceId = "pu",
            title = "扫一扫",
            subtitle = "签到码 · 个人码",
            icon = Icons.Outlined.QrCodeScanner,
            action = FeatureAction.Pu(PuPage.Scan),
        ),
        ServiceFeature(
            id = "pu.signcode",
            serviceId = "pu",
            title = "生成签到码",
            subtitle = "主办方 · 动态码",
            icon = Icons.Outlined.AddBox,
            action = FeatureAction.Pu(PuPage.SignCode),
        ),
        ServiceFeature(
            id = "cx.courses",
            serviceId = "cx",
            title = "我的课程",
            subtitle = "课程封面 · 教师",
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            action = FeatureAction.Cx(CxPage.Courses),
        ),
        ServiceFeature(
            id = "cx.sign",
            serviceId = "cx",
            title = "签到",
            subtitle = "进行中 · 记录",
            icon = Icons.Outlined.TaskAlt,
            action = FeatureAction.Cx(CxPage.Sign),
        ),
        ServiceFeature(
            id = "cx.checkin",
            serviceId = "cx",
            title = "自动签到",
            subtitle = "签到 · 签退 · 定位",
            icon = Icons.Outlined.HowToReg,
            action = FeatureAction.Cx(CxPage.SignSettings),
        ),
        ServiceFeature(
            id = "cx.notices",
            serviceId = "cx",
            title = "收件箱",
            subtitle = "通知 · 签到 · 作业",
            icon = Icons.Outlined.Email,
            action = FeatureAction.Cx(CxPage.Inbox),
        ),
        ServiceFeature(
            id = "zhs.courses",
            serviceId = "zhs",
            title = "我的课程",
            subtitle = "共享课 · 翻转课",
            icon = Icons.Outlined.CastForEducation,
            action = FeatureAction.Zhs(ZhsPage.Courses),
        ),
        ServiceFeature(
            id = "srun.login",
            serviceId = "srun",
            title = "校园网认证",
            subtitle = "登录 · 注销 · 联网状态",
            icon = Icons.Outlined.Wifi,
            action = FeatureAction.Srun,
        ),
    )

    fun forService(serviceId: String): List<ServiceFeature> =
        features.filter { it.serviceId == serviceId }

    fun byId(id: String): ServiceFeature? =
        features.firstOrNull { it.id == id }

    fun sdgyApp(entry: com.sdmu.guanlitong.data.sdgy.SdgyAppEntry): ServiceFeature {
        val native = entry.nativePage
        val action = if (native == SdgyPage.Timetable) {
            FeatureAction.Timetable
        } else if (native != null) {
            FeatureAction.Sdgy(native)
        } else {
            FeatureAction.SdgyApp(
                SdgyWebAction(
                    title = entry.name,
                    appId = entry.id,
                    url = entry.url,
                    lx = entry.lx,
                    opensExternalApp = entry.opensExternalApp,
                    isAppInternal = entry.isAppInternal,
                )
            )
        }
        return ServiceFeature(
            id = "sdgy.app.${entry.id}",
            serviceId = "sdgy",
            title = entry.name,
            subtitle = entry.description,
            icon = iconFor(entry),
            iconUrl = entry.iconUrl,
            action = action,
        )
    }

    private fun iconFor(entry: com.sdmu.guanlitong.data.sdgy.SdgyAppEntry): ImageVector = when {
        entry.name.contains("课表") -> Icons.Outlined.CalendarMonth
        entry.name.contains("成绩") || entry.name.contains("报告") -> Icons.Outlined.Assessment
        entry.name.contains("请假") || entry.name.contains("办事") -> Icons.Outlined.EventNote
        entry.name.contains("卡") || entry.name.contains("余额") || entry.name.contains("账单") ->
            Icons.Outlined.CreditCard
        entry.name.contains("电") || entry.name.contains("宿舍") -> Icons.Outlined.ElectricalServices
        entry.name.contains("选课") -> Icons.Outlined.HowToReg
        else -> Icons.Outlined.PhoneAndroid
    }

    /** 概览页「快捷功能」默认展示的入口。 */
    val quickFeatures: List<ServiceFeature> = listOf(
        "jwxt.timetable",
        "jwxt.grades",
        "jwxt.selection",
    ).mapNotNull(::byId)
}