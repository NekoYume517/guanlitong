package com.sdmu.guanlitong.ui.cx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.service.CxTaskState

/**
 * 刷课浮球的调试预览开关（截图验收用）。
 *
 * 入口只在 debug 构建里存在：`com.sdmu.guanlitong.debug.StudyBallPreviewReceiver`
 * 收到显式广播后改这里，AppRoot 在 `BuildConfig.DEBUG` 且开关打开时用假状态渲染浮球。
 * 预览纯渲染——不启动 CxTaskService、不发任何网络请求、不写 CxTaskStatus。
 */
object CxStudyBallPreviewState {
    const val ACTION_SHOW = "com.sdmu.guanlitong.debug.SHOW_STUDY_BALL"
    const val ACTION_HIDE = "com.sdmu.guanlitong.debug.HIDE_STUDY_BALL"

    var visible by mutableStateOf(false)
        private set

    fun show() {
        visible = true
    }

    fun hide() {
        visible = false
    }
}

/** 预览假状态：超长课程名 + 12/48 + 00:12:34/00:48:00。 */
private val PreviewStudyState = CxTaskState(
    running = true,
    courseName = "高等数学（2025级）第三章 定积分的换元积分法与分部积分法",
    phase = "第 1 轮",
    currentIndex = 12,
    total = 48,
    currentTask = "3.2 定积分的换元积分法",
    position = 754,
    duration = 2880,
    message = "调试预览：不启动刷课、不联网",
)

/** debug 预览用的浮球：与正式浮球共用 [CxStudyBallContent]，拖拽/点击/停靠边界一致。 */
@Composable
fun CxStudyBallPreview(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    bottomAvoid: Dp = 0.dp,
) {
    CxStudyBallContent(
        state = PreviewStudyState,
        visible = true,
        onOpen = onOpen,
        modifier = modifier,
        bottomAvoid = bottomAvoid,
    )
}