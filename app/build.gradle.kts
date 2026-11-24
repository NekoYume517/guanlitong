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
        versionCode = 6
        versionName = "1.2.3"
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

    // 教务系统验证码识别：ML Kit 离线文字识别（Latin 模型，打包进 APK，不依赖 Google Play 服务）
    implementation("com.google.mlkit:text-recognition:16.0.1")
}