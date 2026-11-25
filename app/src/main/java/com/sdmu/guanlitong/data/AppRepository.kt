package com.sdmu.guanlitong.data

import android.content.Context

/** 把各个存储聚合起来，供 UI 层注入使用。 */
class AppRepository(context: Context) {
    val accounts = AccountStore(context)
    val settings = SettingsStore(context)
    val dashboard = DashboardStore(context)
    val quickActions = QuickActionStore(
        context,
        dashboard.items.value
            .filter { it.type == WidgetType.QuickActions }
            .map { it.id },
    )

    fun urlFor(service: SchoolService): String = settings.urlFor(service)
}
