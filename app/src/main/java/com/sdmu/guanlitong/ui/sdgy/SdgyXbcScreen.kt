package com.sdmu.guanlitong.ui.sdgy

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

private const val XBC_BASE = "https://map.bansonic.top"
private const val XBC_PREFS = "sdgy_xbc"
private val XBC_DEFAULT_POS = 36.531820 to 116.780180

private data class XbcAccount(val ownerId: Int, val label: String, val enabled: Boolean)

/**
 * 小白超人电动车地图。
 *
 * 地图网页本身与官方壳一致（UA 带 xbniu-native），登录、支付、订单都在网页内完成；
 * 原生只负责读取网页登录态、相机扫码、定位、确认页与开锁请求。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SdgyXbcScreen(
    initialCode: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val prefs = remember { appContext.getSharedPreferences(XBC_PREFS, Context.MODE_PRIVATE) }
    val main = remember { Handler(Looper.getMainLooper()) }

    var token by remember { mutableStateOf(prefs.getString("auth_token", "").orEmpty()) }
    var page by remember { mutableStateOf(if (initialCode != null) "confirm" else "map") }
    var code by remember { mutableStateOf(initialCode.orEmpty()) }
    var stateText by remember { mutableStateOf("--") }
    var elecText by remember { mutableStateOf("--") }
    var accounts by remember { mutableStateOf(listOf(XbcAccount(0, "自己的账号", true))) }
    var ownerId by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    var unlockMsg by remember { mutableStateOf("") }
    var detailLoading by remember { mutableStateOf(false) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    var scannerOpen by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    var permTick by remember { mutableIntStateOf(0) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var loadedKey by remember { mutableStateOf("") }

    val cameraOk = remember(permTick) { appContext.hasPermission(Manifest.permission.CAMERA) }
    val locationOk = remember(permTick) {
        appContext.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun persistToken(value: String) {
        token = value
        prefs.edit().putString("auth_token", value).apply()
    }

    fun readTokenNow(callback: (String) -> Unit) {
        val wv = webRef
        if (wv == null) {
            callback(token)
            return
        }
        wv.evaluateJavascript(
            "(function(){ try { return localStorage.getItem('auth_token') || ''; } catch(e){ return ''; } })()",
        ) { raw -> callback(raw.removeSurrounding("\"").trim()) }
    }

    fun openConfirm(codeText: String) {
        code = codeText
        loadedKey = ""
        page = "confirm"
    }

    fun onScanned(raw: String) {
        scannerOpen = false
        val c = bikeCodeOf(raw)
        if (c == null) {
            unlockMsg = "未识别到有效车牌（需含 code 参数）"
            return
        }
        readTokenNow { fresh ->
            if (fresh.isBlank()) {
                unlockMsg = "尚未登录，请先在地图页登录"
            } else {
                persistToken(fresh)
                openConfirm(c)
            }
        }
    }

    fun unlockNow() {
        if (token.isBlank()) {
            unlockMsg = "尚未登录，请先在地图页登录"
            return
        }
        unlockMsg = "定位中…"
        locationOnce(appContext) { lat, lng ->
            Thread {
                val result = callUnlock(token, code, lat, lng, if (ownerId > 0) ownerId else null)
                main.post {
                    unlockMsg = if (result == "OK") "开锁成功" else "开锁失败：${result ?: "网络/服务器"}"
                    if (result == "OK") {
                        main.postDelayed({ webRef?.reload() }, 800)
                    }
                    loadedKey = ""
                    refreshTick++
                }
            }.start()
        }
    }

    val cameraRequester = rememberPermissionRequester { permTick++ }
    val locationRequester = rememberPermissionRequester { granted ->
        permTick++
        if (granted) unlockNow() else unlockMsg = "未授权定位，无法开锁"
    }

    fun startScan() {
        if (!cameraOk) {
            cameraRequester(Manifest.permission.CAMERA)
            return
        }
        scannerOpen = true
    }

    // 网页登录后 5 秒内同步 token，避免用启动时的旧值。
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            if (webRef != null) readTokenNow { t -> if (t.isNotBlank()) persistToken(t) }
        }
    }

    LaunchedEffect(page, code, token, refreshTick) {
        if (page != "confirm" || code.isBlank() || token.isBlank()) return@LaunchedEffect
        val key = "$token|$code|$refreshTick"
        if (loadedKey == key) return@LaunchedEffect
        loadedKey = key
        detailLoading = true
        val result = withContext(Dispatchers.IO) {
            loadBikeDetail(token, code) to fetchAccounts(token)
        }
        stateText = result.first.first
        elecText = result.first.second
        accounts = result.second
        if (accounts.none { it.ownerId == ownerId }) ownerId = 0
        detailLoading = false
    }

    LaunchedEffect(armed) {
        if (armed) {
            delay(4_000)
            armed = false
        }
    }

    // 山管功能页里，确认页优先返回地图页。
    BackHandler(enabled = page == "confirm") { page = "map" }

    SdgyScaffold(
        title = "小白超人",
        onBack = onBack,
        modifier = modifier,
        actions = {
            if (page == "map") {
                IconButton(onClick = { webRef?.reload() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = { startScan() }) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = "扫码开锁")
                }
                TextButton(onClick = { showPermissions = true }) { Text("权限") }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    val wv = WebView(c).apply {
                        alpha = 0f
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.setGeolocationEnabled(true)
                        val defaultUa = settings.userAgentString.orEmpty()
                        if (!defaultUa.contains("xbniu-native", true)) {
                            settings.userAgentString = "$defaultUa xbniu-native/1.0"
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (view.alpha < 1f) {
                                    view.animate().alpha(1f).setDuration(320L).start()
                                }
                                readTokenNow { t -> if (t.isNotBlank()) persistToken(t) }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String?,
                                callback: GeolocationPermissions.Callback?,
                            ) {
                                callback?.invoke(origin, appContext.hasPermission(
                                    Manifest.permission.ACCESS_FINE_LOCATION), false)
                            }
                        }
                        val bridge = object {
                            @JavascriptInterface
                            fun unlock(raw: String?) {
                                main.post {
                                    val c = bikeCodeOf(raw.orEmpty()) ?: run {
                                        unlockMsg = "未识别到有效车牌"
                                        return@post
                                    }
                                    readTokenNow { t ->
                                        if (t.isBlank()) unlockMsg = "尚未登录，请先在地图页登录"
                                        else {
                                            persistToken(t)
                                            openConfirm(c)
                                        }
                                    }
                                }
                            }
                        }
                        addJavascriptInterface(bridge, "xbniuAndroid")
                        addJavascriptInterface(bridge, "xbcPack")
                        loadUrl(XBC_BASE)
                    }
                    webRef = wv
                    wv
                },
                onRelease = {
                    webRef = null
                    it.destroy()
                },
            )

            if (!cameraOk || !locationOk) {
                Card(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "相机/定位权限未授予，扫码与定位功能受限",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = { showPermissions = true }) { Text("去授权") }
                    }
                }
            }

            AnimatedVisibility(
                visible = page == "confirm",
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    ConfirmPage(
                        code = code,
                        stateText = if (detailLoading) "查询中…" else stateText,
                        elecText = if (detailLoading) "查询中…" else elecText,
                        accounts = accounts,
                        ownerId = ownerId,
                        onPickOwner = { if (it.enabled) { ownerId = it.ownerId; armed = false } },
                        armed = armed,
                        unlockMsg = unlockMsg,
                        tokenMissing = token.isBlank(),
                        onBackToMap = { page = "map" },
                        onUnlock = {
                            when {
                                token.isBlank() -> unlockMsg = "尚未登录，请先在地图页登录"
                                !armed -> { armed = true; unlockMsg = "请再次点击确认" }
                                !locationOk -> locationRequester(
                                    Manifest.permission.ACCESS_FINE_LOCATION)
                                else -> { armed = false; unlockNow() }
                            }
                        },
                    )
                }
            }
        }
    }

    if (scannerOpen) {
        SdgyScannerDialog(
            onDismiss = { scannerOpen = false },
            onDecoded = ::onScanned,
        )
    }

    if (showPermissions) {
        AlertDialog(
            onDismissRequest = { showPermissions = false },
            title = { Text("权限设置") },
            text = {
                Column {
                    PermissionRow("相机", "用于扫码识别车辆", cameraOk) {
                        if (cameraOk) return@PermissionRow
                        cameraRequester(Manifest.permission.CAMERA)
                    }
                    PermissionRow("精确位置", "用于显示附近车辆与开锁定位", locationOk) {
                        if (locationOk) return@PermissionRow
                        if (!appContext.hasPermission(
                                Manifest.permission.ACCESS_FINE_LOCATION)) {
                            locationRequester(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPermissions = false }) { Text("完成") }
            },
        )
    }
}

@Composable
private fun ConfirmPage(
    code: String,
    stateText: String,
    elecText: String,
    accounts: List<XbcAccount>,
    ownerId: Int,
    onPickOwner: (XbcAccount) -> Unit,
    armed: Boolean,
    unlockMsg: String,
    tokenMissing: Boolean,
    onBackToMap: () -> Unit,
    onUnlock: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "车牌号",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(14.dp),
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "#$code",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            letterSpacing = 0.05.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SdgyKeyValueRow("状态", stateText)
                    HorizontalDivider(
                        Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    SdgyKeyValueRow("电量", elecText)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("开锁账户", style = MaterialTheme.typography.titleMedium)
            if (accounts.size > 1) {
                Text(
                    "灰色项为当前不可用（次数用完/车主使用中等）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            accounts.forEach { account ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = ownerId == account.ownerId,
                        onClick = { onPickOwner(account) },
                        enabled = account.enabled,
                    )
                    Text(
                        account.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (account.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (unlockMsg.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    unlockMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tokenMissing) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "尚未登录小白超人，请先在地图页登录后重新扫码",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onBackToMap) { Text("返回地图") }
        }
        Button(
            onClick = onUnlock,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                if (armed) "再次点击确认开锁" else "确认开锁",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    name: String,
    desc: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (granted) {
            Text(
                "已授权",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onRequest) { Text("申请") }
        }
    }
}

// ---------- 小白超人接口 ----------

private fun loadBikeDetail(token: String, code: String): Pair<String, String> {
    for (type in listOf("3", "1", "6")) {
        queryBike(token, type, code)?.let { return it }
    }
    return try {
        val obj = postJson("$XBC_BASE/api/bicycle", token, JSONObject().put("code", code))
            ?: return "查询失败" to "--"
        val data = obj.optJSONObject("data")
        if (data != null && (obj.optInt("rtn_code") == 0 || obj.optBoolean("success"))) {
            xbcStatus(data.optInt("state"), data.optInt("status")) to
                (xbcElectric(data)?.let { "${it.roundToInt()}%" } ?: "--")
        } else {
            obj.optString("message").ifBlank { "未获取到车辆信息" } to "--"
        }
    } catch (_: Exception) {
        "查询失败" to "--"
    }
}

private fun queryBike(token: String, type: String, code: String): Pair<String, String>? = try {
    val conn = URL("$XBC_BASE/api/map?type=$type").openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 15_000
    conn.setRequestProperty("Authorization", "Bearer $token")
    val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    if (obj.optInt("rtn_code") != 0) null else {
        val arr = obj.optJSONObject("data")?.optJSONArray("bicycleStation")
        val codeLong = code.toLongOrNull()
        var hit: Pair<String, String>? = null
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val bike = arr.optJSONObject(i) ?: continue
                val c = bike.opt("code")?.toString()?.trim()
                if (c == code || (codeLong != null && c?.toLongOrNull() == codeLong)) {
                    val e = xbcElectric(bike)
                    hit = xbcStatus(bike.optInt("state", 0), bike.optInt("status", 0)) to
                        (if (e != null) "${e.roundToInt()}%" else "--")
                    break
                }
            }
        }
        hit
    }
} catch (_: Exception) {
    null
}

private fun fetchAccounts(token: String): List<XbcAccount> = try {
    val conn = URL("$XBC_BASE/api/share/sources?show_unavailable=1")
        .openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 15_000
    conn.setRequestProperty("Authorization", "Bearer $token")
    val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    val arr = obj.optJSONObject("data")?.optJSONArray("sources")
    val out = mutableListOf(XbcAccount(0, "自己的账号", true))
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val oid = s.optInt("owner_user_id", 0)
            if (oid <= 0) continue
            val name = s.optString("remark").ifBlank { s.optString("owner_name") }
                .ifBlank { s.optString("owner_email") }.ifBlank { "共享者$oid" }
            val canUse = s.optBoolean("can_use", false)
            val label = if (canUse) {
                "用 $name 的车开（今日剩 ${s.optInt("remaining_today", 0)} 次）"
            } else {
                "用 $name 的车开（${s.optJSONArray("reasons")?.optString(0) ?: "当前不可用"}）"
            }
            out.add(XbcAccount(oid, label, canUse))
        }
    }
    out
} catch (_: Exception) {
    listOf(XbcAccount(0, "自己的账号", true))
}

private fun callUnlock(
    token: String,
    code: String,
    lat: Double,
    lng: Double,
    ownerId: Int?,
): String? = try {
    val url = if (ownerId != null) "$XBC_BASE/api/order/shared_unlock"
    else "$XBC_BASE/api/order/unlock"
    val body = JSONObject().put("code", code)
        .put("latitude", lat).put("longitude", lng)
    if (ownerId != null) body.put("owner_user_id", ownerId)
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 15_000
    conn.requestMethod = "POST"
    conn.setRequestProperty("Authorization", "Bearer $token")
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    conn.doOutput = true
    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    val obj = runCatching { JSONObject(text) }.getOrNull()
    when {
        obj == null -> if (conn.responseCode in 200..299) "OK" else "HTTP ${conn.responseCode}"
        obj.optInt("rtn_code") == 0 -> "OK"
        else -> obj.optString("message", "未知错误")
    }
} catch (_: Exception) {
    null
}

private fun locationOnce(context: Context, callback: (Double, Double) -> Unit) {
    if (!context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
        callback(XBC_DEFAULT_POS.first, XBC_DEFAULT_POS.second)
        return
    }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var delivered = false
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (delivered) return
            delivered = true
            manager.removeUpdates(this)
            callback(location.latitude, location.longitude)
        }

        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            runCatching {
                manager.requestSingleUpdate(p, listener, Looper.getMainLooper())
            }
        }
    } catch (_: Exception) {
        // 定位服务不可用时走兜底。
    }
    Handler(Looper.getMainLooper()).postDelayed({
        if (delivered) return@postDelayed
        delivered = true
        runCatching { manager.removeUpdates(listener) }
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .minByOrNull { it.accuracy }
        callback(last?.latitude ?: XBC_DEFAULT_POS.first,
            last?.longitude ?: XBC_DEFAULT_POS.second)
    }, 6_000)
}

private fun xbcStatus(state: Int, status: Int): String = when {
    state == 1 && status == 1 -> "使用中"
    state == 1 -> "空闲"
    state == 10 -> "低电量"
    state == 4 -> "欠压车辆"
    state == 2 -> "损坏车辆"
    else -> "未知"
}

private fun xbcElectric(bike: JSONObject): Double? {
    for (key in listOf("electric_quantity", "electricQuantity", "electric")) {
        val value = bike.opt(key) ?: continue
        value.toString().toDoubleOrNull()?.let { return it }
    }
    return null
}

private fun postJson(url: String, token: String, body: JSONObject): JSONObject? = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 15_000
    conn.requestMethod = "POST"
    conn.setRequestProperty("Authorization", "Bearer $token")
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    conn.doOutput = true
    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        ?: conn.inputStream
    JSONObject(stream.bufferedReader().use { it.readText() })
} catch (_: Exception) {
    null
}