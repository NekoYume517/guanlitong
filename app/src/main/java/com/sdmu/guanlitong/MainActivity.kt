package com.sdmu.guanlitong

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.sdmu.guanlitong.data.AppRepository
import com.sdmu.guanlitong.data.SessionKeeper
import com.sdmu.guanlitong.data.srun.SrunAutoAuthMonitor
import com.sdmu.guanlitong.data.srun.SrunAutoResult
import com.sdmu.guanlitong.service.SrunAutoNotifier
import com.sdmu.guanlitong.ui.AppRoot
import com.sdmu.guanlitong.ui.CxDeepLink
import com.sdmu.guanlitong.ui.FeatureDeepLink
import com.sdmu.guanlitong.ui.ZhsDeepLink
import com.sdmu.guanlitong.ui.theme.GuanLiTongTheme
import com.sdmu.guanlitong.widget.refreshDesktopWidgets
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 注册系统网络回调：应用进程存活时响应 Wi-Fi 变化，不启动定时任务。 */
    private var srunAutoAuth: SrunAutoAuthMonitor? = null

    /** 同进程内页面开关和编辑后的凭据变化同步到唯一监听器。 */
    fun syncSrunAutoAuth() = srunAutoAuth?.syncSetting() ?: Unit

    /** App 已在前台 / 后台时点通知：singleTop 复用实例，新 intent 走这里。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 没有深链参数的 intent（例如签到成功通知）不动 pending，避免吞掉还没消费的路由。
        CxDeepLink.routeOf(intent)?.let { CxDeepLink.pending.value = it }
        FeatureDeepLink.routeOf(intent)?.let { FeatureDeepLink.pending.value = it }
        if (ZhsDeepLink.routeOf(intent)) ZhsDeepLink.pending.value = true
    }

    override fun onResume() {
        super.onResume()
        refreshDesktopWidgets(this)
        // 三个账号的统一保活：会话失效就自动重登，失败也不退出登录。
        lifecycleScope.launch {
            SessionKeeper.onAppForeground(this@MainActivity)
        }
    }

    override fun onDestroy() {
        srunAutoAuth?.dispose()
        srunAutoAuth = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 冷启动：通知 / 外部启动带来的学习通深链先放进 pending，AppRoot 首次组合时消费。
        CxDeepLink.pending.value = CxDeepLink.routeOf(intent)
        FeatureDeepLink.pending.value = FeatureDeepLink.routeOf(intent)
        ZhsDeepLink.pending.value = ZhsDeepLink.routeOf(intent)
        val repository = AppRepository(applicationContext)
        srunAutoAuth = SrunAutoAuthMonitor(applicationContext, lifecycleScope) { result, _ ->
            SrunAutoNotifier.notify(
                this,
                success = result is SrunAutoResult.Success,
                detail = (result as? SrunAutoResult.Failure)?.message.orEmpty(),
            )
        }.also { it.observeSetting() }
        setContent {
            GuanLiTongTheme {
                AppRoot(repository)
            }
        }
    }
}
