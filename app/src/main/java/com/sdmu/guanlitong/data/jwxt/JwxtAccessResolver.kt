package com.sdmu.guanlitong.data.jwxt

import android.content.Context
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.SettingsStore

/**
 * 按当前设置解析教务访问入口，供成绩页、桌面预热和卡片读缓存共用。
 *
 * 关键在于 `origin` 只从 [JwxtAccess] 这一处推导（官方到 /jwglxt、抢课助手到站点根），
 * 三处都走这里，切来源或改地址时不会出现「写一份、读另一份」的缓存错位。
 */
object JwxtAccessResolver {

    /** 当前来源的访问入口；缺地址时返回 null。[relogin] 只给需要后台重登的调用方传。 */
    fun current(
        context: Context,
        relogin: (suspend () -> String?)? = null,
    ): JwxtAccess? {
        val settings = SettingsStore(context)
        val jwxt = ServiceCatalog.byId("jwxt") ?: return null
        val grab = ServiceCatalog.byId("grab")
        return JwxtAccess(
            source = settings.jwxtSource.value,
            officialUrl = settings.urlFor(jwxt),
            grabUrl = grab?.let(settings::urlFor).orEmpty(),
            relogin = relogin,
        )
    }

    /** 当前来源的数据根地址；未配置时为空串。 */
    fun currentOrigin(context: Context): String = current(context)?.origin.orEmpty()
}