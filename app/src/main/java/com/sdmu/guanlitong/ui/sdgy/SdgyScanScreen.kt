package com.sdmu.guanlitong.ui.sdgy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.DecodeHintType
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.Size

/** 从 sdlongren 车牌码里取车辆编号；URL ?code= 或纯数字。 */
fun bikeCodeOf(text: String): String? {
    val raw = text.trim()
    Regex("(?:[?&]code=)([^&#]+)").find(raw)?.let { return it.groupValues[1].trim() }
    return if (Regex("^\\d+$").matches(raw)) raw else null
}

/**
 * 全屏扫码框。扫码成功回调一次，随后暂停相机；关闭由调用方控制。
 */
@Composable
fun SdgyScannerDialog(
    onDismiss: () -> Unit,
    onDecoded: (String) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    var torch by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }
    val requestPermission = rememberPermissionRequester { granted = it }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!granted) {
                Column(
                    Modifier.align(Alignment.Center).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("扫一扫需要相机权限", color = Color.White,
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { requestPermission(Manifest.permission.CAMERA) }) {
                        Text("授权相机", color = Color.White)
                    }
                }
            } else {
                val scanner = remember {
                    BarcodeView(context).apply {
                        val settings = cameraSettings
                        settings.setAutoFocusEnabled(true)
                        settings.setContinuousFocusEnabled(true)
                        settings.setBarcodeSceneModeEnabled(true)
                        cameraSettings = settings
                        val hints = java.util.EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
                        hints[DecodeHintType.TRY_HARDER] = true
                        setDecoderFactory(DefaultDecoderFactory(null, hints, "UTF-8", 0))
                        val side = (280 * resources.displayMetrics.density).toInt()
                        setFramingRectSize(Size(side, side))
                        decodeContinuous { result ->
                            val text = result?.text ?: return@decodeContinuous
                            if (text.isBlank() || fired) return@decodeContinuous
                            fired = true
                            pause()
                            onDecoded(text)
                        }
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { scanner.resume(); scanner },
                    onRelease = { scanner.setTorch(false); scanner.pause() },
                )
                Box(
                    Modifier.align(Alignment.Center).size(280.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(18.dp)),
                )
                Text(
                    "将二维码放入框内",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center)
                        .padding(top = 330.dp),
                )
                Row(
                    Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = {
                        torch = !torch
                        scanner.setTorch(torch)
                    }) {
                        Icon(
                            if (torch) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                            contentDescription = "手电筒",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                    }
                }
            }
            if (!granted) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun SdgyScanScreen(
    onBack: () -> Unit,
    onOpenXbc: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(true) }

    if (scanning) {
        SdgyScannerDialog(
            onDismiss = {
                scanning = false
                if (result == null) onBack()
            },
            onDecoded = {
                result = it
                scanning = false
            },
        )
    }

    SdgyScaffold(title = "扫一扫", onBack = onBack, modifier = modifier) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text("识别二维码", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "支持普通二维码与小白超人车牌码；扫描结果只在本机处理。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(onClick = { scanning = true }) {
                Text("开始扫描")
            }
        }
    }

    val text = result
    if (!scanning && text != null) {
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("扫描结果") },
            text = {
                Text(text, style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("scan", text))
                    }) { Text("复制") }
                    TextButton(onClick = { result = null; scanning = true }) { Text("继续扫") }
                }
            },
            dismissButton = {
                val code = bikeCodeOf(text)
                if (text.contains("sdlongren", true) && code != null) {
                    TextButton(onClick = {
                        result = null
                        onOpenXbc(code)
                    }) { Text("去小白超人") }
                }
            },
        )
    }
}