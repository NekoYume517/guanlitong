package com.sdmu.guanlitong

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.sdmu.guanlitong.data.AppRepository
import com.sdmu.guanlitong.data.SessionKeeper
import com.sdmu.guanlitong.ui.AppRoot
import com.sdmu.guanlitong.ui.CxDeepLink
import com.sdmu.guanlitong.ui.FeatureDeepLink
import com.sdmu.guanlitong.ui.ZhsDeepLink
import com.sdmu.guanlitong.ui.theme.GuanLiTongTheme
import com.sdmu.guanlitong.widget.refreshDesktopWidgets
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 冷启动：通知 / 外部启动带来的学习通深链先放进 pending，AppRoot 首次组合时消费。
        CxDeepLink.pending.value = CxDeepLink.routeOf(intent)
        FeatureDeepLink.pending.value = FeatureDeepLink.routeOf(intent)
        ZhsDeepLink.pending.value = ZhsDeepLink.routeOf(intent)
        val repository = AppRepository(applicationContext)
        setContent {
            GuanLiTongTheme {
                AppRoot(repository)
            }
        }
    }
}
