package com.sdmu.guanlitong.ui.pu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuActivity
import com.sdmu.guanlitong.data.pu.PuSignStatus
import com.sdmu.guanlitong.ui.components.rememberRemoteIcon
import com.sdmu.guanlitong.ui.sdgy.SdgyLoading
import com.sdmu.guanlitong.ui.sdgy.hasPermission
import com.sdmu.guanlitong.ui.sdgy.rememberPermissionRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 活动详情：封面与摘要 + 活动信息 + 签到状态 + 签到地址 + 报名/签到操作 + 生成签到码。
 *
 * 报名与签到的时间窗判定以服务端为准：按钮不做本地启用/禁用，
 * 被拒时直接把 `PuException.message`（§4.4 的逐字文案）显示在操作区上方。
 * 签到/签退地址优先用输入框，其次活动 `signPlace → address`，最后退回「定位签到」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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

    Scaffold(
        modifier = modifier,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("活动详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh++ }, enabled = !loading && !busy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
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
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    a.logoUrl?.let { url ->
                        item { CoverImage(url, a.name) }
                    }

                    item { ActivitySummary(a, status) }

                    item { SectionTitle("活动信息") }
                    item { ActivityInfoSurface(a, status) }

                    item { SectionTitle("签到状态") }
                    item { SignStatusSurface(status) }

                    item {
                        SignAddressSurface(
                            address = address,
                            fallback = signAddress(),
                            locating = locating,
                            onAddressChange = { address = it },
                            onLocate = { locate() },
                        )
                    }

                    if (actionMessage != null) {
                        item { ActionMessageCard(actionMessage.orEmpty()) { actionMessage = null } }
                    }

                    item {
                        ActionSection(
                            busy = busy,
                            onJoin = { runAction("报名成功") { PuActions.join(activityId) } },
                            onCancel = { runAction("已取消报名") { PuActions.cancel(activityId) } },
                            onSignIn = {
                                runAction("签到成功") { PuActions.signIn(activityId, signAddress()) }
                            },
                            onSignOut = {
                                runAction("签退成功") { PuActions.signOut(activityId, signAddress()) }
                            },
                        )
                    }

                    item { SignCodeCard(onOpenSignCode = { onOpenSignCode(activityId) }) }

                    item { SectionTitle("活动详情") }
                    item {
                        Text(
                            a.descriptionText ?: "暂无更多活动说明。",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    val attaches = a.attaches
                    if (attaches.isNotEmpty()) {
                        item { SectionTitle("附件") }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                attaches.forEach { (name, url) ->
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Outlined.AttachFile, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
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
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLargeEmphasized)
}

@Composable
private fun CoverImage(url: String, name: String) {
    val bitmap = rememberRemoteIcon(url)
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.extraLarge),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun ActivitySummary(activity: PuActivity, status: PuSignStatus?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            activity.statusChipText?.let { PuStatusChip(it) }
            activity.categoryNameText?.let { PuTagChip(it) }
            if (activity.credit > 0.0) PuCreditBadge(activity.credit)
        }
        Text(
            activity.name.ifBlank { "未命名活动" },
            style = MaterialTheme.typography.headlineSmallEmphasized,
        )
        activity.creatorNameText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.isSignIn) PuStatusChip("已签到")
                if (status.isSignOut) PuStatusChip("已签退")
            }
        }
    }
}

@Composable
private fun PuTagChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ActivityInfoSurface(activity: PuActivity, status: PuSignStatus?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DetailRow("报名时间", timeRange(activity.joinStartTime, activity.joinEndTime))
            DetailRow("活动时间", timeRange(activity.startTime, activity.endTime))
            DetailRow("活动地点", activity.address.takeIf { it.isNotBlank() } ?: "待公布")
            activity.joinUserText?.let { DetailRow("已报名", it) }
            if (activity.credit > 0.0) DetailRow("学时", "${creditText(activity.credit)} 学时")
            activity.rawString("signType")?.let { DetailRow("签到方式", it) }
            DetailRow("签到开始", shortTime(activity.signStartTime).ifBlank { "待公布" })
            if (activity.signOutStartTime.isNotBlank()) {
                DetailRow("签退开始", shortTime(activity.signOutStartTime))
            }
            if (activity.canSignOutTime > 0) {
                DetailRow("签退时限", "${activity.canSignOutTime} 分钟")
            }
            activity.rawInt("signInUserCount")?.let { DetailRow("已签到", "$it 人") }
            activity.rawString("allowNames")?.let { DetailRow("报名范围", it) }
            activity.rawString("contact")?.let { DetailRow("联系人", it) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SignStatusSurface(status: PuSignStatus?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status == null) {
                Text(
                    "暂时读不到签到状态，可刷新重试。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DetailRow(
                    "签到",
                    if (status.isSignIn) "已签到 ${shortTime(status.signInTime)}" else "未签到",
                )
                DetailRow(
                    "签退",
                    if (status.isSignOut) "已签退 ${shortTime(status.signOutTime)}" else "未签退",
                )
                status.signInAddress.takeIf { it.isNotBlank() }?.let {
                    DetailRow("签到地点", it)
                }
            }
        }
    }
}

@Composable
private fun SignAddressSurface(
    address: String,
    fallback: String,
    locating: Boolean,
    onAddressChange: (String) -> Unit,
    onLocate: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("签到地址", style = MaterialTheme.typography.titleMediumEmphasized)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(fallback) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                trailingIcon = {
                    IconButton(onClick = onLocate, enabled = !locating) {
                        if (locating) {
                            CircularProgressIndicator(
                                Modifier.width(18.dp).height(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.MyLocation, contentDescription = "定位")
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

@Composable
private fun ActionMessageCard(text: String, onDismiss: () -> Unit) {
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
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun ActionSection(
    busy: Boolean,
    onJoin: () -> Unit,
    onCancel: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onJoin, enabled = !busy) { Text("报名") }
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("取消报名") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onSignIn, enabled = !busy) { Text("签到") }
            OutlinedButton(onClick = onSignOut, enabled = !busy) { Text("签退") }
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

@Composable
private fun SignCodeCard(onOpenSignCode: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("生成签到码", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "主办方用这个活动的动态二维码让同学扫码签到。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onOpenSignCode) { Text("打开签到码") }
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