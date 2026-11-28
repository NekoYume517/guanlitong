package com.sdmu.guanlitong.ui.cx

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.cx.cxFormatDuration
import com.sdmu.guanlitong.service.CxTaskState
import com.sdmu.guanlitong.ui.components.StudyBall
import com.sdmu.guanlitong.ui.components.StudyBallState

/**
 * 浮球本体：状态由调用方喂。正式入口用 [CxTaskStatus]，debug 预览喂假状态，
 * 两者共用同一套宽度上限、省略与拖拽逻辑，预览才代表真实渲染效果。
 */
@Composable
internal fun CxStudyBallContent(
    state: CxTaskState,
    visible: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    bottomAvoid: Dp = 0.dp,
) {
    StudyBall(
        state = StudyBallState(
            courseName = state.courseName,
            primary = "${state.currentIndex}/${state.total}",
            secondary = "${cxFormatDuration(state.position)}/${cxFormatDuration(state.duration)}",
            fraction = state.taskPercent / 100f,
        ),
        visible = visible,
        onOpen = onOpen,
        modifier = modifier,
        bottomAvoid = bottomAvoid,
    )
}