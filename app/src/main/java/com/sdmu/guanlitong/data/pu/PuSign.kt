package com.sdmu.guanlitong.data.pu

import android.os.Build
import com.pu.encrypt.StringEncryptor
import org.json.JSONObject
import java.util.UUID

/**
 * 上游 `Sign.java` 的移植：UA 与 X-Sign。
 *
 * X-Sign 只对写接口有意义，其中 `/apis/activity/join` **必须**带（§2.2）；
 * 其余接口带上也不会被拒，所以统一「库可用就带」。
 *
 * 注意：`StringEncryptor` 的静态初始化里会 `System.loadLibrary` 并调用
 * `android.util.Log`，在 JVM 单元测试里会抛错，所以这里所有调用都包在
 * `runCatching` 里，失败一律降级为「不可用 / null」，绝不让异常冒到页面。
 */
object PuSign {

    const val VERSION = "7.1.99"

    /** 与上游逐字一致的 UA 格式。 */
    fun userAgent(): String = runCatching {
        "client:Android version:$VERSION" +
            " Brand:${nz(Build.BRAND)}" +
            " Product:${nz(Build.MODEL)}" +
            " OsVersion:${nz(Build.VERSION.RELEASE)}"
    }.getOrDefault("client:Android version:$VERSION Brand: Product: OsVersion:")

    /**
     * `StringEncryptor.encrypt({"echo":uuid4,"client":"android","timestamp":"<unix 秒>"})`。
     *
     * `timestamp` 必须是**字符串**（上游就是这么序列化的），改成数字会让服务端验签失败。
     * 库不可用 / 加密失败返回 `null`。
     */
    fun xSign(): String? {
        if (!available()) return null
        return runCatching {
            val o = JSONObject()
            o.put("echo", UUID.randomUUID().toString())
            o.put("client", "android")
            o.put("timestamp", (System.currentTimeMillis() / 1000L).toString())
            StringEncryptor.encrypt(o.toString())
        }.getOrNull()
    }

    /** `arm64-v8a` 原生库是否装载成功；非 arm64 设备 / 测试环境为 false（§6.5）。 */
    fun available(): Boolean = runCatching { StringEncryptor.available() }.getOrDefault(false)

    /** 调试页 / 生成签到码页展示用。 */
    internal fun state(): PuSignState = PuSignState(
        libLoaded = available(),
        sign = xSign().orEmpty(),
        ua = userAgent(),
    )

    private fun nz(s: String?): String = s ?: ""
}