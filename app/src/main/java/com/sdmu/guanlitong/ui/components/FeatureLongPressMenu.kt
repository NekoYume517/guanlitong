package com.sdmu.guanlitong.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/** 菜单宽度，和概览快捷功能的长按菜单一致。 */
private val MenuWidth = 236.dp

/** 菜单和被长按元素之间的距离。 */
private val MenuGap = 10.dp

/** 菜单离屏幕边缘至少留的距离。 */
private val MenuMargin = 16.dp

/** 菜单上下各留的内边距。 */
private val MenuVerticalPadding = 6.dp

/** 菜单项的高度。 */
private val MenuItemHeight = 48.dp

/**
 * 长按后弹出的操作菜单：被长按的元素浮在最上层保持清晰，菜单贴在它正下方；
 * 下方放不下就翻到上方，左右超出屏幕时会往里收。
 *
 * 位置和尺寸与概览页快捷功能的长按菜单一致。
 * 只负责弹出层；虚化背景由调用方自己处理。
 *
 * @param anchor 被长按元素的位置，窗口坐标。
 * @param itemCount 菜单项的个数。位置要在第一帧就算准，不能等测量结果，所以用行数算菜单高度。
 * @param bounds 页面在窗口里占的范围，窗口坐标。菜单只摆在它里面，
 *   这样不会压到底部导航栏上；不能用整个窗口，窗口底下还叠着导航栏。
 * @param visible 菜单是否应该显示。调用方把它改成 false，组件先播退场动画，再回调 [onDismiss]。
 * @param onDismiss 退场动画播完后调用，调用方在这里真正移除菜单。
 * @param onRequestClose 点了菜单外面的空白处。组件不直接关，交给调用方把 [visible] 改成 false，
 *   这样所有关闭方式都走同一条退场路径。
 * @param lifted 浮在最上层的那个元素，画在 [anchor] 的位置上。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeatureLongPressMenu(
    anchor: Rect,
    itemCount: Int,
    bounds: Rect,
    visible: Boolean,
    onDismiss: () -> Unit,
    onRequestClose: () -> Unit,
    modifier: Modifier = Modifier,
    lifted: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val motion = MaterialTheme.motionScheme
    // 先播放出现动画；visible 变 false 时先播完退场动画，再真正移除。
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { shown.targetState = true }
    LaunchedEffect(visible) {
        if (!visible) shown.targetState = false
    }
    LaunchedEffect(shown.isIdle, shown.currentState, visible) {
        if (shown.isIdle && !shown.currentState && !visible) onDismiss()
    }

    Popup(
        // 弹窗铺满整个窗口，位置全靠下面的对齐和 offset。
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset.Zero
        },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            // 点菜单外面关闭：交给调用方，走统一的退场路径。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRequestClose,
                    ),
            )

            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn(motion.fastEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier.offset {
                    IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt())
                },
            ) {
                Box(
                    modifier = Modifier
                        .size(
                            with(density) { anchor.width.toDp() },
                            with(density) { anchor.height.toDp() },
                        )
                        .clearAndSetSemantics { },
                ) {
                    lifted?.invoke()
                }
            }

            // 菜单宽度固定，高度按行数算，所以第一帧位置就是准的。
            val menuWidth = with(density) { MenuWidth.toPx() }
            val menuHeight = with(density) { MenuVerticalPadding.toPx() * 2 + MenuItemHeight.toPx() * itemCount }
            val gap = with(density) { MenuGap.toPx() }
            val margin = with(density) { MenuMargin.toPx() }
            val x = (anchor.center.x - menuWidth / 2)
                .coerceIn(
                    bounds.left + margin,
                    (bounds.right - menuWidth - margin).coerceAtLeast(bounds.left + margin),
                )
            val below = anchor.bottom + gap
            val fitsBelow = below + menuHeight <= bounds.bottom - margin
            val y = if (fitsBelow) {
                below
            } else {
                (anchor.top - gap - menuHeight).coerceAtLeast(bounds.top + margin)
            }
            // 从被长按元素的那一侧长出来：在下方就从顶部展开，翻到上方就从底部展开。
            val originY = if (fitsBelow) 0f else 1f
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(
                    animationSpec = motion.fastSpatialSpec(),
                    initialScale = 0.8f,
                    transformOrigin = TransformOrigin(0.5f, originY),
                ),
                exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(
                    animationSpec = motion.fastSpatialSpec(),
                    targetScale = 0.8f,
                    transformOrigin = TransformOrigin(0.5f, originY),
                ),
                modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 6.dp,
                    modifier = Modifier.width(MenuWidth),
                ) {
                    Column(modifier = Modifier.padding(vertical = MenuVerticalPadding)) {
                        content()
                    }
                }
            }
        }
    }
}

/** 长按菜单里的一行。 */
@Composable
fun LongPressMenuItem(text: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MenuItemHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
