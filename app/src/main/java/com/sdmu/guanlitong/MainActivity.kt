package com.sdmu.guanlitong

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.sdmu.guanlitong.data.AppRepository
import com.sdmu.guanlitong.data.SessionKeeper
import com.sdmu.guanlitong.ui.AppRoot
import com.sdmu.guanlitong.ui.theme.GuanLiTongTheme
import com.sdmu.guanlitong.widget.refreshDesktopWidgets
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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
        val repository = AppRepository(applicationContext)
        setContent {
            GuanLiTongTheme {
                AppRoot(repository)
            }
        }
    }
}
