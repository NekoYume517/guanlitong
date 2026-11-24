package com.sdmu.guanlitong.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.School
import androidx.compose.ui.graphics.Color

/**
 * 校园服务目录。目前只保留官方教务系统与抢课助手，其他服务等确认后逐个加回来。
 */
object ServiceCatalog {

    val services: List<SchoolService> = listOf(
        SchoolService(
            id = "jwxt",
            name = "教务系统",
            summary = "选课 · 成绩 · 考试安排",
            defaultUrl = "https://jwxt.sdmu.edu.cn/jwglxt/xtgl/login_slogin.html",
            loginMode = LoginMode.WEB,
            icon = Icons.Outlined.School,
            accent = Color(0xFF2F6BFF),
        ),
        SchoolService(
            id = "grab",
            name = "抢课助手",
            summary = "自建抢课系统 · 教务数据代理",
            defaultUrl = "https://jwxt.bansonic.top",
            loginMode = LoginMode.CREDENTIAL,
            icon = Icons.Outlined.Bolt,
            accent = Color(0xFFF08A24),
        ),
    )

    /** 「功能」页左侧二级栏展示的服务：教务数据来源可切换，抢课助手不单独占 tab。 */
    val featureServices: List<SchoolService>
        get() = services.filter { it.id == "jwxt" }

    /** 「我的」与概览「账号状态」只展示这一个账号入口；抢课助手只作为数据源。 */
    val accountServices: List<SchoolService>
        get() = services.filter { it.id == "jwxt" }

    fun byId(id: String): SchoolService? = services.firstOrNull { it.id == id }
}
