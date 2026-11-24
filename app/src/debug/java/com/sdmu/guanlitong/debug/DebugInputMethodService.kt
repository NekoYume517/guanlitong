package com.sdmu.guanlitong.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.util.Base64
import androidx.core.content.ContextCompat

/** 仅供模拟器自动化测试输入中文；此 Service 不包含在 release 构建中。 */
class DebugInputMethodService : InputMethodService() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val encoded = intent?.getStringExtra("text") ?: return
            val text = runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: return
            currentInputConnection?.commitText(text, 1)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_INPUT),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_INPUT = "com.sdmu.guanlitong.debug.INPUT"
    }
}
