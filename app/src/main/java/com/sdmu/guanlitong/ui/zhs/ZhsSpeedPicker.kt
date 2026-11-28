package com.sdmu.guanlitong.ui.zhs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.zhs.ZhsSpeedChoice

/**
 * 倍速档位选择器：章节页开始前与进度页运行中共用。
 *
 * 运行中调用方把选中项接 [com.sdmu.guanlitong.data.zhs.ZhsTaskController.chooseSpeed]，
 * 正在播的视频在下一次上报前就按新倍率重排。
 */
@Composable
internal fun SpeedPicker(
    selected: ZhsSpeedChoice,
    enabled: Boolean,
    onSelect: (ZhsSpeedChoice) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FilterChip(
                selected = !selected.custom,
                onClick = { onSelect(ZhsSpeedChoice.DEFAULT) },
                enabled = enabled,
                label = { Text("默认") },
            )
        }
        items(ZhsSpeedChoice.RATES) { rate ->
            FilterChip(
                selected = selected.custom && selected.rate == rate,
                onClick = { onSelect(ZhsSpeedChoice.custom(rate)) },
                enabled = enabled,
                label = { Text("${rate.toInt()}x") },
            )
        }
    }
}