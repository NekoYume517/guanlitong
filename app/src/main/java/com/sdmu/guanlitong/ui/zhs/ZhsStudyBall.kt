package com.sdmu.guanlitong.ui.zhs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.zhs.ZhsTaskController
import com.sdmu.guanlitong.data.zhs.formatClock
import com.sdmu.guanlitong.ui.components.StudyBall
import com.sdmu.guanlitong.ui.components.StudyBallState

/**
 * 知到刷课浮球：与学习通的刷课球共用 [StudyBall] 本体。
 *
 * 定时刷课显示「i/n 或 第N轮」、「累计新增/目标」；单节刷课没有目标，
 * 只显示本节已刷时长与本节进度。学习通任务在跑时不显示（避免两个球叠在一起），
 * 由 AppRoot 通过 [hidden] 传进来。
 */
@Composable
fun ZhsStudyBall(
    onOpen: () -> Unit,
    hidden: Boolean = false,
    modifier: Modifier = Modifier,
    bottomAvoid: Dp = 0.dp,
) {
    val state by ZhsTaskController.state.collectAsState()
    val fraction = if (state.target > 0) state.percent / 100f else state.currentProgress / 100f
    StudyBall(
        state = StudyBallState(
            courseName = state.courseName,
            primary = state.current.ifEmpty { "单节" },
            secondary = if (state.target > 0) {
                "${formatClock(state.gained)}/${formatClock(state.target)}"
            } else {
                formatClock(state.gained)
            },
            fraction = fraction,
        ),
        visible = !hidden && state.running,
        onOpen = onOpen,
        modifier = modifier,
        bottomAvoid = bottomAvoid,
    )
}