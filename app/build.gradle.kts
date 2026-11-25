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
        versionCode = 11
        versionName = "1.2.8"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.12.3")

    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
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