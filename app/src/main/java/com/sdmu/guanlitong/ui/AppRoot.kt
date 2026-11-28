package com.sdmu.guanlitong.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.BuildConfig
import com.sdmu.guanlitong.data.Account
import com.sdmu.guanlitong.data.AccountDefaults
import com.sdmu.guanlitong.data.AppRepository
import com.sdmu.guanlitong.data.CookieUtils
import com.sdmu.guanlitong.data.FeatureAction
import com.sdmu.guanlitong.data.FeatureCatalog
import com.sdmu.guanlitong.data.OnboardingPrefs
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.ServiceFeature
import com.sdmu.guanlitong.data.SdgyPage
import com.sdmu.guanlitong.data.CxPage
import com.sdmu.guanlitong.data.PuPage
import com.sdmu.guanlitong.data.ZhsPage
import com.sdmu.guanlitong.data.cx.CxCourse
import com.sdmu.guanlitong.data.cx.CxCredStore
import com.sdmu.guanlitong.data.cx.CxDocTaskStatus
import com.sdmu.guanlitong.data.cx.CxRepository
import com.sdmu.guanlitong.data.cx.CxSignStore
import com.sdmu.guanlitong.service.BrushTaskRegistry
import com.sdmu.guanlitong.service.BrushTaskRoutes
import com.sdmu.guanlitong.service.BrushTaskSnapshot
import com.sdmu.guanlitong.service.CxKeepAlive
import com.sdmu.guanlitong.service.CxSignManualNotify
import com.sdmu.guanlitong.service.CxTaskService
import com.sdmu.guanlitong.data.jwxt.JwxtAccess
import com.sdmu.guanlitong.data.jwxt.JwxtCache
import com.sdmu.guanlitong.data.jwxt.JwxtOrigin
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import com.sdmu.guanlitong.data.jwxt.SessionRelogin
import com.sdmu.guanlitong.data.sdgy.SdgySessionStore
import com.sdmu.guanlitong.data.pu.PuCredStore
import com.sdmu.guanlitong.data.zhs.ZhsAuthCenter
import com.sdmu.guanlitong.data.zhs.ZhsCourse
import com.sdmu.guanlitong.data.zhs.ZhsRepository
import com.sdmu.guanlitong.data.zhs.ZhsSessionStore
import com.sdmu.guanlitong.data.zhs.ZhsStoredAccountProvider
import com.sdmu.guanlitong.ui.components.BrushTaskBall
import com.sdmu.guanlitong.ui.zhs.ZhsBrushProgressScreen
import com.sdmu.guanlitong.ui.zhs.ZhsReloginDialog
import com.sdmu.guanlitong.ui.zhs.ZhsCourseDetailScreen
import com.sdmu.guanlitong.ui.zhs.ZhsCoursesScreen
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
import com.sdmu.guanlitong.ui.onboarding.OnboardingScreen
import com.sdmu.guanlitong.ui.overview.OverviewScreen
import com.sdmu.guanlitong.ui.cx.CxCourseDetailScreen
import com.sdmu.guanlitong.ui.cx.CxCourseHomeScreen
import com.sdmu.guanlitong.ui.cx.CxMaterialsScreen
import com.sdmu.guanlitong.ui.cx.CxCoursesScreen
import com.sdmu.guanlitong.ui.cx.CxGestureSignScreen
import com.sdmu.guanlitong.ui.cx.CxInboxScreen
import com.sdmu.guanlitong.ui.cx.CxKeepAliveGuideDialog
import com.sdmu.guanlitong.ui.cx.CxSignScreen
import com.sdmu.guanlitong.ui.cx.CxSignSettingsScreen
import com.sdmu.guanlitong.ui.cx.CxStudyBallPreview
import com.sdmu.guanlitong.ui.cx.CxStudyBallPreviewState
import com.sdmu.guanlitong.ui.cx.CxStudyDetailScreen
import com.sdmu.guanlitong.ui.cx.CxWorkAnswerScreen
import com.sdmu.guanlitong.ui.pu.PuActivitiesScreen
import com.sdmu.guanlitong.ui.pu.PuActivityDetailScreen
import com.sdmu.guanlitong.ui.pu.PuCreditScreen
import com.sdmu.guanlitong.ui.pu.PuFriendsScreen
import com.sdmu.guanlitong.ui.pu.PuMineScreen
import com.sdmu.guanlitong.ui.pu.PuPersonalCodeScreen
import com.sdmu.guanlitong.ui.pu.PuScanScreen
import com.sdmu.guanlitong.ui.pu.PuSignCodeScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyHubScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyPayScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyPowerScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyShotScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyTimetableScreen
import com.sdmu.guanlitong.ui.sdgy.SdgyWebScreen
import com.sdmu.guanlitong.ui.update.UpdateDialog
import com.sdmu.guanlitong.ui.update.UpdateState
import com.sdmu.guanlitong.ui.web.ServiceWebScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Dest {
    /** 首次启动引导，和普通页面一样进栈：去登录再返回时还能回到引导。 */
    data object Onboarding : Dest
    data class Web(val serviceId: String, val startUrl: String? = null) : Dest
    data class Account(val serviceId: String) : Dest
    data class Jwxt(val page: JwxtPage = JwxtPage.Timetable) : Dest
    data class Sdgy(val page: SdgyPage = SdgyPage.Hub) : Dest
    /** 第二课堂原生页面；[activityId] 非空表示在活动列表之上叠加活动详情。 */
    data class Pu(val page: PuPage = PuPage.Activities, val activityId: Long? = null) : Dest
    /** 学习通原生页面；[course] 非空表示课程占位详情。 */
    data class Cx(val page: CxPage = CxPage.Courses, val course: CxCourse? = null) : Dest

    /** 知到（智慧树）原生页面。 */
    data class Zhs(val page: ZhsPage = ZhsPage.Courses) : Dest

    /** 知到课程详情：章节树 + 学习进度。 */
    data class ZhsDetail(val course: ZhsCourse) : Dest

    /** 知到刷课实时进度页：浮球 / 通知 / 章节页「查看进度」进入，只读任务控制器。 */
    data object ZhsStudy : Dest

    /** 某门课程自己的功能页（课程名 + 模块列表）；不占用 [CxPage]，功能页入口保持原样。 */
    data class CxCourseHome(val course: CxCourse? = null) : Dest
    /** 学习通作业答题页；[entryUrl] 是收件箱消息里的作业入口地址。 */
    data class CxWork(val entryUrl: String, val title: String) : Dest
    /** 学习通手势签到页：九宫格必须当场划，参数取自通知或收件箱链接。 */
    data class CxGesture(
        val activeId: String,
        val courseId: String,
        val classId: String,
        val courseName: String = "",
        val activeName: String = "",
    ) : Dest
    /** 刷课详情页：浮球点击进入，只读 [com.sdmu.guanlitong.service.CxTaskStatus]。 */
    data object CxStudy : Dest
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

    // 首次安装、且还没有任何已登录账号时才引导；看过或跳过就写标记，之后不再出现。
    // 引导也是一个页面，进栈后「去登录教务系统」再返回会回到它，而不是直接退出引导。
    val startWithGuide = remember {
        OnboardingPrefs.shouldShow(context) && accounts.values.none { it.loggedIn }
    }
    // 引导翻到第几页也记着，登录回来时停在同一页。
    var onboardingPage by rememberSaveable { mutableIntStateOf(0) }

    val jwxtSource by repository.settings.jwxtSource.collectAsState()
    // 多任务刷课浮球的数据源：学习通章节/资料、知到各自注册，互不影响。
    val brushTasks by BrushTaskRegistry.tasks.collectAsState()
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

    // App 自己的底部导航栏实测高度（含系统导航栏内边距）：浮球停靠时要让出这块，
    // 否则会压住「功能」「我的」等 tab 的点击区。旋转/栏高变化时 onSizeChanged 会重报。
    var rootBottomBarHeightPx by remember { mutableIntStateOf(0) }
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
    var overlay by remember {
        mutableStateOf<Dest?>(if (startWithGuide) Dest.Onboarding else null)
    }
    // 页面栈：进入新页面时把当前页压进来，返回时再弹出去，
    // 这样详情页返回的是它上层的列表，而不是直接回主导航。
    val destHistory = remember { mutableStateListOf<Dest>() }
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
    // 保活引导最多出现一次；之后只在设置页「后台保活」区展示入口。
    var keepAliveGuideVisible by remember { mutableStateOf(false) }
    val appContext = context.applicationContext

    // 知到过期自动重登：账号与记住的密码都从本地账号存储里取。
    LaunchedEffect(Unit) {
        ZhsAuthCenter.bindProvider(ZhsStoredAccountProvider(appContext))
    }

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
        val next = if (dest is Dest.Sdgy && dest.page == SdgyPage.Timetable) {
            Dest.Jwxt(JwxtPage.Timetable)
        } else {
            dest
        }
        overlay?.let { destHistory.add(it) }
        overlay = next
    }

    fun goBack() {
        pageDirection = -1
        overlay = if (destHistory.isEmpty()) null else destHistory.removeAt(destHistory.lastIndex)
    }

    // 通知 / 冷启动的深链：MainActivity 解析后放进 CxDeepLink，这里只消费一次就清空。
    // 已经在目标页时不重复入栈（例如连续点两条同一活动的通知）。
    LaunchedEffect(Unit) {
        CxDeepLink.pending.collect { route ->
            if (route == null) return@collect
            CxDeepLink.pending.value = null
            val next = when (route) {
                CxRoute.Inbox -> Dest.Cx(CxPage.Inbox)
                CxRoute.Sign -> Dest.Cx(CxPage.Sign)
                is CxRoute.Gesture -> Dest.CxGesture(
                    route.activeId,
                    route.courseId,
                    route.classId,
                    route.courseName,
                    route.activeName,
                )
            }
            if (overlay != next) goForward(next)
        }
    }

    // 已登录且「签到实时提醒」默认开着：App 一启动就确保签到轮询与保活链在跑。
    // 服务内部有启停去重，课程页/设置页再请求也不会重复开。
    LaunchedEffect(Unit) {
        val cxStore = CxSignStore(appContext)
        if (!cxStore.settings().monitorEnabled) return@LaunchedEffect
        if (!CxRepository(appContext).loggedIn) return@LaunchedEffect
        CxTaskService.startSign(appContext)
        CxKeepAlive.enable(appContext)
        if (!cxStore.hasShownKeepAliveGuide()) keepAliveGuideVisible = true
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

            is FeatureAction.Pu -> goForward(Dest.Pu(action.page))

            is FeatureAction.Cx -> goForward(Dest.Cx(action.page))

            is FeatureAction.Zhs -> goForward(Dest.Zhs(action.page))

            is FeatureAction.SdgyApp -> {
                val web = action.action
                when {
                    web.opensExternalApp -> runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.url)))
                    }.onFailure {
                        Toast.makeText(context, "没有能打开「${web.title}」的应用", Toast.LENGTH_SHORT).show()
                    }
                    // 官方 App 自己的原生功能没有网页地址，打不开。
                    web.isAppInternal ->
                        Toast.makeText(context, "这个功能暂时不能在管院通里打开", Toast.LENGTH_SHORT).show()
                    // 没登录山管时，内置网页只会显示加载失败，直接去登录页。
                    accounts["sdgy"]?.loggedIn != true -> goForward(Dest.Account("sdgy"))
                    else -> goForward(Dest.SdgyWeb(web.title, web.appId, web.url, web.lx))
                }
            }

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

    // 桌面小组件的「按功能直达」：只认 feature_id，映射与快捷入口共用 openFeature。
    LaunchedEffect(Unit) {
        FeatureDeepLink.pending.collect { featureId ->
            if (featureId == null) return@collect
            FeatureDeepLink.pending.value = null
            FeatureCatalog.byId(featureId)?.let { openFeature(it) }
        }
    }

    // 知到刷课通知点击：直接落到实时进度页；已在进度页不重复入栈。
    LaunchedEffect(Unit) {
        ZhsDeepLink.pending.collect { wanted ->
            if (!wanted) return@collect
            ZhsDeepLink.pending.value = false
            if (overlay !is Dest.ZhsStudy) goForward(Dest.ZhsStudy)
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
        // 最外层 Box：页面栈在里层，刷课浮球盖在所有页面之上。
        // 浮球放在 AnimatedContent 外，切页时位置 remember 不会随页面销毁；
        // 容器按 safeDrawing 收边，并额外避让 App 自己的底栏高度，
        // 球不会压到状态栏 / 系统导航栏 / 根页底部 tab 上。
        Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = overlay,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                // 引导进出用淡入淡出，其余页面按前进 / 后退的方向滑动。
                if (initialState == Dest.Onboarding || targetState == Dest.Onboarding) {
                    fadeIn(tween(240)).togetherWith(fadeOut(tween(180)))
                } else if (pageDirection >= 0) {
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
                            ShortNavigationBar(
                                modifier = Modifier.onSizeChanged {
                                    rootBottomBarHeightPx = it.height
                                },
                            ) {
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
                                    favorites = repository.favorites,
                                )

                                else -> MineScreen(
                                    accounts = accounts,
                                    onOpenAccount = { goForward(Dest.Account(it.id)) },
                                    onClearAll = {
                                        repository.accounts.clearAll()
                                        CookieUtils.clearAll()
                                        JwxtCache.clear(context)
                                        SdgySessionStore(context).clear()
                                        PuCredStore(context).clear()
                                        CxCredStore(context).clear()
                                        ZhsSessionStore(context).clear()
                                    },
                                    jwxtSource = jwxtSource,
                                    versionName = currentVersion,
                                    updateState = updateState,
                                    onCheckUpdate = { checkForUpdate(showUpToDate = true) },
                                    onShowOnboarding = {
                                        onboardingPage = 0
                                        goForward(Dest.Onboarding)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    when (dest) {
                        // 引导只出现一次：跳过 / 看完 / 系统返回都算看过。
                        is Dest.Onboarding -> OnboardingScreen(
                            // 已经登录过教务系统的用户，第二页直接显示「下一步」。
                            jwxtLoggedIn = accounts["jwxt"]?.loggedIn == true,
                            initialPage = onboardingPage,
                            onPageChange = { onboardingPage = it },
                            onSkip = {
                                OnboardingPrefs.markDone(context)
                                goBack()
                            },
                            onAddAccount = {
                                tab = 2
                                goForward(Dest.Account("jwxt"))
                            },
                            onFinish = {
                                OnboardingPrefs.markDone(context)
                                goBack()
                            },
                        )

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

                            SdgyPage.Shot -> SdgyShotScreen(onBack = { goBack() })
                        }

                        is Dest.Pu -> when (dest.page) {
                            PuPage.Activities -> if (dest.activityId != null) {
                                PuActivityDetailScreen(
                                    activityId = dest.activityId,
                                    onBack = { goBack() },
                                    onOpenSignCode = { goForward(Dest.Pu(PuPage.SignCode, it)) },
                                )
                            } else {
                                PuActivitiesScreen(
                                    onBack = { goBack() },
                                    onOpenActivity = { goForward(Dest.Pu(PuPage.Activities, it)) },
                                )
                            }

                            PuPage.Mine -> PuMineScreen(
                                onBack = { goBack() },
                                onOpenActivity = { goForward(Dest.Pu(PuPage.Activities, it)) },
                            )

                            PuPage.Credit -> PuCreditScreen(onBack = { goBack() })

                            PuPage.Friends -> PuFriendsScreen(onBack = { goBack() })

                            PuPage.PersonalCode -> PuPersonalCodeScreen(onBack = { goBack() })

                            PuPage.Scan -> PuScanScreen(
                                onBack = { goBack() },
                                onOpenActivity = { goForward(Dest.Pu(PuPage.Activities, it)) },
                            )

                            PuPage.SignCode -> PuSignCodeScreen(
                                onBack = { goBack() },
                                initialActivityId = dest.activityId,
                            )
                        }

                        is Dest.CxCourseHome -> CxCourseHomeScreen(
                            course = dest.course,
                            onBack = { goBack() },
                            onOpenModule = { page ->
                                goForward(Dest.Cx(page, dest.course))
                            },
                        )

                        is Dest.Cx -> when (dest.page) {
                            CxPage.Courses -> CxCoursesScreen(
                                onBack = { goBack() },
                                // 先到这门课的功能页，再按模块进具体功能。
                                onOpenCourse = { goForward(Dest.CxCourseHome(it)) },
                            )

                            CxPage.CourseDetail -> CxCourseDetailScreen(
                                course = dest.course,
                                onBack = { goBack() },
                            )

                            CxPage.Materials -> CxMaterialsScreen(
                                course = dest.course,
                                onBack = { goBack() },
                            )

                            CxPage.Sign -> CxSignScreen(
                                onBack = { goBack() },
                                onOpenSettings = { goForward(Dest.Cx(CxPage.SignSettings)) },
                                // 签到页的「点课程去签到」也先落课程功能页，再进章节刷课。
                                onOpenCourse = { goForward(Dest.CxCourseHome(it)) },
                                onOpenWork = { actionUrl, title ->
                                    goForward(Dest.CxWork(actionUrl, title))
                                },
                                onOpenGesture = { activeId, courseId, classId, courseName, activeName ->
                                    goForward(
                                        Dest.CxGesture(
                                            activeId,
                                            courseId,
                                            classId,
                                            courseName,
                                            activeName,
                                        ),
                                    )
                                },
                            )

                            CxPage.SignSettings -> CxSignSettingsScreen(
                                onBack = { goBack() },
                            )

                            CxPage.Inbox -> CxInboxScreen(
                                onBack = { goBack() },
                                onOpenWork = { actionUrl, title ->
                                    goForward(Dest.CxWork(actionUrl, title))
                                },
                                // 收件箱详情里识别出可手划的签到时，进九宫格页当场划。
                                onOpenGesture = { activeId, courseId, classId, courseName, activeName ->
                                    goForward(
                                        Dest.CxGesture(
                                            activeId,
                                            courseId,
                                            classId,
                                            courseName,
                                            activeName,
                                        ),
                                    )
                                },
                            )
                        }

                        is Dest.CxWork -> CxWorkAnswerScreen(
                            entryUrl = dest.entryUrl,
                            title = dest.title,
                            onBack = { goBack() },
                        )

                        is Dest.CxGesture -> CxGestureSignScreen(
                            activeId = dest.activeId,
                            courseId = dest.courseId,
                            classId = dest.classId,
                            courseName = dest.courseName,
                            activeName = dest.activeName,
                            onBack = { goBack() },
                            onDone = { success, message ->
                                // 成功：提示并留在页上（可继续看结果）；失败只展示组件内的结果，不自动返回。
                                if (success) {
                                    Toast.makeText(context, "签到成功", Toast.LENGTH_SHORT).show()
                                    CxSignManualNotify.notifySuccess(
                                        context = context,
                                        title = "签到成功",
                                        body = message.ifBlank { "手势签到已提交" },
                                    )
                                }
                            },
                        )

                        Dest.CxStudy -> CxStudyDetailScreen(onBack = { goBack() })

                        is Dest.Zhs -> when (dest.page) {
                            ZhsPage.Courses -> ZhsCoursesScreen(
                                onBack = { goBack() },
                                onOpenAccount = { goForward(Dest.Account(ZhsRepository.SERVICE_ID)) },
                                onOpenCourse = { goForward(Dest.ZhsDetail(it)) },
                            )
                        }

                        is Dest.ZhsDetail -> ZhsCourseDetailScreen(
                            course = dest.course,
                            onBack = { goBack() },
                            onOpenAccount = { goForward(Dest.Account(ZhsRepository.SERVICE_ID)) },
                            onOpenProgress = { goForward(Dest.ZhsStudy) },
                        )

                        Dest.ZhsStudy -> ZhsBrushProgressScreen(
                            onBack = { goBack() },
                            onOpenAccount = { goForward(Dest.Account(ZhsRepository.SERVICE_ID)) },
                        )

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
                                    rememberInitial = AccountDefaults.REMEMBER_PASSWORD,
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
                                    rememberInitial = AccountDefaults.REMEMBER_PASSWORD,
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
                                    rememberInitial = AccountDefaults.REMEMBER_PASSWORD,
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
                                    rememberInitial = AccountDefaults.REMEMBER_PASSWORD,
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

        // 刷课浮球：任务没在跑时组件自身不画球、也不拦截手势。
            // 已经在刷课详情页时点击不再入栈，避免叠出多个详情页。
            // debug 预览由 adb 广播切换，用假状态走同一套渲染，不启动服务、不联网。
            // 根 tab 页有 App 底栏，浮球布局已按 safeDrawing 收边，底栏又自带系统内边距，
            // 所以只补「底栏实测高度 − 系统底栏内边距」这一段，保证气泡压在底栏上方。
            val density = LocalDensity.current
            val safeBottomPx = WindowInsets.safeDrawing.getBottom(density)
            val ballBottomAvoid = if (overlay == null && rootBottomBarHeightPx > 0) {
                with(density) {
                    (rootBottomBarHeightPx - safeBottomPx).coerceAtLeast(0).toDp()
                }
            } else {
                0.dp
            }
            val showBallPreview = BuildConfig.DEBUG && CxStudyBallPreviewState.visible
            if (showBallPreview) {
                CxStudyBallPreview(
                    onOpen = { /* 预览不跳详情，也不开刷 */ },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    bottomAvoid = ballBottomAvoid,
                )
            } else {
                BrushTaskBall(
                    tasks = brushTasks,
                    onOpenTask = { task ->
                        when (task.route) {
                            BrushTaskRoutes.CX_STUDY ->
                                if (overlay !is Dest.CxStudy) goForward(Dest.CxStudy)

                            BrushTaskRoutes.ZHS_STUDY ->
                                if (overlay !is Dest.ZhsStudy) goForward(Dest.ZhsStudy)

                            // 学习通资料：用任务自己带的课程上下文回到该课程的资料页，
                            // 不跳空页；已经在该课程资料页时不重复入栈。
                            BrushTaskRoutes.CX_MATERIAL -> {
                                val context = CxDocTaskStatus.state.value.course
                                if (context != null) {
                                    val target = CxCourse(
                                        courseId = context.courseId,
                                        clazzId = context.clazzId,
                                        personId = context.personId,
                                        name = context.name,
                                        teacher = "",
                                        coverUrl = "",
                                        link = "",
                                    )
                                    val current = overlay as? Dest.Cx
                                    if (current?.page != CxPage.Materials ||
                                        current.course?.courseId != target.courseId
                                    ) {
                                        goForward(Dest.Cx(CxPage.Materials, target))
                                    }
                                }
                            }

                            // 未识别的 route 只展示、不跳转。
                            else -> Unit
                        }
                    },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    bottomAvoid = ballBottomAvoid,
                )
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

        // 知到会话过期：任何页面抛 EXPIRED 都会拉起这个重登弹窗，成功后各页面自动刷新。
        ZhsReloginDialog()

        // 首次进入且有监控时的保活引导：看完/跳过就写标记，之后不再弹。
        if (keepAliveGuideVisible) {
            CxKeepAliveGuideDialog(
                onDismiss = {
                    CxSignStore(appContext).markKeepAliveGuideShown()
                    keepAliveGuideVisible = false
                },
                onOpenSettings = {
                    CxSignStore(appContext).markKeepAliveGuideShown()
                    keepAliveGuideVisible = false
                    goForward(Dest.Cx(CxPage.SignSettings))
                },
            )
        }

        }
}