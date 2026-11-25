package com.sdmu.guanlitong.ui.sdgy

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdmu.guanlitong.data.sdgy.SdgyPowerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val POWER_PREFS = "sdgy_power"

private data class PowerSelection(
    val building: SdgyPowerApi.Building? = null,
    val floor: Int = -1,
    val room: SdgyPowerApi.Room? = null,
)

/**
 * 宿舍用电：选楼栋 → 楼层 → 房间，查剩余电量、表状态和近 14 天用量。
 * 查询接口不需要登录，学校服务器夜间关机时保留上次选择，恢复后刷新即可。
 */
@Composable
fun SdgyPowerScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(POWER_PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var buildings by remember { mutableStateOf<List<SdgyPowerApi.Building>>(emptyList()) }
    var selection by remember {
        mutableStateOf(
            prefs.getString("b_id", null)?.let { id ->
                PowerSelection(
                    building = SdgyPowerApi.Building(
                        id = id,
                        name = prefs.getString("b_name", "").orEmpty(),
                        floorFrom = prefs.getInt("b_from", 1),
                        floorTo = prefs.getInt("b_to", 5),
                    ),
                    floor = prefs.getInt("floor", -1),
                    room = prefs.getString("r_id", null)?.let { rid ->
                        SdgyPowerApi.Room(rid, prefs.getString("r_name", "").orEmpty())
                    },
                )
            } ?: PowerSelection()
        )
    }
    var rooms by remember { mutableStateOf<List<SdgyPowerApi.Room>>(emptyList()) }
    var meter by remember { mutableStateOf<SdgyPowerApi.Meter?>(null) }
    var usage by remember { mutableStateOf<SdgyPowerApi.Usage?>(null) }
    var daily by remember { mutableStateOf<List<SdgyPowerApi.DayUsage>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var pickerStep by remember { mutableStateOf<String?>(null) }

    fun save(sel: PowerSelection) {
        prefs.edit()
            .putString("b_id", sel.building?.id)
            .putString("b_name", sel.building?.name)
            .putInt("b_from", sel.building?.floorFrom ?: 1)
            .putInt("b_to", sel.building?.floorTo ?: 5)
            .putInt("floor", sel.floor)
            .putString("r_id", sel.room?.id)
            .putString("r_name", sel.room?.name)
            .apply()
    }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            val area = SdgyPowerApi.areas().firstOrNull() ?: return@withContext emptyList()
            SdgyPowerApi.buildings(area.id)
        }
        buildings = list
        if (list.isEmpty()) error = "楼栋列表没加载出来"
    }

    LaunchedEffect(selection.room, tick) {
        val room = selection.room ?: return@LaunchedEffect
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            val meters = SdgyPowerApi.meters(room.id)
            val m = meters.firstOrNull { it.type.contains("电") } ?: meters.firstOrNull()
            val u = m?.let { SdgyPowerApi.usage(it.id) }
            val d = m?.let { SdgyPowerApi.dailyUsage(it.id, 14) }.orEmpty()
            Triple(m, u, d)
        }
        meter = result.first
        usage = result.second
        daily = result.third
        when {
            result.first == null -> error = "没有找到这个房间的电表"
            result.second == null -> error = "用电数据没加载出来"
        }
        loading = false
    }

    SdgyScaffold(
        title = "宿舍用电",
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = { tick++ }, enabled = !loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "宿舍",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (selection.building == null) "未选择"
                            else "${selection.building!!.name}" +
                                (if (selection.floor > 0) " ${selection.floor}层" else "") +
                                (selection.room?.let { " · ${it.name}室" } ?: ""),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(onClick = { pickerStep = "building" }) {
                        Text(if (selection.building == null) "选择" else "更换")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                selection.room == null -> Box(
                    Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            error ?: "请先选择宿舍房间",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.Button(
                            onClick = { pickerStep = "building" },
                        ) { Text("选择楼栋") }
                    }
                }

                loading -> Box(
                    Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "正在查询用电…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                error != null -> Box(
                    Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { tick++ }) { Text("重试") }
                    }
                }

                usage != null -> {
                    val u = usage!!
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                "剩余${if (u.unit == "元") "金额" else "电量"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    u.remain.ifBlank { "--" },
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    " ${u.unit}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "电表读数 ${u.total} · 抄表时间 ${u.readTime.ifBlank { "--" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        PowerInfoCard("电表状态", u.state.ifBlank { "--" }, Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        PowerInfoCard("供电开关", u.valve.ifBlank { "--" }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        PowerInfoCard("电表类型", meter?.name?.ifBlank { "--" } ?: "--", Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        PowerInfoCard("表号", meter?.id ?: "--", Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(18.dp))
                    if (daily.isNotEmpty()) {
                        Text("近 14 天用量", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                        DailyBars(daily)
                        Spacer(Modifier.height(8.dp))
                        val today = daily.last()
                        Text(
                            "今日 ${today.value} ${today.unit}（约 ${today.money} 元）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // ---- 楼栋 → 楼层 → 房间 选择 ----
    when (pickerStep) {
        "building" -> PickerDialog(
            title = "选择楼栋",
            options = buildings.map { it.id to it.name },
            onClose = { pickerStep = null },
            onPick = { id, _ ->
                val b = buildings.firstOrNull { it.id == id }
                if (b != null) {
                    selection = PowerSelection(building = b)
                    save(selection)
                    pickerStep = "floor"
                }
            },
        )

        "floor" -> {
            val b = selection.building
            if (b == null) pickerStep = null else PickerDialog(
                title = "选择楼层 · ${b.name}",
                options = (b.floorFrom..b.floorTo).map { it.toString() to "${it}层" },
                onClose = { pickerStep = null },
                onPick = { v, _ ->
                    val floor = v.toIntOrNull() ?: return@PickerDialog
                    selection = selection.copy(floor = floor, room = null)
                    save(selection)
                    scope.launch {
                        rooms = withContext(Dispatchers.IO) {
                            SdgyPowerApi.rooms(b.id, floor)
                        }
                        if (rooms.isEmpty()) {
                            error = "这个楼层没有房间"
                            pickerStep = null
                        } else {
                            pickerStep = "room"
                        }
                    }
                },
            )
        }

        "room" -> PickerDialog(
            title = "选择房间 · ${selection.floor}层",
            options = rooms.map { it.id to "${it.name}室" },
            onClose = { pickerStep = null },
            onPick = { id, _ ->
                val room = rooms.firstOrNull { it.id == id } ?: return@PickerDialog
                selection = selection.copy(room = room)
                save(selection)
                pickerStep = null
            },
        )

        else -> Unit
    }
}

@Composable
private fun PowerInfoCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    onClose: () -> Unit,
    onPick: (String, String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            if (options.isEmpty()) {
                Text("暂无可选项", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(
                    Modifier.height((options.size.coerceAtMost(7) * 48).dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEach { (id, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(id, name) }
                                .padding(vertical = 12.dp),
                        ) { Text(name, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("取消") }
        },
    )
}

@Composable
private fun DailyBars(days: List<SdgyPowerApi.DayUsage>) {
    val values = days.map { it.value.toFloatOrNull() ?: 0f }
    val maxValue = max(values.maxOrNull() ?: 0f, 0.01f)
    Row(
        Modifier.fillMaxWidth().height(150.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEachIndexed { index, day ->
            val ratio = (values.getOrNull(index) ?: 0f) / maxValue
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .height((ratio * 110).dp.coerceAtLeast(2.dp))
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    day.date.takeLast(5),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}