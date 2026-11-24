package com.sdmu.guanlitong.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 更新源：源码和 Release 都放在 GitHub 上的公开仓库。 */
object UpdateSource {
    const val OWNER = "NekoYume517"
    const val REPO = "guanlitong"
    val latestUrl: String get() = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    val listUrl: String get() = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"
    val releasesPage: String get() = "https://github.com/$OWNER/$REPO/releases"
}

/** 一次检查拿到的版本信息。 */
data class UpdateInfo(
    val version: String,
    val tag: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
)

/**
 * 从 GitHub Release 检查并下载更新。
 *
 * 多线程体现在两处：
 * 1. `releases/latest` 与 `releases?per_page=10` 两个接口在 IO 线程池上并发请求，
 *    谁先返回可用的就先用谁。GitHub 匿名 API 有频率限制，单接口超时或 404 时
 *    另一个还能兜底，检查不会整体卡死。
 * 2. APK 下载全程在 IO 线程，边读边写并回调进度，主线程只负责画进度条。
 */
object UpdateClient {

    private const val USER_AGENT = "GuanLiTong-Android"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val CHUNK = 64 * 1024

    /** 拿到仓库里版本最高、且带 APK 资产的正式 Release；没有就返回 null。 */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        coroutineScope {
            val latest = async { runCatching { requestObject(UpdateSource.latestUrl) }.getOrNull() }
            val list = async { runCatching { requestArray(UpdateSource.listUrl) }.getOrNull() }

            val candidates = buildList {
                latest.await()?.let { add(it) }
                list.await()?.let { array ->
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { add(it) }
                    }
                }
            }
            candidates.asSequence()
                .mapNotNull { parseRelease(it) }
                .reduceOrNull { best, next ->
                    if (compareVersions(next.version, best.version) > 0) next else best
                }
        }
    }

    /** 候选版本是否比当前版本新。 */
    fun isNewer(candidate: String, current: String): Boolean = compareVersions(candidate, current) > 0

    /** 版本段逐位比较：1.2.10 比 1.2.9 新，1.3 比 1.2.99 新。 */
    fun compareVersions(a: String, b: String): Int {
        val left = versionParts(a)
        val right = versionParts(b)
        for (index in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrElse(index) { 0 }
            val y = right.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** 下载 APK 到 cache/updates，返回落盘文件；失败抛异常。 */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, info.apkName.ifBlank { "guanlitong-${info.version}.apk" })
        if (target.exists()) target.delete()

        val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("下载失败：HTTP $code")
            val total = info.apkSize.takeIf { it > 0 }
                ?: connection.contentLengthLong.coerceAtLeast(0L)
            var downloaded = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }
            if (info.apkSize > 0 && target.length() != info.apkSize) {
                target.delete()
                error("下载不完整：${target.length()} / ${info.apkSize}")
            }
        } finally {
            connection.disconnect()
        }
        target
    }

    /** 安装未知来源应用的权限是否已经打开。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 跳到系统的「安装未知应用」总开关页面。 */
    fun openInstallPermissionSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 用系统安装器打开下载好的 APK。 */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // ── JSON / 网络 ─────────────────────────────────────────

    private fun parseRelease(json: JSONObject): UpdateInfo? {
        if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return null
        val tag = json.optString("tag_name").trim()
        val version = tag.removePrefix("v").removePrefix("V")
        // 只认形如 1.2.3 的正式版本，避免把 nightly / beta 之类的 tag 当成更新。
        if (!version.matches(Regex("\\d+(\\.\\d+)*"))) return null

        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apk: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apk = asset
                break
            }
        }
        val asset = apk ?: return null
        val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: return null
        return UpdateInfo(
            version = version,
            tag = tag,
            title = json.optString("name").ifBlank { tag },
            notes = json.optString("body").trim(),
            apkUrl = url,
            apkName = asset.optString("name"),
            apkSize = asset.optLong("size"),
        )
    }

    private fun versionParts(version: String): List<Int> =
        version.split('.').map { segment -> segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun requestObject(url: String): JSONObject = JSONObject(requestText(url))

    private fun requestArray(url: String): JSONArray = JSONArray(requestText(url))

    private fun requestText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("GitHub 返回 HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/** 自动检查的节流：启动时最多每 12 小时查一次，手动检查不受限制。 */
object UpdatePrefs {
    private const val FILE = "guanlitong_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val INTERVAL_MS = 12 * 60 * 60 * 1000L

    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > INTERVAL_MS
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }
}