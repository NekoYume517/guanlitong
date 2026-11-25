package com.sdmu.guanlitong.ui.pu

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.AccountStore
import com.sdmu.guanlitong.data.pu.PuActivity
import com.sdmu.guanlitong.data.pu.PuCredStore
import com.sdmu.guanlitong.data.pu.PuException
import com.sdmu.guanlitong.data.pu.puMessage
import com.sdmu.guanlitong.ui.components.rememberRemoteIcon
import com.sdmu.guanlitong.ui.sdgy.SdgyMessage
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** 未登录时各 PU 页面统一展示的引导文案。 */
internal const val PU_LOGIN_HINT = "请先到「我的 → 第二课堂」登录"

/** 服务端返回 body.code == 401 时统一提示。 */
internal const val PU_AUTH_LOST = "登录已失效，请重新登录"

/** 批量报名一次最多勾选的活动数（§6.4）。 */
internal const val PU_BATCH_MAX = 50

/** 活动签到码二维码的 UTF-8 字节上限（§6.4）。 */
internal const val PU_QR_MAX_BYTES = 120

/**
 * 页面异步调用结果。把 401 / 业务错误 / 成功三种情况收敛成一个类型，
 * 让每个页面只写一次 `when`，不会漏掉清凭据或错误文案。
 */
internal sealed interface PuOutcome<out T> {
    data class Ok<T>(val value: T) : PuOutcome<T>
    data object AuthLost : PuOutcome<Nothing>

    /**
     * [message] 是给用户看的统一文案（`puMessage()`），
     * [detail] 保留 `PuException.message` 原文，供详情页展示报名/签到被拒的具体原因（§4.4）。
     */
    data class Fail(val message: String, val detail: String? = null) : PuOutcome<Nothing>
}

/**
 * 统一的 PU 调用包装：
 * - `CancellationException` 原样抛出（页面离开时要能取消）；
 * - `PuException(code == 401)` → 清本地凭据 + 退出账号，返回 [PuOutcome.AuthLost]；
 * - 其余异常 → 用阶段 A 的 `puMessage()` 转成用户能看的文案。
 */
internal suspend fun <T> puCall(context: Context, block: suspend () -> T): PuOutcome<T> = try {
    PuOutcome.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: PuException) {
    if (e.code == 401) {
        val app = context.applicationContext
        runCatching { PuCredStore(app).clear() }
        runCatching { AccountStore(app).signOut("pu") }
        PuOutcome.AuthLost
    } else {
        PuOutcome.Fail(e.puMessage(), e.message?.takeIf { it.isNotBlank() })
    }
} catch (e: Throwable) {
    PuOutcome.Fail(e.puMessage())
}

@Composable
internal fun rememberPuCredStore(): PuCredStore {
    val app = LocalContext.current.applicationContext
    return remember(app) { PuCredStore(app) }
}

/** 未登录门：不发任何网络请求，只给一句引导和一个返回按钮（硬性要求 4）。 */
@Composable
internal fun PuLoginPrompt(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                PU_LOGIN_HINT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
    }
}

/** 错误态：文案来自 `puMessage()`，带重试。 */
@Composable
internal fun PuErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    SdgyMessage(message, modifier, onRetry)
}

/** 空态。 */
@Composable
internal fun PuEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                action()
            }
        }
    }
}

internal fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** 服务端时间统一是 `yyyy-MM-dd HH:mm:ss`，列表里只留到分钟。 */
internal fun shortTime(raw: String?): String = raw.orEmpty().trim().let {
    if (it.length >= 16) it.substring(0, 16) else it
}

internal fun timeRange(start: String?, end: String?): String {
    val s = shortTime(start)
    val e = shortTime(end)
    return when {
        s.isBlank() && e.isBlank() -> "时间待定"
        e.isBlank() -> s
        s.isBlank() -> "至 $e"
        else -> "$s → $e"
    }
}

/** 列表卡片用的一行时间：同年省年份，同一天只写一个日期。 */
internal fun compactTimeRange(start: String?, end: String?): String {
    val s = shortTime(start)
    val e = shortTime(end)
    if (s.isBlank()) return timeRange(start, end)
    if (e.isBlank()) return s
    val sDate = s.substringBefore(' ')
    val eDate = e.substringBefore(' ')
    val sTime = s.substringAfter(' ', "")
    val eTime = e.substringAfter(' ', "")
    if (sDate != eDate) return "$s → $e"
    val day = sDate.substringAfter('-', sDate)
    return if (sTime.isBlank() || eTime.isBlank()) day else "$day $sTime → $eTime"
}

/** 学时显示：整数不带小数点，非整数保留一位。 */
internal fun creditText(value: Double): String = when {
    value == 0.0 -> "0"
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> String.format(Locale.US, "%.1f", value)
}

/** 活动卡片：列表 / 我的活动 / 学时明细 / 生成签到码选活动共用。 */
@Composable
internal fun PuActivityCard(
    activity: PuActivity,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { PuActivityLogo(activity, 56.dp) },
            headlineContent = {
                Text(
                    activity.name.ifBlank { "未命名活动" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val place = activity.signPlace.ifBlank { activity.address }
                    if (place.isNotBlank()) PuActivityMetaRow(Icons.Outlined.Place, place)
                    PuActivityMetaRow(
                        Icons.Outlined.DateRange,
                        compactTimeRange(activity.startTime, activity.endTime),
                    )
                    activity.joinUserText?.let { PuActivityMetaRow(Icons.Outlined.Group, it) }
                }
            },
            trailingContent = {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (activity.credit > 0.0) PuCreditBadge(activity.credit)
                    if (trailing != null) {
                        trailing()
                    } else {
                        activity.statusChipText?.let { PuStatusChip(it) }
                    }
                }
            },
        )
    }
}

@Composable
private fun PuActivityMetaRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 活动封面：有图用图，没图或加载失败时退回落款图标。 */
@Composable
internal fun PuActivityLogo(activity: PuActivity, size: Dp) {
    val bitmap = activity.logoUrl?.let { rememberRemoteIcon(it) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
internal fun PuCreditBadge(credit: Double) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            "${creditText(credit)} 学时",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun PuStatusChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

/*
 * 列表 / 详情的附加字段：PuActivity 只冻结了固定字段，上游多出来的
 * 封面、报名人数、状态、简介等按需从 raw 里读，读不到就不展示。
 */

internal fun PuActivity.rawString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    when (val value = raw.opt(key)) {
        null, JSONObject.NULL -> null
        is String -> value.trim().takeIf { it.isNotBlank() }
        else -> value.toString().trim().takeIf { it.isNotBlank() }
    }
}

internal fun PuActivity.rawInt(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    when (val value = raw.opt(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}

internal val PuActivity.logoUrl: String?
    get() = rawString("logo", "activityLogo", "logoUrl", "icon", "cover")

internal val PuActivity.statusChipText: String?
    get() = rawString("joinStatusName", "statusName", "status")
        ?: raw.optJSONObject("joinStatus")?.let { join ->
            listOf("name", "cancelName", "ruleName")
                .mapNotNull { join.optString(it).trim().takeIf { s -> s.isNotBlank() } }
                .distinct()
                .joinToString(" · ")
                .takeIf { it.isNotBlank() }
        }

internal val PuActivity.categoryNameText: String?
    get() = rawString("categoryName", "category", "typeName")

internal val PuActivity.creatorNameText: String?
    get() = rawString("creatorName", "publisher", "organizer")

internal val PuActivity.descriptionText: String?
    get() = rawString(
        "description", "activityDescription", "intro", "content",
        "details", "activityContent",
    )

internal val PuActivity.joinUserText: String?
    get() {
        val joined = rawInt("joinUserCount", "joinedUserCount")
        val allow = rawInt("allowUserCount", "maxUserCount")
        return when {
            joined == null && allow == null -> null
            joined == null -> "限额 $allow 人"
            allow == null || allow <= 0 -> "$joined 人已报名"
            else -> "$joined/$allow 人"
        }
    }

internal val PuActivity.attaches: List<Pair<String, String>>
    get() {
        val array = raw.optJSONArray("attaches") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url").trim().ifBlank {
                    item.optString("path").trim().ifBlank { item.optString("fileUrl").trim() }
                }
                if (url.isBlank()) continue
                val name = item.optString("name").trim().ifBlank {
                    item.optString("fileName").trim().ifBlank { "附件 ${index + 1}" }
                }
                add(name to url)
            }
        }
    }

/** 列表底部的「加载更多」与终止提示。 */
@Composable
internal fun PuListFooter(
    loadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    emptyHint: String = "没有更多了",
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loadingMore -> Text(
                "正在加载…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            !hasMore -> Text(
                emptyHint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> TextButton(onClick = onLoadMore) { Text("加载更多") }
        }
    }
}