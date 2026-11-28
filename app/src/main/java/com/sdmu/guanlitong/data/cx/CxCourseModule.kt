package com.sdmu.guanlitong.data.cx

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PlayLesson
import androidx.compose.ui.graphics.vector.ImageVector
import com.sdmu.guanlitong.data.CxPage

/**
 * 课程功能页上的一个模块入口。
 *
 * [target] 为空表示该模块尚未接入：UI 显示「即将支持」并禁用点击，不伪造入口。
 * 以后接入新模块时只往 [cxCourseModules] 里加一行，页面和导航都不用改。
 */
data class CxCourseModule(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    /** 点击后要去的原生页；null = 尚未接入。 */
    val target: CxPage?,
) {
    val available: Boolean get() = target != null
}

/**
 * 课程功能页的模块列表，顺序即展示顺序。
 *
 * 章节刷课与资料已接入；作业等学习通侧能力还没对接，先如实标成未接入，
 * 等对应数据层与页面落地后再把 target 填上。
 */
val cxCourseModules: List<CxCourseModule> = listOf(
    CxCourseModule(
        id = "chapters",
        title = "章节刷课",
        description = "扫描章节树，逐节播放音视频",
        icon = Icons.Outlined.PlayLesson,
        target = CxPage.CourseDetail,
    ),
    CxCourseModule(
        id = "materials",
        title = "资料",
        description = "课程资料与课件",
        icon = Icons.Outlined.Description,
        target = CxPage.Materials,
    ),
    CxCourseModule(
        id = "works",
        title = "作业",
        description = "课程作业与提交",
        icon = Icons.Outlined.Assignment,
        target = null,
    ),
)

/** 按 id 取模块点开后的目标页；未接入或 id 不存在都返回 null。 */
fun cxCourseModuleTarget(id: String): CxPage? =
    cxCourseModules.firstOrNull { it.id == id }?.target