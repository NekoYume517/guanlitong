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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.FeatureCatalog
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.ServiceFeature
import com.sdmu.guanlitong.data.targetServiceId
import com.sdmu.guanlitong.ui.components.ServiceBadge
import com.sdmu.guanlitong.ui.components.StatusText

/**
 * 功能页：左侧是纯文字的服务二级栏，右侧是该服务的功能胶囊（两列）。
 *
 * 顶部搜索会跨所有服务检索功能；不搜索时只看当前服务。
 * 功能定义都在 [FeatureCatalog]，点击后的跳转由外层处理。
 */
@Composable
fun FeaturesScreen(
    accounts: Map<String, Account>,
    onOpenFeature: (ServiceFeature) -> Unit,
    onOpenAccount: (SchoolService) -> Unit,
    modifier: Modifier = Modifier,
    dataSourceLabel: String? = null,
    statusAccount: Account? = null,
    /** 数据来源是抢课助手时，才展示抢课任务这类抢课助手专属功能。 */
    grabSource: Boolean = false,
) {
    val services = ServiceCatalog.featureServices
    var selectedId by rememberSaveable { mutableStateOf(services.first().id) }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = services.firstOrNull { it.id == selectedId } ?: services.first()
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
                onValueChange = { query = it },
                placeholder = { Text("搜索所需功能") },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
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
                        onClick = { selectedId = service.id },
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
                    onOpenFeature = onOpenFeature,
                    modifier = Modifier.weight(1f),
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
    modifier: Modifier = Modifier,
    grabSource: Boolean = false,
) {
    val features = FeatureCatalog.forService(service.id)
        .filter { grabSource || !it.grabSourceOnly }

    Column(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceBadge(service = service, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = service.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = service.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dataSourceLabel != null && service.id == "jwxt") {
                        Text(
                            text = "数据来源：$dataSourceLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = service.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (service.requiresAccount) {
                    val shown = statusAccount ?: account
                    StatusText(loggedIn = shown?.loggedIn == true)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onOpenAccount(service) }) {
                        Text(if (shown?.loggedIn == true) "账号" else "登录")
                    }
                } else {
                    Text(
                        text = if (service.externalPackage != null) "独立 App · 免登录" else "公开访问",
                        style = MaterialTheme.typography.labelMedium,
                        color = service.accent.copy(alpha = 0.9f),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
        ) {
            Text(
                text = "全部功能",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
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

@Composable
private fun SearchResultsPane(
    keyword: String,
    results: List<ServiceFeature>,
    onOpenFeature: (ServiceFeature) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
    ) {
        Text(
            text = "搜索结果",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (results.isEmpty()) "没有找到「$keyword」相关的功能" else "找到 ${results.size} 个功能",
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        features.chunked(2).forEach { rowFeatures ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowFeatures.forEach { feature ->
                    FeatureChip(
                        feature = feature,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenFeature(feature) },
                    )
                }
                if (rowFeatures.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FeatureChip(
    feature: ServiceFeature,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = ServiceCatalog.byId(feature.targetServiceId)?.accent
        ?: MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 46.dp)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
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