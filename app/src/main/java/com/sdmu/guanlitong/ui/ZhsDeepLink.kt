package com.sdmu.guanlitong.ui

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 知到任务通知点击后的落地页。
 *
 * 常驻通知点「知到 · 某某课」直接打开实时进度页；MainActivity 只解析 intent，
 * AppRoot 消费一次并置空，与 [CxDeepLink] / [FeatureDeepLink] 同一套做法。
 */
object ZhsDeepLink {

    const val EXTRA_PAGE = "zhs_page"
    const val PAGE_STUDY = "study"

    val pending = MutableStateFlow(false)

    fun routeOf(intent: Intent?): Boolean =
        intent?.getStringExtra(EXTRA_PAGE)?.trim() == PAGE_STUDY
}