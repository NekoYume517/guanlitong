import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名：密钥放在项目的 keystore/ 目录，密码写在 keystore.properties；
// 两者都在 .gitignore 里，不会随源码公开。没有这个文件时回退到调试签名。
val releaseKeystore = Properties().apply {
    rootProject.file("keystore.properties")
        .takeIf { it.exists() }
        ?.inputStream()
        ?.use { load(it) }
}

val hasReleaseKeystore = releaseKeystore.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.sdmu.guanlitong"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.sdmu.guanlitong"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.2.9"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有本地密钥就用它；没有时（比如别人 clone 后直接构建）退回调试签名，
            // 两种方式证书相同，应用内更新都能覆盖安装。
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // JVM 单测里没有真正的 android.util.Log，默认会抛 "not mocked"。
        // 让这些桩方法返回默认值，重试/诊断日志就不会把异常覆盖成 RuntimeException。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.12.3")

    // 收件箱/提醒的 30 分钟周期任务（Doze 下由系统延后执行）。
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // 学习通作业页解析（doWork HTML）与题目配图加载。
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.glance:glance-appwidget:1.2.0") {
        version { strictly("1.2.0") }
    }
    implementation("androidx.glance:glance:1.2.0") {
        version { strictly("1.2.0") }
    }
    implementation("androidx.glance:glance-material3:1.2.0") {
        version { strictly("1.2.0") }
    }

    // 教务系统验证码识别：ML Kit 离线文字识别（Latin 模型，打包进 APK，不依赖 Google Play 服务）
    implementation("com.google.mlkit:text-recognition:16.0.1")

// 一卡通付款码的二维码生成，完全离线。
    implementation("com.google.zxing:core:3.5.3")

    // 口袋校园「扫一扫」的内嵌取景器，完全离线。
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // PU 数据层单元测试：JVM 上没有可用的 org.json / JUnit，显式引入。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}