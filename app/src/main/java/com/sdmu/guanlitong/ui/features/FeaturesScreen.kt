package com.sdmu.guanlitong.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.FeatureAction
import com.sdmu.guanlitong.data.FeatureCatalog
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.data.SdgyPage
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.ServiceFeature
import com.sdmu.guanlitong.data.targetServiceId
import com.sdmu.guanlitong.ui.components.FlowChipRow
import com.sdmu.guanlitong.ui.sdgy.SdgyAppsContent

/**
 * 功能页：左侧是纯文字的服务二级栏，右侧是该服务的功能胶囊（两列）。
 *
 * 顶部搜索会跨所有服务检索功能；不搜索时只看当前服务。
 * 功能定义都在 [FeatureCatalog]，点击后的跳转由外层处理。
 */
@Composable
fun FeaturesScreen(
    accounts: Map<String, Account>,
    selectedServiceId: String,
    onSelectService: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenAccount: (SchoolService) -> Unit,
    onOpenSdgyNative: (SdgyPage) -> Unit = { _ -> },
    onOpenSdgyWeb: (title: String, appId: String, url: String, lx: String) -> Unit = { _, _, _, _ -> },
    /** 由 AppRoot 保存，覆盖页退出后仍保留各服务右侧面板的滚动位置。 */
    scrollStates: MutableMap<String, ScrollState>,
    searchScrollState: ScrollState,
    sdgyListState: LazyListState,
    modifier: Modifier = Modifier,
    dataSourceLabel: String? = null,
    statusAccount: Account? = null,
    /** 数据来源是抢课助手时，才展示抢课任务这类抢课助手专属功能。 */
    grabSource: Boolean = false,
) {
    val services = ServiceCatalog.featureServices
    val selected = services.firstOrNull { it.id == selectedServiceId } ?: services.first()
    val account = accounts[selected.id]

    val keyword = query.trim()
    val searching = keyword.isNotEmpty()
    val results = remember(keyword, grabSource) {
        if (keyword.isEmpty()) {
            emptyList()
        } else {
            FeatureCatalog.features.filter { feature ->
                (grabSource || !feature.grabSourceOnly) &&
                    (
                        feature.title.contains(keyword, ignoreCase = true) ||
                            feature.subtitle.contains(keyword, ignoreCase = true) ||
                            ServiceCatalog.byId(feature.serviceId)
                                ?.name?.contains(keyword, ignoreCase = true) == true
                        )
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            Text(
                text = "功能",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.padding(top = 10.dp))
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("搜索功能") },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.padding(top = 12.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .width(124.dp)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(services.size, key = { services[it].id }) { index ->
                    val service = services[index]
                    ServiceRailTab(
                        service = service,
                        selected = service.id == selected.id,
                        onClick = { onSelectService(service.id) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            )

            if (searching) {
                SearchResultsPane(
                    keyword = keyword,
                    results = results,
                    onOpenFeature = { feature ->
                        if (feature.serviceId == "other") {
                            val page = (feature.action as? FeatureAction.Sdgy)?.page ?: return@SearchResultsPane
                            onOpenSdgyNative(page)
                        } else {
                            onOpenFeature(feature)
                        }
                    },
                    scrollState = searchScrollState,
                    modifier = Modifier.weight(1f),
                )
            } else if (selected.id == "sdgy") {
                SdgyAppsContent(
                    onOpenNative = onOpenSdgyNative,
                    onOpenWeb = onOpenSdgyWeb,
                    onOpenAccount = { onOpenAccount(selected) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    listState = sdgyListState,
                    showAccountCard = false,
                    showSearch = false,
                    externalQuery = query,
                    flowChips = true,
                )
            } else {
                ServicePane(
                    service = selected,
                    account = account,
                    dataSourceLabel = dataSourceLabel,
                    statusAccount = statusAccount,
                    grabSource = grabSource,
                    onOpenFeature = onOpenFeature,
                    onOpenAccount = onOpenAccount,
                    onOpenSdgyNative = onOpenSdgyNative,
                    onOpenSdgyWeb = onOpenSdgyWeb,
                    scrollStates = scrollStates,
                    sdgyListState = sdgyListState,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ServiceRailTab(
    service: SchoolService,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = service.name,
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

@Composable
private fun ServicePane(
    service: SchoolService,
    account: Account?,
    dataSourceLabel: String? = null,
    statusAccount: Account? = null,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenAccount: (SchoolService) -> Unit,
    onOpenSdgyNative: (SdgyPage) -> Unit = { _ -> },
    onOpenSdgyWeb: (title: String, appId: String, url: String, lx: String) -> Unit = { _, _, _, _ -> },
    scrollStates: MutableMap<String, ScrollState>,
    sdgyListState: LazyListState,
    modifier: Modifier = Modifier,
    grabSource: Boolean = false,
) {
    val features = FeatureCatalog.forService(service.id)
        .filter { grabSource || !it.grabSourceOnly }
    val scrollState = scrollStates.getOrPut(service.id) { ScrollState(0) }

    Column(modifier = modifier.fillMaxHeight()) {
        if (service.id == "sdgy") {
            // 山管app 不再单开二级页：官方功能总表直接铺在右侧面板里。
            SdgyAppsContent(
                onOpenNative = onOpenSdgyNative,
                onOpenWeb = onOpenSdgyWeb,
                onOpenAccount = { onOpenAccount(service) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                listState = sdgyListState,
                showAccountCard = false,
                showSearch = false,
                externalQuery = "",
                flowChips = true,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 120.dp),
            ) {
                if (features.isEmpty()) {
                    Text(
                        text = "这个服务还没有配置功能",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FeatureChipRows(features = features, onOpenFeature = onOpenFeature)
                }
            }
        }
    }
}

@Composable
private fun SearchResultsPane(
    keyword: String,
    results: List<ServiceFeature>,
    onOpenFeature: (ServiceFeature) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
    ) {
        Text(
            text = "搜索结果",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (results.isEmpty()) "没有找到「$keyword」" else "搜索结果",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )

        results.groupBy { it.serviceId }.forEach { (serviceId, features) ->
            val serviceName = ServiceCatalog.byId(serviceId)?.name ?: serviceId
            Text(
                text = serviceName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
            )
            FeatureChipRows(features = features, onOpenFeature = onOpenFeature)
        }
    }
}

@Composable
private fun FeatureChipRows(
    features: List<ServiceFeature>,
    onOpenFeature: (ServiceFeature) -> Unit,
) {
    FlowChipRow(modifier = Modifier.fillMaxWidth()) {
        features.forEach { feature ->
            FeatureChip(
                feature = feature,
                onClick = { onOpenFeature(feature) },
            )
        }
    }
}

@Composable
private fun FeatureChip(
    feature: ServiceFeature,
    onClick: () -> Unit,
) {
    val accent = ServiceCatalog.byId(feature.targetServiceId)?.accent
        ?: MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val officialIcon = com.sdmu.guanlitong.ui.components.rememberRemoteIcon(feature.iconUrl)
            if (officialIcon != null) {
                androidx.compose.foundation.Image(
                    bitmap = officialIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = feature.title,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}