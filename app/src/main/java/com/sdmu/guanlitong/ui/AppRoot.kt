package com.sdmu.guanlitong.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sdmu.guanlitong.BuildConfig
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.AppRepository
import com.sdmu.guanlitong.data.CookieUtils
import com.sdmu.guanlitong.data.FeatureAction
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.ServiceFeature
import com.sdmu.guanlitong.data.SdgyPage
import com.sdmu.guanlitong.data.jwxt.JwxtAccess
import com.sdmu.guanlitong.data.jwxt.JwxtCache
import com.sdmu.guanlitong.data.jwxt.JwxtOrigin
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import com.sdmu.guanlitong.data.jwxt.SessionRelogin
import com.sdmu.guanlitong.data.sdgy.SdgySessionStore
import com.sdmu.guanlitong.data.JwxtPage
import com.sdmu.guanlitong.data.update.UpdateClient
import com.sdmu.guanlitong.data.update.UpdateInfo
import com.sdmu.guanlitong.data.update.UpdatePrefs
import com.sdmu.guanlitong.ui.account.AccountScreen
import com.sdmu.guanlitong.ui.account.GrabLoginCard
import com.sdmu.guanlitong.ui.features.FeaturesScreen
import com.sdmu.guanlitong.ui.jwxt.JwxtExamsScreen
import com.sdmu.guanlitong.ui.jwxt.JwxtGradesScreen
import com.sdmu.guanlitong.ui.jwxt.JwxtHubScreen
import com.sdmu.guanlitong.ui.jwxt.JwxtScreen
import com.sdmu.guanlitong.ui.jwxt.JwxtSelectionScreen
import com.sdmu.guanlitong.ui.jwxt.JwxtTasksScreen
import com.sdmu.guanlitong.ui.mine.MineScreen
import com.sdmu.guanlitong.ui.overview.OverviewScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyHubScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyPayScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyPowerScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyScanScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyShotScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyTimetableScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyWebScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyXbcScreen
import com.sdmu.guanlitong.ui.update.UpdateDialog
import com.sdmu.guanlitong.ui.update.UpdateState
import com.sdmu.guanlitong.ui.web.ServiceWebScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Dest {
    data class Web(val serviceId: String, val startUrl: String? = null) : Dest
    data class Account(val serviceId: String) : Dest
    data class Jwxt(val page: JwxtPage = JwxtPage.Timetable) : Dest
    data class Sdgy(val page: SdgyPage = SdgyPage.Hub, val xbcCode: String? = null) : Dest
    /** 官方功能里的网页部分，内置 WebView + 免登凭据打开。 */
    data class SdgyWeb(
        val title: String,
        val appId: String,
        val url: String = "",
        val lx: String = "2",
    ) : Dest
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppRoot(repository: AppRepository) {
    val context = LocalContext.current
    val accounts by repository.accounts.accounts.collectAsState()

    val jwxtSource by repository.settings.jwxtSource.collectAsState()
    val jwxtService = ServiceCatalog.byId("jwxt")!!
    val grabService = ServiceCatalog.byId("grab")
    val jwxtServiceUrl = remember(jwxtService) { repository.urlFor(jwxtService) }
    val grabUrl = remember(grabService) {
        grabService?.let { repository.urlFor(it) }.orEmpty()
    }
    val sessionRelogin = remember { SessionRelogin(repository.accounts) }
    val access = remember(jwxtSource, jwxtServiceUrl, grabUrl) {
        JwxtAccess(jwxtSource, jwxtServiceUrl, grabUrl) {
            sessionRelogin.relogin(jwxtSource, jwxtServiceUrl, grabUrl)
        }
    }
    val jwxtBaseUrl = access.origin

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 放在覆盖页动画外：打开子页面后概览被移出组合，滚动位置也不能随之销毁。
    val overviewScrollState = rememberScrollState()
    // 功能页选中的服务、搜索词同理：进入某个功能再返回时要保持原样，不能跳回第一个。
    var featureServiceId by rememberSaveable {
        mutableStateOf(ServiceCatalog.featureServices.first().id)
    }
    var featureQuery by rememberSaveable { mutableStateOf("") }
    // 滚动状态也必须提权到覆盖页动画外；否则返回时 LazyColumn 会重新从 0 开始。
    val featureScrollStates = remember { mutableMapOf<String, ScrollState>() }
    val featureSearchScrollState = rememberScrollState()
    val featureSdgyListState = rememberLazyListState()
    var overlay by remember { mutableStateOf<Dest?>(null) }
    // +1 前进（页面从右侧滑入），-1 后退（页面向右滑出）。
    var pageDirection by remember { mutableIntStateOf(1) }
    var jwxtRefreshToken by remember { mutableIntStateOf(0) }
    // 从「课表未登录」入口打开网页时，登录成功要自动回到原生课表；
    // 从账号页打开网页则留在网页里，方便继续用成绩、选课等功能。
    var returnToTimetable by remember { mutableStateOf(false) }

    // ── 应用内更新 ────────────────────────────────────────────
    // 版本号直接读 BuildConfig，跟 build.gradle 保持一致。
    val currentVersion = BuildConfig.VERSION_NAME
    val updateScope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    fun checkForUpdate(showUpToDate: Boolean) {
        if (updateState is UpdateState.Downloading) return
        updateState = UpdateState.Checking
        updateScope.launch {
            val result = runCatching { UpdateClient.fetchLatest() }
            UpdatePrefs.markChecked(context)
            val info = result.getOrNull()
            updateState = when {
                result.isFailure -> UpdateState.Failed("请检查网络后重试")

                info == null -> UpdateState.Failed("暂时没有可安装的新版本")
                UpdateClient.isNewer(info.version, currentVersion) -> UpdateState.Available(info)
                showUpToDate -> UpdateState.UpToDate
                else -> UpdateState.Idle
            }
        }
    }

    fun downloadUpdate(info: UpdateInfo) {
        downloadJob?.cancel()
        updateState = UpdateState.Downloading(info, 0, info.apkSize)
        var lastPercent = -1
        downloadJob = updateScope.launch {
            try {
                // 进度回调跑在 IO 线程：只在百分比变化时写状态，避免每 64 KB 重组一次。
                val apk = UpdateClient.download(context, info) { downloaded, total ->
                    val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        updateState = UpdateState.Downloading(info, downloaded, total)
                    }
                }
                val needsPermission = !UpdateClient.canInstall(context)
                updateState = UpdateState.Ready(info, apk, needsPermission)
                // 已经有「安装未知应用」权限时直接把系统安装器拉起来。
                if (!needsPermission) UpdateClient.install(context, apk)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                updateState = UpdateState.Failed("请检查网络后重试", downloadFailure = true)
            }
        }
    }

    fun installUpdate() {
        val ready = updateState as? UpdateState.Ready ?: return
        if (UpdateClient.canInstall(context)) {
            UpdateClient.install(context, ready.apk)
            updateState = UpdateState.Idle
        } else {
            // 没有权限就先跳到系统的「安装未知应用」开关；回来再点一次会直接安装。
            UpdateClient.openInstallPermissionSettings(context)
        }
    }

    // 启动后自动检查一次：12 小时内最多打扰一次，失败静默，不弹任何东西。
    LaunchedEffect(Unit) {
        if (!UpdatePrefs.shouldAutoCheck(context)) return@LaunchedEffect
        delay(1200)
        val info = runCatching { UpdateClient.fetchLatest() }.getOrNull()
        UpdatePrefs.markChecked(context)
        if (info != null && UpdateClient.isNewer(info.version, currentVersion)) {
            updateState = UpdateState.Available(info)
        }
    }

    // 打开 App 或切换数据来源时：若用户没主动退出、密码也还在，但本地已经没有会话，就先补一次登录。
    LaunchedEffect(jwxtSource, jwxtServiceUrl, grabUrl) {
        if (sessionRelogin.ensure(jwxtSource, jwxtServiceUrl, grabUrl)) {
            jwxtRefreshToken++
        }
    }

    fun goForward(dest: Dest) {
        pageDirection = 1
        // 课表只有一个页面：山管侧的「我的课表」也进入教务课表页，
        // 山管数据只在课程详情里补充挂科率和上课人。
        overlay = if (dest is Dest.Sdgy && dest.page == SdgyPage.Timetable) {
            Dest.Jwxt(JwxtPage.Timetable)
        } else {
            dest
        }
    }

    fun goBack() {
        pageDirection = -1
        overlay = null
    }

    /** 教务原生页的「用网页打开」：跟随当前数据来源。 */
    fun openJwxtWeb(returnToTimetableAfterLogin: Boolean) {
        returnToTimetable = returnToTimetableAfterLogin
        goForward(
            Dest.Web(if (jwxtSource == JwxtSource.Grab) "grab" else "jwxt"),
        )
    }

    fun openFeature(feature: ServiceFeature) {
        when (val action = feature.action) {
            FeatureAction.Timetable -> goForward(Dest.Jwxt(JwxtPage.Timetable))

            is FeatureAction.Jwxt -> goForward(Dest.Jwxt(action.page))

            is FeatureAction.Sdgy -> goForward(Dest.Sdgy(action.page))

            is FeatureAction.SdgyApp -> goForward(
                Dest.SdgyWeb(action.action.title, action.action.appId, action.action.url, action.action.lx)
            )

            is FeatureAction.Web -> goForward(Dest.Web(action.serviceId, action.url))

            is FeatureAction.Account -> goForward(Dest.Account(action.serviceId))

            is FeatureAction.External -> {
                val packageName = ServiceCatalog.byId(action.serviceId)?.externalPackage
                val intent = packageName
                    ?.let { context.packageManager.getLaunchIntentForPackage(it) }
                if (intent != null) {
                    context.startActivity(intent)
                } else {
                    goForward(Dest.Web(action.serviceId))
                }
            }
        }
    }

    // 教务原生登录成功后统一处理：注入 Cookie、按需保存密码、触发各原生页刷新。
    fun saveJwxtLogin(user: String, password: String, remember: Boolean, cookies: String) {
        CookieUtils.replaceFor(JwxtOrigin.ORIGIN, cookies, "/jwglxt")
        if (remember) {
            repository.accounts.markLoggedIn(
                serviceId = "jwxt",
                username = user,
                password = password,
            )
        } else {
            repository.accounts.forgetPassword("jwxt")
            repository.accounts.markLoggedIn(serviceId = "jwxt", username = user)
        }
        jwxtRefreshToken++
    }

    BackHandler(enabled = overlay != null && overlay !is Dest.Web) {
        goBack()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        AnimatedContent(
            targetState = overlay,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                // 前进：新页面从右滑入，旧页面轻微左移淡出；
                // 后退：旧页面向右滑出，底层页面从左侧回位。
                if (pageDirection >= 0) {
                    (slideInHorizontally(animationSpec = tween(320)) { width -> width } +
                        fadeIn(animationSpec = tween(200)))
                        .togetherWith(
                            slideOutHorizontally(animationSpec = tween(320)) { width -> -width / 4 } +
                                fadeOut(animationSpec = tween(200))
                        )
                } else {
                    (slideInHorizontally(animationSpec = tween(320)) { width -> -width / 4 } +
                        fadeIn(animationSpec = tween(200)))
                        .togetherWith(
                            slideOutHorizontally(animationSpec = tween(320)) { width -> width } +
                                fadeOut(animationSpec = tween(200))
                        )
                }
            },
            label = "page",
        ) { dest ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                if (dest == null) {
                    Scaffold(
                        bottomBar = {
                            ShortNavigationBar {
                                ShortNavigationBarItem(
                                    selected = tab == 0,
                                    onClick = { tab = 0 },
                                    icon = { Icon(Icons.Outlined.Dashboard, contentDescription = null) },
                                    label = { Text("概览") },
                                )
                                ShortNavigationBarItem(
                                    selected = tab == 1,
                                    onClick = { tab = 1 },
                                    icon = { Icon(Icons.Outlined.Apps, contentDescription = null) },
                                    label = { Text("功能") },
                                )
                                ShortNavigationBarItem(
                                    selected = tab == 2,
                                    onClick = { tab = 2 },
                                    icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                                    label = { Text("我的") },
                                )
                            }
                        },
                    ) { padding ->
                        AnimatedContent(
                            targetState = tab,
                            modifier = Modifier.padding(padding),
                            label = "tab",
                        ) { index ->
                            when (index) {
                                0 -> OverviewScreen(
                                    accounts = accounts,
                                    dashboard = repository.dashboard,
                            quickActions = repository.quickActions,
                                    jwxtBaseUrl = jwxtBaseUrl,
                                    onOpenFeature = { openFeature(it) },
                                    onOpenTimetable = { goForward(Dest.Jwxt(JwxtPage.Timetable)) },
                                    onOpenAccount = { goForward(Dest.Account(it.id)) },
                                    onOpenMine = { tab = 2 },
                                    jwxtSource = jwxtSource,
                                    scrollState = overviewScrollState,
                                )

                                1 -> FeaturesScreen(
                                    accounts = accounts,
                                    selectedServiceId = featureServiceId,
                                    onSelectService = { featureServiceId = it },
                                    query = featureQuery,
                                    onQueryChange = { featureQuery = it },
                                    onOpenFeature = { openFeature(it) },
                                    onOpenAccount = { goForward(Dest.Account(it.id)) },
                                    onOpenSdgyNative = { goForward(Dest.Sdgy(it)) },
                                    onOpenSdgyWeb = { title, appId, url, lx ->
                                        goForward(Dest.SdgyWeb(title, appId, url, lx))
                                    },
                                    scrollStates = featureScrollStates,
                                    searchScrollState = featureSearchScrollState,
                                    sdgyListState = featureSdgyListState,
                                    dataSourceLabel = jwxtSource
                                        .takeIf { it != JwxtSource.Official }
                                        ?.label,
                                    statusAccount = accounts[
                                        if (jwxtSource == JwxtSource.Grab) "grab" else "jwxt"
                                    ],
                                    grabSource = jwxtSource == JwxtSource.Grab,
                                )

                                else -> MineScreen(
                                    accounts = accounts,
                                    onOpenAccount = { goForward(Dest.Account(it.id)) },
                                    onClearAll = {
                                        repository.accounts.clearAll()
                                        CookieUtils.clearAll()
                                        JwxtCache.clear(context)
                                        SdgySessionStore(context).clear()
                                    },
                                    jwxtSource = jwxtSource,
                                    versionName = currentVersion,
                                    updateState = updateState,
                                    onCheckUpdate = { checkForUpdate(showUpToDate = true) },
                                )
                            }
                        }
                    }
                } else {
                    when (dest) {
                        is Dest.Web -> {
                            val service = ServiceCatalog.byId(dest.serviceId)
                            if (service != null) {
                                ServiceWebScreen(
                                    service = service,
                                    startUrl = dest.startUrl ?: repository.urlFor(service),
                                    onBack = {
                                        returnToTimetable = false
                                        goBack()
                                    },
                                    onLoggedIn = {
                                        repository.accounts.markWebLoggedIn(service.id)
                                        // 教务系统（或抢课助手）登录成功后回到原生课表，并触发一次重新拉取。
                                        if ((service.id == "jwxt" || service.id == "grab") && returnToTimetable) {
                                            returnToTimetable = false
                                            jwxtRefreshToken++
                                            pageDirection = -1
                                            overlay = Dest.Jwxt(JwxtPage.Timetable)
                                        }
                                    },
                                )
                            }
                        }

                        is Dest.Account -> {
                            val service = ServiceCatalog.byId(dest.serviceId)
                            if (service != null) {
                                AccountScreen(
                                    service = service,
                                    account = accounts[service.id] ?: Account(serviceId = service.id),
                                    serviceUrl = repository.urlFor(service),
                                    store = repository.accounts,
                                    onBack = { goBack() },
                                    onOpenWeb = {
                                        returnToTimetable = false
                                        goForward(Dest.Web(service.id))
                                    },
                                    onOpenTimetable = if (service.id == "jwxt") {
                                        { goForward(Dest.Jwxt(JwxtPage.Timetable)) }
                                    } else {
                                        null
                                    },
                                    jwxtSource = jwxtSource,
                                    onSelectJwxtSource = if (service.id == "jwxt") {
                                        { source: JwxtSource ->
                                            repository.settings.setJwxtSource(source)
                                            jwxtRefreshToken++
                                        }
                                    } else {
                                        null
                                    },
                                    grabAccount = accounts["grab"],
                                    grabUrl = grabUrl,
                                    onGrabLoginChanged = { jwxtRefreshToken++ },
                                )
                            }
                        }

                        is Dest.Sdgy -> when (dest.page) {
                            SdgyPage.Hub -> SdgyHubScreen(
                                onBack = { goBack() },
                                onOpenNative = { goForward(Dest.Sdgy(it)) },
                                onOpenWeb = { title, appId, url, lx ->
                                    goForward(Dest.SdgyWeb(title, appId, url, lx))
                                },
                                onOpenAccount = { goForward(Dest.Account("sdgy")) },
                            )

                            SdgyPage.Timetable -> SdgyTimetableScreen(
                                onBack = { goBack() },
                                onOpenAccount = { goForward(Dest.Account("sdgy")) },
                            )

                            SdgyPage.Pay -> SdgyPayScreen(
                                onBack = { goBack() },
                                onOpenAccount = { goForward(Dest.Account("sdgy")) },
                            )

                            SdgyPage.Power -> SdgyPowerScreen(onBack = { goBack() })

                            SdgyPage.Scan -> SdgyScanScreen(
                                onBack = { goBack() },
                                onOpenXbc = { code ->
                                    goForward(Dest.Sdgy(SdgyPage.Xbc, code))
                                },
                            )

                            SdgyPage.Shot -> SdgyShotScreen(onBack = { goBack() })

                            SdgyPage.Xbc -> SdgyXbcScreen(
                                initialCode = dest.xbcCode,
                                onBack = {
                                    if (dest.xbcCode != null) {
                                        overlay = Dest.Sdgy(SdgyPage.Scan)
                                    } else {
                                        goBack()
                                    }
                                },
                            )
                        }

                        is Dest.SdgyWeb -> SdgyWebScreen(
                            title = dest.title,
                            appId = dest.appId,
                            url = dest.url,
                            lx = dest.lx,
                            onBack = { goBack() },
                        )

                        is Dest.Jwxt -> {
                            val jwxtAccount = accounts["jwxt"] ?: Account(serviceId = "jwxt")
                            val savedPassword = remember(jwxtAccount.username, jwxtAccount.hasPassword) {
                                repository.accounts.passwordOf("jwxt").orEmpty()
                            }
                            // 抢课助手只当数据源：未登录时统一去唯一的账号入口（官方教务系统页），在数据来源里登录。
                            val openGrabLogin = { goForward(Dest.Account("jwxt")) }
                            when (dest.page) {
                                JwxtPage.Timetable -> JwxtScreen(
                                    access = access,
                                    refreshToken = jwxtRefreshToken,
                                    initialUsername = jwxtAccount.username,
                                    initialPassword = savedPassword,
                                    rememberInitial = jwxtAccount.hasPassword,
                                    onLoginSuccess = { u, p, r, c -> saveJwxtLogin(u, p, r, c) },
                                    onGrabLogin = openGrabLogin,
                                    onBack = { goBack() },
                                    onOpenWeb = { openJwxtWeb(true) },
                                )

                                JwxtPage.Hub -> JwxtHubScreen(
                                    access = access,
                                    onBack = { goBack() },
                                    onOpenWeb = { openJwxtWeb(false) },
                                    onOpenTimetable = { goForward(Dest.Jwxt(JwxtPage.Timetable)) },
                                    onOpenGrades = { goForward(Dest.Jwxt(JwxtPage.Grades)) },
                                    onOpenExams = { goForward(Dest.Jwxt(JwxtPage.Exams)) },
                                    onOpenSelection = { goForward(Dest.Jwxt(JwxtPage.Selection)) },
                                    onOpenTasks = { goForward(Dest.Jwxt(JwxtPage.Tasks)) },
                                )

                                JwxtPage.Grades -> JwxtGradesScreen(
                                    access = access,
                                    refreshToken = jwxtRefreshToken,
                                    initialUsername = jwxtAccount.username,
                                    initialPassword = savedPassword,
                                    rememberInitial = jwxtAccount.hasPassword,
                                    onLoginSuccess = { u, p, r, c -> saveJwxtLogin(u, p, r, c) },
                                    onGrabLogin = openGrabLogin,
                                    onBack = { goBack() },
                                    onOpenWeb = { openJwxtWeb(false) },
                                )

                                JwxtPage.Exams -> JwxtExamsScreen(
                                    access = access,
                                    refreshToken = jwxtRefreshToken,
                                    initialUsername = jwxtAccount.username,
                                    initialPassword = savedPassword,
                                    rememberInitial = jwxtAccount.hasPassword,
                                    onLoginSuccess = { u, p, r, c -> saveJwxtLogin(u, p, r, c) },
                                    onGrabLogin = openGrabLogin,
                                    onBack = { goBack() },
                                    onOpenWeb = { openJwxtWeb(false) },
                                )

                                JwxtPage.Selection -> JwxtSelectionScreen(
                                    access = access,
                                    refreshToken = jwxtRefreshToken,
                                    initialUsername = jwxtAccount.username,
                                    initialPassword = savedPassword,
                                    rememberInitial = jwxtAccount.hasPassword,
                                    onLoginSuccess = { u, p, r, c -> saveJwxtLogin(u, p, r, c) },
                                    onGrabLogin = openGrabLogin,
                                    onBack = { goBack() },
                                    onOpenWeb = { openJwxtWeb(false) },
                                    // 抢课 tab 里直接给出抢课助手的账号密码输入框。
                                    grabLoginContent = {
                                        GrabLoginCard(
                                            grabUrl = grabUrl,
                                            account = accounts["grab"]
                                                ?: Account(serviceId = "grab"),
                                            store = repository.accounts,
                                            onLoggedIn = { jwxtRefreshToken++ },
                                            onSignedOut = { jwxtRefreshToken++ },
                                        )
                                    },
                                )

                                JwxtPage.Tasks -> JwxtTasksScreen(
                                    access = access,
                                    refreshToken = jwxtRefreshToken,
                                    onBack = { goBack() },
                                    onOpenWeb = { openJwxtWeb(false) },
                                    onOpenSelection = { goForward(Dest.Jwxt(JwxtPage.Selection)) },
                                    grabLoginContent = {
                                        GrabLoginCard(
                                            grabUrl = grabUrl,
                                            account = accounts["grab"]
                                                ?: Account(serviceId = "grab"),
                                            store = repository.accounts,
                                            onLoggedIn = { jwxtRefreshToken++ },
                                            onSignedOut = { jwxtRefreshToken++ },
                                        )
                                    },
                                )
                            }
                        }

                    }
                }
            }
        }

        // 更新对话框盖在根页面之上：无论在哪个 tab，发现新版本都能直接提示。
        UpdateDialog(
            state = updateState,
            onDownload = { (updateState as? UpdateState.Available)?.let { downloadUpdate(it.info) } },
            onInstall = ::installUpdate,
            onRetry = { checkForUpdate(showUpToDate = true) },
            onDismiss = {
                if (updateState is UpdateState.Downloading) downloadJob?.cancel()
                updateState = UpdateState.Idle
            },
        )
    }
}