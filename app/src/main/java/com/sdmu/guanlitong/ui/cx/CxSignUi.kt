package com.sdmu.guanlitong.ui.cx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.cx.CxInboxType
import com.sdmu.guanlitong.data.cx.CxNoticeEntity
import com.sdmu.guanlitong.data.cx.CxOpenSign
import com.sdmu.guanlitong.data.cx.CxSignRestriction
import com.sdmu.guanlitong.data.cx.cxRemainText
import com.sdmu.guanlitong.data.cx.restrictionBannerText
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 收件箱置顶与签到页共用的「进行中签到」卡片：课程、活动名、方式标签、
 * 动作文案与结束倒计时。手势活动点开走九宫格，其它走收件箱详情的手动签到路径。
 */
@Composable
internal fun CxOpenSignCard(
    sign: CxOpenSign,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onExpire: () -> Unit = {},
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sign.courseName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = sign.modeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(
                text = sign.activeName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                )
                SignCountdown(
                    endsAtMs = sign.endsAtMs,
                    endTime = sign.endTime,
                    onExpire = onExpire,
                )
            }
        }
    }
}

/** 账号被学习通限制访问时的横幅：明确给出解除时间，不冒充「暂无签到」。 */
@Composable
internal fun CxSignRestrictionBanner(
    restriction: CxSignRestriction,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = restrictionBannerText(restriction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** 结束倒计时；解析不出时间就退回活动自带的结束时间文本。 */
@Composable
internal fun SignCountdown(endsAtMs: Long?, endTime: String, onExpire: () -> Unit = {}) {
    if (endsAtMs == null) {
        Text(
            text = "结束 ${endTime.ifBlank { "时间未知" }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var remain by remember(endsAtMs) {
        mutableLongStateOf((endsAtMs - System.currentTimeMillis()).coerceAtLeast(0))
    }
    val currentOnExpire by rememberUpdatedState(onExpire)
    LaunchedEffect(endsAtMs) {
        while (true) {
            val next = endsAtMs - System.currentTimeMillis()
            remain = next.coerceAtLeast(0)
            if (next <= 0L) {
                // 页面停留期间活动自然结束：只回调一次，写「已结束」历史。
                currentOnExpire()
                break
            }
            delay(1_000)
        }
    }
    Text(
        text = if (remain > 0L) "剩余 ${cxRemainText(remain)}" else "已结束",
        style = MaterialTheme.typography.labelSmall,
        color = if (remain in 1..5 * 60_000L) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** 非手势签到点进现有详情页：由详情页走「点击签到」的手动提交路径。 */
internal fun CxOpenSign.asInboxNotice(accountKey: String): CxNoticeEntity = CxNoticeEntity(
    id = "sign-$activeId",
    accountKey = accountKey,
    ntype = CxInboxType.CHECKIN.ntype,
    title = "签到 · $activeName",
    content = "$courseName · $modeLabel",
    timeMs = System.currentTimeMillis(),
    localRead = true,
    actionUrl = actionUrl,
)

/** 签到历史的时间格式：`MM-dd HH:mm`。 */
internal fun formatCxSignTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))