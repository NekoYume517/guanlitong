package com.sdmu.guanlitong.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 全局 M3E 主题：
 * - 亮色使用 M3E 自带的 expressive 色板；暗色沿用基线暗色板；
 * - 动效使用 expressive 弹簧，交互更「有生命力」；
 * - 形状用 M3E 的 8 档尺度，卡片、弹窗、胶囊各归各档。
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    largeIncreased = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(40.dp),
)

@Composable
fun GuanLiTongTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) darkColorScheme() else expressiveLightColorScheme(),
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        content = content,
    )
}
