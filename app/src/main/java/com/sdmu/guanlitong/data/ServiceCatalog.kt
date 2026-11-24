package com.sdmu.guanlitong.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PhoneAndroid
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
        SchoolService(
            id = "sdgy",
            name = "山管app",
            summary = "移动校园 · 统一身份认证",
            defaultUrl = "http://222.206.86.42:8081",
            loginMode = LoginMode.CREDENTIAL,
            icon = Icons.Outlined.PhoneAndroid,
            accent = Color(0xFF1E9E6A),
        ),
        SchoolService(
            id = "other",
            name = "其他",
            summary = "扫一扫 · 小白超人",
            defaultUrl = "",
            loginMode = LoginMode.WEB,
            icon = Icons.Outlined.MoreHoriz,
            accent = Color(0xFF7657A5),
            requiresAccount = false,
        ),
    )

    /** 「功能」页左侧：教务系统、山管 App、其他；后两者互不混入原生工具。 */
    val featureServices: List<SchoolService>
        get() = services.filter { it.id == "jwxt" || it.id == "sdgy" || it.id == "other" }

    /** 「我的」与概览「账号状态」展示需要独立登录的服务；抢课助手只作为数据源。 */
    val accountServices: List<SchoolService>
        get() = services.filter { it.id == "jwxt" || it.id == "sdgy" }

    fun byId(id: String): SchoolService? = services.firstOrNull { it.id == id }
}
