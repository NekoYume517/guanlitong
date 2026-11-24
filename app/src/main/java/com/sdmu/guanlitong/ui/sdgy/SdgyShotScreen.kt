package com.sdmu.guanlitong.ui.sdgy

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 请销假生成器。
 *
 * HTML 编辑器整体打包在 assets/editor.html 里（html2canvas 离线可用），
 * 页面点击「保存/分享」时通过 ShotBridge 把裁剪后的 PNG 交回原生：
 * 保存到相册，按需拉起系统分享面板。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SdgyShotScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    // 分包版本身就是整页 HTML（含微信栏 + 红色标题条），不再套原生 Scaffold 顶栏。
    BackHandler(onBack = onBack)
    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .windowInsetsPadding(WindowInsets.statusBars),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                webViewClient = WebViewClient()
                addJavascriptInterface(ShotBridge(context.applicationContext, this, onBack), "ShotBridge")
                addJavascriptInterface(SdmuBridge(context.applicationContext), "SDMU")
                loadUrl("file:///android_asset/editor.html")
            }
        },
        onRelease = { it.destroy() },
    )
}

private class ShotBridge(
    private val context: Context,
    private val webView: WebView,
    private val onBack: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun back() {
        // JavascriptInterface 在 WebView 子线程执行，Compose 导航必须切回主线程。
        main.post(onBack)
    }

    @JavascriptInterface
    fun save(base64: String, name: String, share: String) {
        Thread {
            val result = runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "")
                    .ifBlank { "请销假申请" } + ".png"
                val uri = saveToGallery(bytes, safeName)
                if (share == "1") shareImage(uri, safeName)
                JSONObject().put("saved", true).put("shared", share == "1")
            }.getOrElse { e ->
                JSONObject().put("error", e.message?.take(120) ?: "保存失败")
            }
            main.post {
                webView.evaluateJavascript("window.__shotResult($result)", null)
            }
        }.start()
    }

    private fun saveToGallery(bytes: ByteArray, name: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SDMU")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建相册文件")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("无法写入相册")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw IllegalStateException("外部存储不可用")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(bytes) }
        BitmapFactory.decodeFile(file.absolutePath) // 确认文件可读
        MediaStore.Images.Media.insertImage(
            context.contentResolver, file.absolutePath, name, "请销假申请",
        )
        return FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    }

    private fun shareImage(uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, name.substringBeforeLast("."))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "分享截图")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/**
 * 请销假表单用的 SDMU 桥（对齐 sdmu-lite 的 SdmuBridge 契约）：
 * editor.html 通过 window.SDMU.getUser() 自动填入学号/姓名/学院/班级。
 */
private class SdmuBridge(private val context: Context) {

    @JavascriptInterface
    fun getUser(): String {
        val session = runCatching {
            com.sdmu.guanlitong.data.sdgy.SdgySessionStore(context).load()
        }.getOrNull()
        val u = session?.user ?: JSONObject()
        return JSONObject().apply {
            put("userId", u.optString("userId"))
            put("userName", u.optString("userName"))
            put("userType", u.optString("userType"))
            put("schoolId", u.optString("schoolId"))
            put("className", u.optString("className"))
            put("department", u.optString("department"))
        }.toString()
    }

    @JavascriptInterface
    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}