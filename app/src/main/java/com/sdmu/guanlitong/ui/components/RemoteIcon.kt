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

/** 官方 YYTB 图标的小型共享缓存；读取失败时调用方保留原生占位图标。 */
@Composable
fun rememberRemoteIcon(url: String): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf(RemoteIconCache.get(url)) }
    LaunchedEffect(url) {
        if (url.isBlank() || bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { downloadIcon(url) }
        if (loaded != null) {
            RemoteIconCache.put(url, loaded)
            bitmap = loaded
        }
    }
    return bitmap?.asImageBitmap()
}

private fun downloadIcon(url: String): Bitmap? = runCatching {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000
        readTimeout = 6000
        setRequestProperty("User-Agent", "guanlitong/${BuildConfig.VERSION_NAME}")
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
