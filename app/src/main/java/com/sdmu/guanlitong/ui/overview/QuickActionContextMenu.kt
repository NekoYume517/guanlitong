package com.sdmu.guanlitong.ui.overview

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.sdmu.guanlitong.data.ServiceFeature
import com.sdmu.guanlitong.data.WidgetSize
import kotlin.math.roundToInt

/** Coordinates are window-relative, matching PopupPositionProvider's contract. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun QuickActionContextMenu(
    feature: ServiceFeature,
    anchor: Rect,
    size: WidgetSize,
    visibleState: Boolean,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    windowSize: IntSize,
) {
    val density = LocalDensity.current
    val visible = remember { MutableTransitionState(false) }
    val motion = MaterialTheme.motionScheme
    LaunchedEffect(Unit) { visible.targetState = true }
    LaunchedEffect(visibleState) {
        if (!visibleState) visible.targetState = false
    }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && !visibleState) onDismiss()
    }

    Box(modifier = modifier) {
        Popup(
            popupPositionProvider = object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: androidx.compose.ui.unit.IntRect,
                    windowSize: androidx.compose.ui.unit.IntSize,
                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                    popupContentSize: androidx.compose.ui.unit.IntSize,
                ) = IntOffset.Zero
            },
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
            onDismissRequest = onDismiss,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                )
                AnimatedVisibility(
                    visibleState = visible,
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
                        QuickActionVisual(feature, quickActionKind(size))
                    }
                }

                val menuWidth = with(density) { 236.dp.toPx() }
                val menuHeight = with(density) { 105.dp.toPx() }
                val gap = with(density) { 10.dp.toPx() }
                val margin = with(density) { 16.dp.toPx() }
                val x = (anchor.center.x - menuWidth / 2)
                    .coerceIn(margin, (windowSize.width - menuWidth - margin).coerceAtLeast(margin))
                val below = anchor.bottom + gap
                val y = if (below + menuHeight <= windowSize.height - margin) {
                    below
                } else {
                    (anchor.top - gap - menuHeight).coerceAtLeast(margin)
                }
                val originY = if (y > anchor.bottom) 0f else 1f
                AnimatedVisibility(
                    visibleState = visible,
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
                        modifier = Modifier.width(236.dp),
                    ) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            QuickMenuAction(
                                text = "更换此功能",
                                icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = onEdit,
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            QuickMenuAction(
                                text = "调整顺序",
                                icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                                onClick = onEdit,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickMenuAction(text: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
