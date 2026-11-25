package com.sdmu.guanlitong.ui.pu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.sdmu.guanlitong.data.pu.PuActions
import com.sdmu.guanlitong.data.pu.PuCreditSummary
import com.sdmu.guanlitong.ui.sdgy.SdgyLoading
import com.sdmu.guanlitong.ui.sdgy.SdgyScaffold
import com.sdmu.guanlitong.ui.sdgy.SdgySectionTitle
import kotlin.math.max

/**
 * 学时统计。
 *
 * 上游有两个互不一致的数：`/api/credit` 返回的 `total.total`（服务端统计口径）
 * 与逐条明细求和 `rawSum`（客户端口径，实测 53.9 vs 58）。两者都展示并说明来源，
 * 不做合并、不四舍五入去掩盖差异——差异本身就是需要被用户看到的信息。
 */
@Composable
fun PuCreditScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = rememberPuCredStore()

    var summary by remember { mutableStateOf<PuCreditSummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var authLost by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey, store.loggedIn) {
        if (!store.loggedIn) return@LaunchedEffect
        loading = true
        error = null
        when (val r = puCall(context) { PuActions.credit() }) {
            is PuOutcome.Ok -> summary = r.value
            is PuOutcome.AuthLost -> authLost = true
            is PuOutcome.Fail -> error = r.message
        }
        loading = false
    }

    SdgyScaffold(
        title = "学时统计",
        onBack = onBack,
        modifier = modifier,
        actions = {
            if (store.loggedIn && !authLost) {
                IconButton(onClick = { refreshKey++ }, enabled = !loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
            }
        },
    ) { padding ->
        when {
            !store.loggedIn -> PuLoginPrompt(onBack, Modifier.fillMaxSize().padding(padding))
            authLost -> PuErrorState(PU_AUTH_LOST, null, Modifier.fillMaxSize().padding(padding))
            loading && summary == null -> SdgyLoading("正在统计学时…", Modifier.fillMaxSize().padding(padding))
            error != null && summary == null -> PuErrorState(
                error.orEmpty(),
                onRetry = { refreshKey++ },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            summary != null -> {
                val s = summary!!
                val finished = s.finished.filter { it.credit > 0.0 }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { TotalCard(total = s.total.total, rawSum = s.rawSum) }

                    if (s.years.isNotEmpty()) {
                        item {
                            Card(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    SdgySectionTitle("按年聚合")
                                    Spacer(Modifier.height(10.dp))
                                    YearBars(s.years.map { Triple(it.year, it.count, it.sum) })
                                }
                            }
                        }
                    }

                    item {
                        Column {
                            SdgySectionTitle("学时明细（${finished.size} 项）")
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    "逐条相加 = ${creditText(s.rawSum)} 学时",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (finished.isEmpty()) {
                        item {
                            PuEmptyState("还没有拿到学时的活动", Modifier.fillMaxWidth().height(160.dp))
                        }
                    } else {
                        items(finished, key = { it.id }) { activity ->
                            PuActivityCard(activity = activity)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalCard(total: Double, rawSum: Double) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "累计学时",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    creditText(total),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    " 学时",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(
                        "服务端统计：${creditText(total)} 学时",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "明细求和：${creditText(rawSum)} 学时",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "两者来源不同（服务端汇总 vs 逐条明细），差额以服务端为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

/** 年度条：宽度按当年学时占最大年的比例。 */
@Composable
private fun YearBars(years: List<Triple<String, Int, Double>>) {
    val maxSum = max(years.maxOfOrNull { it.third } ?: 0.0, 0.01)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        years.forEach { (year, count, sum) ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        year,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(52.dp),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(10.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(5.dp),
                            ),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((sum / maxSum).toFloat().coerceIn(0.03f, 1f))
                                .height(10.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    RoundedCornerShape(5.dp),
                                ),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${creditText(sum)} 学时 · $count 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}