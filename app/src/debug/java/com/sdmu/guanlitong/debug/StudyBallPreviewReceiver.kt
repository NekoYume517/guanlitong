package com.sdmu.guanlitong.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sdmu.guanlitong.ui.cx.CxStudyBallPreviewState

/**
 * 刷课浮球的调试预览开关，只随 debug 构建打包（见 `app/src/debug/AndroidManifest.xml`）。
 *
 * 只改 [CxStudyBallPreviewState] 这个纯渲染开关，不碰 CxTaskService、不联网、
 * 不改真实刷课状态；release 构建里既没有这个类，也没有清单注册。
 */
class StudyBallPreviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CxStudyBallPreviewState.ACTION_SHOW -> CxStudyBallPreviewState.show()
            CxStudyBallPreviewState.ACTION_HIDE -> CxStudyBallPreviewState.hide()
        }
    }
}