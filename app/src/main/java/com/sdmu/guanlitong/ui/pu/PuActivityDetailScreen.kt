package com.sdmu.guanlitong.ui.pu

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuActivity
import com.sdmu.guanlitong.data.pu.PuSignStatus
import com.sdmu.guanlitong.ui.sdgy.SdgyKeyValueRow
import com.sdmu.guanlitong.ui.sdgy.SdgyLoading
import com.sdmu.guanlitong.ui.sdgy.SdgyScaffold
import com.sdmu.guanlitong.ui.sdgy.SdgySectionTitle
import com.sdmu.guanlitong.ui.sdgy.hasPermission
import com.sdmu.guanlitong.ui.sdgy.rememberPermissionRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 活动详情：基本信息 + 签到状态 + 报名/取消/签到/签退 + 生成签到码入口。
 *
 * 报名与签到的时间窗判定以服务端为准：按钮不做本地启用/禁用，
 * 被拒时直接把 `PuException.message`（§4.4 的逐字文案）显示在操作区上方。
 * 签到/签退地址优先用输入框，其次活动 `signPlace → address`，最后退回「定位签到」。
 */
@Composable
fun PuActivityDetailScreen(
    activityId: Long,
    onBack: () -> Unit,
    onOpenSignCode: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = rememberPuCredStore()
    val scope = rememberCoroutineScope()

    var activity by remember { mutableStateOf<PuActivity?>(null) }
    var status by remember { mutableStateOf<PuSignStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var authLost by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    var busy by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }

    LaunchedEffect(refresh, store.loggedIn) {
        if (!store.loggedIn) return@LaunchedEffect
        if (activity == null) loading = true
        error = null
        when (val detail = puCall(context) { PuActions.activityDetail(activityId) }) {
            is PuOutcome.Ok -> activity = detail.value
            is PuOutcome.AuthLost -> authLost = true
            is PuOutcome.Fail -> error = detail.message
        }
        // 签到状态是增强信息，取不到不影响详情展示。
        when (val s = puCall(context) { PuActions.signStatus(activityId) }) {
            is PuOutcome.Ok -> status = s.value
            is PuOutcome.AuthLost -> authLost = true
            is PuOutcome.Fail -> Unit
        }
        loading = false
    }

    fun signAddress(): String = address.trim().ifBlank {
        activity?.signPlace?.takeIf { it.isNotBlank() }
            ?: activity?.address?.takeIf { it.isNotBlank() }
            ?: "定位签到"
    }

    fun runAction(successText: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            actionMessage = null
            when (val r = puCall(context) { block() }) {
                is PuOutcome.Ok -> {
                    toast(context, successText)
                    refresh++
                }
                is PuOutcome.AuthLost -> authLost = true
                is PuOutcome.Fail -> actionMessage = r.detail ?: r.message
            }
            busy = false
        }
    }

    val permissionRequester = rememberPermissionRequester { granted ->
        if (granted) {
            scope.launch { address = locateAddress(context) ?: run { toast(context, "暂时取不到位置，可手动填写地址"); address } }
        } else {
            toast(context, "没有定位权限，可手动填写地址")
        }
    }

    fun locate() {
        val hasPermission = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasPermission) {
            permissionRequester(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        scope.launch {
            locating = true
            val found = locateAddress(context)
            if (found != null) address = found else toast(context, "暂时取不到位置，可手动填写地址")
            locating = false
        }
    }

    SdgyScaffold(
        title = "活动详情",
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = { refresh++ }, enabled = !loading && !busy) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
        },
    ) { padding ->
        when {
            !store.loggedIn -> PuLoginPrompt(onBack, Modifier.fillMaxSize().padding(padding))
            authLost -> PuErrorState(PU_AUTH_LOST, null, Modifier.fillMaxSize().padding(padding))
            loading -> SdgyLoading("正在打开活动…", Modifier.fillMaxSize().padding(padding))
            error != null && activity == null -> PuErrorState(
                error.orEmpty(),
                onRetry = { refresh++ },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            activity != null -> {
                val a = activity!!
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { HeaderCard(a) }

                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                SdgySectionTitle("时间安排")
                                Spacer(Modifier.height(6.dp))
                                SdgyKeyValueRow("报名", timeRange(a.joinStartTime, a.joinEndTime))
                                SdgyKeyValueRow("活动", timeRange(a.startTime, a.endTime))
                                SdgyKeyValueRow("签到开始", shortTime(a.signStartTime).ifBlank { "—" })
                                SdgyKeyValueRow("签退开始", shortTime(a.signOutStartTime).ifBlank { "—" })
                                if (a.canSignOutTime > 0) {
                                    SdgyKeyValueRow("签退时限", "${a.canSignOutTime} 分钟")
                                }
                            }
                        }
                    }

                    item { SignStatusCard(status) }

                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                SdgySectionTitle("签到地址")
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = address,
                                    onValueChange = { address = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(signAddress()) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    trailingIcon = {
                                        IconButton(onClick = { locate() }, enabled = !locating) {
                                            if (locating) {
                                                CircularProgressIndicator(
                                                    Modifier.width(18.dp).height(18.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Outlined.MyLocation,
                                                    contentDescription = "定位",
                                                )
                                            }
                                        }
                                    },
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "留空时使用活动签到地点；已签到/已签退的记录不会覆盖。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (actionMessage != null) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        actionMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { actionMessage = null }) { Text("知道了") }
                                }
                            }
                        }
                    }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "报名",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(
                                    onClick = { runAction("报名成功") { PuActions.join(activityId) } },
                                    enabled = !busy,
                                ) { Text("报名") }
                                OutlinedButton(
                                    onClick = { runAction("已取消报名") { PuActions.cancel(activityId) } },
                                    enabled = !busy,
                                ) { Text("取消报名") }
                            }

                            Text(
                                "签到",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        runAction("签到成功") {
                                            PuActions.signIn(activityId, signAddress())
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("签到") }
                                OutlinedButton(
                                    onClick = {
                                        runAction("签退成功") {
                                            PuActions.signOut(activityId, signAddress())
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("签退") }
                            }
                            if (busy) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        Modifier.width(16.dp).height(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "正在提交…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "生成签到码",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Text(
                                        "主办方用这个活动的动态二维码让同学扫码签到。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                FilledTonalButton(onClick = { onOpenSignCode(activityId) }) {
                                    Text("打开")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(activity: PuActivity) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                activity.name.ifBlank { "未命名活动" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                timeRange(activity.startTime, activity.endTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
            val place = activity.signPlace.ifBlank { activity.address }
            if (place.isNotBlank()) {
                Text(
                    place,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
            if (activity.credit > 0.0) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "${creditText(activity.credit)} 学时",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignStatusCard(status: PuSignStatus?) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            SdgySectionTitle("签到状态")
            Spacer(Modifier.height(6.dp))
            if (status == null) {
                Text(
                    "暂时读不到签到状态，可下拉刷新重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SdgyKeyValueRow(
                    "签到",
                    if (status.isSignIn) "已签到 ${shortTime(status.signInTime)}" else "未签到",
                )
                if (status.isSignIn && status.signInAddress.isNotBlank()) {
                    SdgyKeyValueRow("签到地点", status.signInAddress)
                }
                SdgyKeyValueRow(
                    "签退",
                    if (status.isSignOut) "已签退 ${shortTime(status.signOutTime)}" else "未签退",
                )
            }
        }
    }
}

/** 取最后已知位置并反查地址；权限、定位、地理编码任一失败都返回 null。 */
private suspend fun locateAddress(context: Context): String? = withContext(Dispatchers.IO) {
    val permitted = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
        context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (!permitted) return@withContext null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return@withContext null
    val location = runCatching {
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }.getOrNull() ?: return@withContext null
    runCatching {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.CHINA)
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?.let { address ->
                listOfNotNull(
                    address.locality,
                    address.subLocality,
                    address.thoroughfare,
                    address.featureName,
                ).distinct().joinToString("")
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}