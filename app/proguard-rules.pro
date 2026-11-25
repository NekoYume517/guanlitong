# 保留 WebView JS 接口（如有）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# PU: JNI 静态注册的 X-Sign 桥，禁止混淆/移除（改名即 UnsatisfiedLinkError）
-keep class com.pu.encrypt.StringEncryptor { *; }
