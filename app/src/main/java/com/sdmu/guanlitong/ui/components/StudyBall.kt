package com.sdmu.guanlitong.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 刷课浮球内容（学习通 / 知到共用）：课程名、「当前/总数」、时长与进度条。
 *
 * 宽度上限取「父布局宽度 72%」与 240dp 的较小者：课程名再长也只单行省略，
 * 下面那一行始终单行、不换行，窄屏/大字号下气泡也不会溢出屏幕。
 *
 * 两个模块各自把任务状态映射成 [StudyBallState]，渲染与拖拽逻辑只有这一份。
 */
data class StudyBallState(
    val courseName: String = "",
    /** 「3/12」或「第2轮」。 */
    val primary: String = "",
    /** 「12:03/25:00」这类时长对比。 */
    val secondary: String = "",
    /** 进度条 0..1。 */
    val fraction: Float = 0f,
)

/**
 * 用法：作为内容区最外层的最后一个子元素铺满可用区域即可（例如放在 Scaffold 的
 * content Box 里）。它本身是透明 Box，只有球体那一小块可点、可拖；球体位置只存在
 * 本组件内（进程内 remember），不落盘。
 *
 * @param bottomAvoid 球所在区域底部还要额外让出的高度（例如 App 自己的底部导航栏）。
 */
@Composable
fun StudyBall(
    state: StudyBallState,
    visible: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    bottomAvoid: Dp = 0.dp,
    /** 右上角红色计数徽标；null/0 不画。 */
    badge: Int? = null,
) {
    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    var ballSize by remember { mutableStateOf(IntSize.Zero) }
    // null 表示还没摆过：首次出现时放到右下角，之后保留用户拖到的位置。
    var position by remember { mutableStateOf<Offset?>(null) }
    // 用户手动拖过后才锁定位置；没拖过就始终贴右下角（尺寸量准后会重新贴一次）。
    var movedByUser by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val margin = with(density) { 16.dp.toPx() }
    // 球体还没量出尺寸时先用近似值摆右下角，避免首帧贴在右边缘再跳回来。
    val estimateW = with(density) { 240.dp.toPx() }
    val estimateH = with(density) { 76.dp.toPx() }
    val bottomAvoidPx = with(density) { bottomAvoid.toPx() }
    val maxX = (parentSize.width - ballSize.width).coerceAtLeast(0).toFloat()
    // 球底边永远留在底部让出区之上（App 底栏 / 系统栏），拖到底也只能停在这里。
    val maxY = (parentSize.height - ballSize.height - bottomAvoidPx)
        .coerceAtLeast(0f)

    fun clamp(p: Offset): Offset =
        Offset(p.x.coerceIn(0f, maxX), p.y.coerceIn(0f, maxY))

    // 父布局或球体尺寸变化（旋转、分屏、回到前台）后重新钳制，避免球被甩到边界外。
    LaunchedEffect(visible, parentSize, ballSize, bottomAvoidPx) {
        if (!visible || parentSize == IntSize.Zero) return@LaunchedEffect
        val current = position
        position = if (current == null || !movedByUser) {
            val width = if (ballSize.width > 0) ballSize.width.toFloat() else estimateW
            val height = if (ballSize.height > 0) ballSize.height.toFloat() else estimateH
            clamp(
                Offset(
                    (parentSize.width - width - margin).coerceAtLeast(0f),
                    (parentSize.height - height - margin - bottomAvoidPx).coerceAtLeast(0f),
                )
            )
        } else {
            clamp(current)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { parentSize = it },
        contentAlignment = Alignment.TopStart,
    ) {
        // 明确的最大宽度：屏宽 72% 与 240dp 取小，保证窄屏、大字号下都不越界。
        val bubbleMaxWidth = minOf(maxWidth * 0.72f, 240.dp)
        val pos = position
        if (visible && pos != null) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                    .onSizeChanged { ballSize = it },
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        // 拖动超过 touch slop 才算拖动（detectDragGestures 内部按系统 slop 判定），
                        // 拖动会消费事件，clickable 的点击随即取消，不会「拖完又打开详情」。
                        .pointerInput(parentSize, ballSize, bottomAvoidPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                movedByUser = true
                                position = clamp((position ?: Offset.Zero) + dragAmount)
                            }
                        }
                        .clickable(onClickLabel = "打开刷课详情", role = Role.Button, onClick = onOpen),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (state.courseName.isNotBlank()) {
                        Text(
                            state.courseName,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.secondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    LinearProgressIndicator(
                        progress = { state.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp)
                            .height(3.dp),
                    )
                }
                }
                if (badge != null && badge > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 5.dp, y = (-5).dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            badge.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}