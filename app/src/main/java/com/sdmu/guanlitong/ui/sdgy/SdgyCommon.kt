package com.sdmu.guanlitong.ui.sdgy

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.sdmu.guanlitong.data.sdgy.SdgyException
import com.sdmu.guanlitong.data.sdgy.SdgyPasswordResetException
import com.sdmu.guanlitong.data.sdgy.SdgyRepository
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 山管二级页统一的外壳：标题 + 返回 + 可选右上角操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdgyScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { actions() },
            )
        },
    ) { padding -> content(padding) }
}

@Composable
fun rememberSdgyRepository(): SdgyRepository {
    val context = LocalContext.current.applicationContext
    return remember(context) { SdgyRepository(context) }
}

@Composable
fun SdgyLoading(text: String = "加载中…", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(14.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SdgyMessage(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

/** 把一段文本编码成二维码。纯离线生成，不上传任何内容。 */
fun qrBitmap(text: String, sizePx: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val off = y * sizePx
        for (x in 0 until sizePx) {
            pixels[off + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    }
}

@Composable
fun QrImage(text: String, modifier: Modifier = Modifier, sizePx: Int = 720) {
    val bitmap = remember(text, sizePx) { qrBitmap(text, sizePx) }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.White),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "二维码",
            modifier = Modifier.fillMaxSize().padding(10.dp),
        )
    }
}

/** 申请单个权限，回调结果；相机等能力由调用方在拿到授权后再使用。 */
@Composable
fun rememberPermissionRequester(onResult: (Boolean) -> Unit): (String) -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(granted) }
    return { permission -> launcher.launch(permission) }
}

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** 页面异步任务里的错误文案：保留业务原因，不把异常类名或协议细节交给用户。 */
fun Throwable.sdgyMessage(): String = when {
    this is CancellationException -> ""
    this is SdgyPasswordResetException -> "请先在官方山管 App 重置密码"
    this is SdgyException && sessionLost -> "山管 App 登录已过期"
    this is SdgyException && code == "not_login" -> "还没登录山管 App"
    this is SdgyException && (
        message.orEmpty().contains("HTTP") ||
            message.orEmpty().contains("握手") ||
            message.orEmpty().contains("无法识别") ||
            message.orEmpty().contains("缺少会话")
        ) -> "暂时连不上山管服务器"
    this is SdgyException -> message?.takeIf { it.isNotBlank() } ?: "操作没成功"
    this is UnknownHostException || this is ConnectException -> "网络没连上"
    this is SocketTimeoutException -> "连接超时了"
    this is SSLException -> "安全连接失败"
    this is JSONException -> "数据没读出来"
    this is IOException -> "网络没连上"
    else -> "操作没成功"
}

@Composable
fun SdgySectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun SdgyKeyValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}