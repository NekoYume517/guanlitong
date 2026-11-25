package com.sdmu.guanlitong.ui.sdgy

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sdmu.guanlitong.BuildConfig
import com.sdmu.guanlitong.data.sdgy.SdgyConst
import com.sdmu.guanlitong.data.sdgy.SdgyWebAuth
import kotlinx.coroutines.CancellationException

/**
 * 官方功能里的网页部分：内置 WebView + 山管免登凭据。
 *
 * 打开顺序与官方一致：
 *  - LX=2：先走 `api/lapp/springboard?yyid=`，由服务端换票后跳到最终页面；
 *  - LX=4/6（h5=true）：直接加载条目里的 URL；
 *  - LX=8：目标地址直接拼 `accessToken/uniqueCode/yyid`；
 *  - 其他：有 URL 就直开，没有就走跳板。
 *
 * 首次加载带 Access-Token/Unique-Code/AppType 请求头，并把同款凭据写进
 * Cookie，后续同域跳转、页面里的 XHR 都能继续用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SdgyWebScreen(
    title: String,
    appId: String,
    url: String,
    lx: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = rememberSdgyRepository()
    val context = LocalContext.current

    var resolvedUrl by remember(title, appId, url, lx) { mutableStateOf<String?>(null) }
    var auth by remember(title, appId, url, lx) { mutableStateOf<SdgyWebAuth?>(null) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableIntStateOf(0) }
    // 网页内部跳转后的真实地址，供右上角「浏览器打开」使用。
    var currentUrl by remember(title, appId, url, lx) { mutableStateOf("") }

    LaunchedEffect(title, appId, url, lx, retryToken) {
        resolvedUrl = null
        currentUrl = ""
        loadingError = null
        try {
            val credentials = repository.call(forceCheck = true) { it.webAuth() }
            val target = when {
                // 官方 LX=8：第三方单点，凭据直接拼在目标地址上。
                lx == "8" && url.isNotBlank() ->
                    repository.call { it.thirdPartySsoUrl(url, appId) }
                // 官方 LX=4/6（h5=true）：直接加载 URL，不再走跳板。
                (lx == "4" || lx == "6") && url.isNotBlank() -> url
                // 官方 LX=2：先走 springboard，由服务端换票后 302 到真实页面。
                lx == "2" && appId.isNotBlank() ->
                    repository.call { it.springboardUrl(appId) }
                url.isNotBlank() -> url
                appId.isNotBlank() -> repository.call { it.springboardUrl(appId) }
                else -> ""
            }
            if (target.isBlank()) {
                loadingError = "这个功能暂时不可用"
            } else {
                auth = credentials
                resolvedUrl = target
                currentUrl = target
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loadingError = e.sdgyMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val target = currentUrl
                            if (target.isNotBlank()) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(target)),
                                    )
                                }
                            }
                        },
                        enabled = currentUrl.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = "在浏览器中打开",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val target = resolvedUrl
            when {
                loadingError != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            loadingError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = { retryToken++ },
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text("重试") }
                    }
                }
                target == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    SdgyWebView(
                        target = target,
                        auth = auth,
                        onBack = onBack,
                        onUrlChanged = { currentUrl = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SdgyWebView(
    target: String,
    auth: SdgyWebAuth?,
    onBack: () -> Unit,
    onUrlChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
        fileCallback = null
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

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.WHITE)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = "$userAgentString GuanLiTong/${BuildConfig.VERSION_NAME}"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            isForceDarkAllowed = false
                        }
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?,
                        ) {
                            super.onPageStarted(view, url, favicon)
                            progress = 5
                            pageError = null
                            url?.let(onUrlChanged)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            progress = 100
                            url?.let(onUrlChanged)
                            if (auth != null) applyWebCookies(auth, target)
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            url?.let(onUrlChanged)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                pageError = "页面暂时打不开，请稍后重试"
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val scheme = uri.scheme.orEmpty()
                            if (scheme != "http" && scheme != "https") {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                                return true
                            }
                            // 同域后续请求靠 Cookie 免登；这里不能重新 loadUrl，
                            // 否则会把官方页面里的 POST 表单变成 GET，直接丢数据。
                            return false
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?,
                        ): Boolean {
                            if (view == null || resultMsg == null) return false
                            val child = WebView(view.context)
                            child.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    childView: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val next = request?.url ?: return false
                                    view.loadUrl(next.toString())
                                    child.destroy()
                                    return true
                                }
                            }
                            (resultMsg.obj as WebView.WebViewTransport).webView = child
                            resultMsg.sendToTarget()
                            return true
                        }

                        override fun onShowFileChooser(
                            view: WebView?,
                            callback: ValueCallback<Array<Uri>>?,
                            params: FileChooserParams?,
                        ): Boolean {
                            if (callback == null || params == null) return false
                            fileCallback?.onReceiveValue(null)
                            fileCallback = callback
                            return runCatching {
                                fileLauncher.launch(params.createIntent())
                                true
                            }.getOrDefault(false)
                        }
                    }

                    setDownloadListener { downloadUrl, _, _, _, _ ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)),
                            )
                        }
                    }

                    if (auth != null) applyWebCookies(auth, target)
                    if (auth != null && (target.startsWith(SdgyConst.SERVER))) {
                        loadUrl(target, auth.headers)
                    } else {
                        loadUrl(target)
                    }
                    webView = this
                }
            },
            update = { view ->
                if (webView == null) webView = view
            },
        )

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }

        if (pageError != null) {
            Column(
                Modifier.fillMaxSize()
                    .padding(32.dp)
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "页面暂时打不开，请稍后重试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = {
                        pageError = null
                        webView?.reload()
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("重新加载") }
            }
        }
    }
}

/**
 * CookieManager 一次只认一条 Set-Cookie，三个凭据必须逐条写。
 * 官方 LX=4 会把凭据写到目标站点；这里同样只在 .sdmu.edu.cn 校内站点铺开，
 * 公共站点（如 www.sdmu.edu.cn 新闻页）不接收 token。
 */
private fun applyWebCookies(auth: SdgyWebAuth, target: String) {
    val manager = CookieManager.getInstance()
    val urls = linkedSetOf(SdgyConst.SERVER)
    val uri = runCatching { Uri.parse(target) }.getOrNull()
    val host = uri?.host.orEmpty()
    if (uri != null && (host.endsWith(".sdmu.edu.cn") || host == "sdmu.edu.cn") &&
        !host.startsWith("www.")
    ) {
        urls += "${uri.scheme}://${uri.authority}"
    }
    urls.forEach { url ->
        auth.cookies.forEach { (name, value) ->
            manager.setCookie(url, "$name=$value")
        }
    }
    manager.flush()
}
