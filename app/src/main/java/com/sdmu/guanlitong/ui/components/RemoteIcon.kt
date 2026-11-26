package com.sdmu.guanlitong.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import com.sdmu.guanlitong.BuildConfig
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private object RemoteIconCache {
    private val cache = LruCache<String, Bitmap>(160)
    fun get(url: String): Bitmap? = cache.get(url)
    fun put(url: String, bitmap: Bitmap) { cache.put(url, bitmap) }
}

/**
 * 官方 YYTB 图标的小型共享缓存；读取失败时调用方保留原生占位图标。
 *
 * [referer] 可选：学习通图片 CDN 对没有 Referer 的请求一律 403，
 * 课程封面必须带上 `https://mooc1.chaoxing.com/`；其他图片保持 null 不变。
 * 同 URL 不同 Referer 使用不同的缓存键，避免拿到 403 后的空结果。
 */
@Composable
fun rememberRemoteIcon(url: String, referer: String? = null): ImageBitmap? {
    val cacheKey = referer?.takeIf { it.isNotBlank() }?.let { "$url\n$it" } ?: url
    var bitmap by remember(cacheKey) { mutableStateOf(RemoteIconCache.get(cacheKey)) }
    LaunchedEffect(cacheKey) {
        if (url.isBlank() || bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { downloadIcon(url, referer) }
        if (loaded != null) {
            RemoteIconCache.put(cacheKey, loaded)
            bitmap = loaded
        }
    }
    return bitmap?.asImageBitmap()
}

private fun downloadIcon(url: String, referer: String?): Bitmap? = runCatching {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000
        readTimeout = 6000
        setRequestProperty("User-Agent", "guanlitong/${BuildConfig.VERSION_NAME}")
        referer?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Referer", it) }
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        val bytes = connection.inputStream.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 192 || bounds.outHeight / sample > 192) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } finally {
        connection.disconnect()
    }
}.getOrNull()
