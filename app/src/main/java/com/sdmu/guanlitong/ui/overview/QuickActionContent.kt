package com.sdmu.guanlitong.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.ServiceFeature
import com.sdmu.guanlitong.data.WidgetSize

/** Display plus one gesture per tile: tap, long-press menu, or release into dashboard drag. */
@Composable
internal fun QuickActionsContent(
    widgetId: String,
    size: WidgetSize,
    features: List<ServiceFeature>,
    menuTargetIndex: Int?,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenMenu: (QuickActionTarget, Long) -> Unit,
    onQuickActionPress: (Offset) -> Long,
    onEdit: () -> Unit,
    onTilePositioned: (QuickActionTarget) -> Unit,
) {
    WidgetCard(
        title = "快捷功能",
        size = size,
        actionContent = {
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑快捷功能")
            }
        },
    ) {
        @Composable
        fun Entry(index: Int, feature: ServiceFeature, modifier: Modifier) {
            val shape = RoundedCornerShape(
                when (size) {
                    WidgetSize.Small -> 12.dp
                    WidgetSize.Medium -> 16.dp
                    WidgetSize.Large -> 18.dp
                },
            )
            var target = QuickActionTarget(widgetId, index, feature, Rect.Zero)
            Box(
                modifier = modifier
                    .onGloballyPositioned {
                        target = QuickActionTarget(widgetId, index, feature, it.boundsInRoot())
                        onTilePositioned(target)
                    }
                    .graphicsLayer {
                        alpha = if (menuTargetIndex == index) 0f else 1f
                    }
                    .clip(shape)
                    .pointerInput(widgetId, feature.id, index) {
                        detectTapGestures(
                            onTap = { onOpenFeature(feature) },
                            onLongPress = { offset ->
                                val token = onQuickActionPress(offset)
                                onOpenMenu(target, token)
                            },
                        )
                    },
            ) {
                QuickActionVisual(feature, quickActionKind(size))
            }
        }

        when (size) {
            WidgetSize.Small -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (features.size == 2) {
                    // 两个入口在半宽格子里标题会被截断，小卡片改成上下两行整行显示。
                    features.forEachIndexed { index, feature ->
                        Entry(index, feature, Modifier.fillMaxWidth())
                    }
                } else {
                    features.chunked(2).forEachIndexed { rowIndex, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEachIndexed { columnIndex, feature ->
                                Entry(rowIndex * 2 + columnIndex, feature, Modifier.weight(1f))
                            }
                            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            WidgetSize.Medium -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                features.forEachIndexed { index, feature ->
                    Entry(index, feature, Modifier.weight(1f))
                }
            }
            WidgetSize.Large -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                features.chunked(2).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { columnIndex, feature ->
                            Entry(rowIndex * 2 + columnIndex, feature, Modifier.weight(1f))
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

internal fun quickActionKind(size: WidgetSize) = when (size) {
    WidgetSize.Small -> QuickActionKind.Mini
    WidgetSize.Medium -> QuickActionKind.Tile
    WidgetSize.Large -> QuickActionKind.Card
}

internal enum class QuickActionKind { Mini, Tile, Card }

/** Shared by real tiles and the clear popup copy. */
@Composable
internal fun QuickActionVisual(feature: ServiceFeature, kind: QuickActionKind) {
    val accent = accentOf(feature)
    when (kind) {
        QuickActionKind.Mini -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f))
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionIcon(feature, accent, 24.dp, 14.dp)
            Spacer(Modifier.width(6.dp))
            ActionText(feature.title, MaterialTheme.typography.labelSmall)
        }
        QuickActionKind.Tile -> Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ActionIcon(feature, accent, 28.dp, 16.dp)
                Spacer(Modifier.height(4.dp))
                ActionText(feature.title, MaterialTheme.typography.labelSmall)
            }
        }
        QuickActionKind.Card -> Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionIcon(feature, accent, 36.dp, 19.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    ActionText(feature.title, MaterialTheme.typography.titleSmall, FontWeight.Medium)
                    Text(
                        text = feature.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    fontWeight: FontWeight? = null,
) {
    Text(text = text, style = style, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ActionIcon(feature: ServiceFeature, accent: Color, size: Dp, iconSize: Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val officialIcon = com.sdmu.guanlitong.ui.components.rememberRemoteIcon(feature.iconUrl)
        if (officialIcon != null) {
            Image(
                bitmap = officialIcon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(iconSize),
            )
        } else {
            Icon(feature.icon, contentDescription = null, tint = accent, modifier = Modifier.size(iconSize))
        }
    }
}
