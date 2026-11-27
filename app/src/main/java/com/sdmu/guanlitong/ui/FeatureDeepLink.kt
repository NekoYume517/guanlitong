package com.sdmu.guanlitong.ui

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 小组件 / 外部启动按功能 id 直达页面。
 *
 * 与 [CxDeepLink] 分开：学习通深链还要带活动、班级等参数，这里只认
 * `feature_id`，由 [com.sdmu.guanlitong.data.FeatureCatalog] 转成页面。
 * MainActivity 只负责解析 intent，AppRoot 消费一次并置空。
 */
object FeatureDeepLink {

    const val EXTRA_FEATURE_ID = "feature_id"

    val pending = MutableStateFlow<String?>(null)

    fun routeOf(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_FEATURE_ID)?.trim()?.takeIf { it.isNotEmpty() }
}