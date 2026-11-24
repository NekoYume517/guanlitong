package com.sdmu.guanlitong

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sdmu.guanlitong.data.AppRepository
import com.sdmu.guanlitong.ui.AppRoot
import com.sdmu.guanlitong.ui.theme.GuanLiTongTheme

class MainActivity : ComponentActivity() {

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
