package com.sdmu.guanlitong.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.SchoolService

/** 服务图标：带品牌色的圆形底。 */
@Composable
fun ServiceBadge(service: SchoolService, size: Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(service.accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = service.icon,
            contentDescription = null,
            tint = service.accent,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/** 登录状态小圆点。 */
@Composable
fun StatusDot(loggedIn: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                if (loggedIn) Color(0xFF2E9B5B) else MaterialTheme.colorScheme.outlineVariant
            ),
    )
}

/** 状态文案：已登录 / 未登录。 */
@Composable
fun StatusText(loggedIn: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(loggedIn)
        Text(
            text = if (loggedIn) "已登录" else "未登录",
            style = MaterialTheme.typography.labelMedium,
            color = if (loggedIn) Color(0xFF2E9B5B) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun VerticalGap(height: Dp = 12.dp) {
    Spacer(modifier = Modifier.height(height))
}

@Composable
fun HorizontalGap(width: Dp = 12.dp) {
    Spacer(modifier = Modifier.width(width))
}