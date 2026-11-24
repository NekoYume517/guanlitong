package com.sdmu.guanlitong.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable

/**
 * 全局 M3E 主题：
 * - 亮色使用 M3E 自带的 expressive 色板；
 * - 动效使用 expressive 弹簧，交互更「有生命力」；
 * - 形状/字体沿用 Material 3 默认的 8 档形状与字体阶梯。
 */
@Composable
fun GuanLiTongTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) darkColorScheme() else expressiveLightColorScheme(),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
