package com.sdmu.guanlitong.ui.zhs

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sdmu.guanlitong.data.zhs.ZhsHttp

/**
 * 网易易盾滑块验证窗口。
 *
 * 这里只承载易盾官方组件（`load.min.js`，参数与智慧树登录页一致），
 * 由真人在弹出的滑块上完成验证；结果通过 JS bridge 原样回传 [onValidate]。
 * 页面不读取账号密码，也不做任何验证码识别。
 *
 * 页面用 `loadDataWithBaseURL` 挂到 passport 域名下，让易盾请求的 Origin
 * 与真实登录页一致，避免 file:// 页面被跨域策略挡住。
 */
@Composable
fun ZhsCaptchaDialog(
    onValidate: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("正在加载验证组件…") }
    var failed by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun cancelIfActive() {
        if (!finished) {
            finished = true
            onCancel()
        }
    }

    Dialog(
        onDismissRequest = { cancelIfActive() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "安全验证",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "滑块由你本人完成，App 只把验证结果提交给智慧树。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { cancelIfActive() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭验证")
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            @SuppressLint("SetJavaScriptEnabled")
                            WebView(viewContext).apply {
                                setBackgroundColor(Color.parseColor("#F7F7F8"))
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.userAgentString = ZhsHttp.DEFAULT_USER_AGENT
                                android.webkit.CookieManager.getInstance()
                                    .setAcceptThirdPartyCookies(this, true)

                                addJavascriptInterface(
                                    CaptchaBridge { action ->
                                        main.post {
                                            when (action) {
                                                is BridgeAction.Validate -> {
                                                    if (!finished) {
                                                        finished = true
                                                        onValidate(action.validate)
                                                    }
                                                }
                                                BridgeAction.Closed -> {
                                                    // 页面自己会重新显示「开始验证」，原生不再叠一个「重试」。
                                                    message = "你关闭了验证窗口，可再次点击「开始验证」。"
                                                    failed = false
                                                }
                                                is BridgeAction.Fail -> {
                                                    message = action.message
                                                    failed = true
                                                }
                                                BridgeAction.Ready -> {
                                                    message = "请点击「开始验证」并拖动滑块。"
                                                    failed = false
                                                }
                                            }
                                        }
                                    },
                                    "ZhsCaptcha",
                                )
                                webViewClient = object : WebViewClient() {
                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        if (request.isForMainFrame) {
                                            main.post {
                                                message = "验证页面加载失败，请检查网络后重试。"
                                                failed = true
                                            }
                                        }
                                    }
                                }
                                val html = viewContext.assets.open("zhs_captcha.html")
                                    .bufferedReader()
                                    .use { it.readText() }
                                loadDataWithBaseURL(
                                    "https://passport.zhihuishu.com/",
                                    html,
                                    "text/html",
                                    "utf-8",
                                    null,
                                )
                            }.also { webView = it }
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (failed) {
                        TextButton(
                            onClick = {
                                failed = false
                                message = "正在重新加载验证组件…"
                                webView?.evaluateJavascript("window.zhsRetry()", null)
                            },
                        ) { Text("重试") }
                    }
                    TextButton(onClick = { cancelIfActive() }) { Text("取消") }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }
}

private sealed interface BridgeAction {
    data object Ready : BridgeAction
    data class Validate(val validate: String) : BridgeAction
    data object Closed : BridgeAction
    data class Fail(val message: String) : BridgeAction
}

/** 易盾页面的回调入口；方法运行在 WebView 的 JavaBridge 线程。 */
private class CaptchaBridge(private val callback: (BridgeAction) -> Unit) {

    // JS bridge 按签名匹配，页面统一会传一个字符串参数（可能为空），
    // 这里不能用无参方法，否则调用会被静默丢弃。
    @JavascriptInterface
    fun onLoading(unused: String) {}

    @JavascriptInterface
    fun onReady(unused: String) = callback(BridgeAction.Ready)

    @JavascriptInterface
    fun onValidate(validate: String) {
        if (validate.isNotBlank()) callback(BridgeAction.Validate(validate))
    }

    @JavascriptInterface
    fun onClosed(unused: String) = callback(BridgeAction.Closed)

    @JavascriptInterface
    fun onFail(message: String) {
        callback(BridgeAction.Fail(message.ifBlank { "验证失败，请重试。" }))
    }
}