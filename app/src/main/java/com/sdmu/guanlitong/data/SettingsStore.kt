package com.sdmu.guanlitong.data

import android.content.Context
import com.sdmu.guanlitong.data.jwxt.JwxtSource
import com.sdmu.guanlitong.widget.refreshDesktopWidgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 服务地址、教务数据来源等非敏感设置。 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences("guanlitong_settings", Context.MODE_PRIVATE)

    private val _urls = MutableStateFlow(loadUrls())
    val urls: StateFlow<Map<String, String>> = _urls.asStateFlow()

    private val _jwxtSource = MutableStateFlow(
        JwxtSource.fromKey(prefs.getString(SOURCE_JWXT, null)),
    )
    val jwxtSource: StateFlow<JwxtSource> = _jwxtSource.asStateFlow()

    fun setJwxtSource(source: JwxtSource) {
        prefs.edit().putString(SOURCE_JWXT, source.key).apply()
        _jwxtSource.value = source
        refreshDesktopWidgets(appContext)
    }

    private fun loadUrls(): Map<String, String> = buildMap {
        ServiceCatalog.services.forEach { service ->
            prefs.getString(urlKey(service.id), null)?.let { put(service.id, it) }
        }
    }

    fun urlFor(service: SchoolService): String =
        _urls.value[service.id]?.takeIf { it.isNotBlank() } ?: service.defaultUrl

    fun setUrl(serviceId: String, url: String) {
        val cleaned = url.trim()
        if (cleaned.isBlank()) {
            prefs.edit().remove(urlKey(serviceId)).apply()
        } else {
            prefs.edit().putString(urlKey(serviceId), cleaned).apply()
        }
        _urls.value = loadUrls()
        refreshDesktopWidgets(appContext)
    }

    fun resetUrl(serviceId: String) {
        prefs.edit().remove(urlKey(serviceId)).apply()
        _urls.value = loadUrls()
        refreshDesktopWidgets(appContext)
    }

    private fun urlKey(id: String) = "url_$id"

    private companion object {
        const val SOURCE_JWXT = "source_jwxt"
    }
}
