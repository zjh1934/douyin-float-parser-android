plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.douyinfloat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.douyinfloat"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // debug 构建自带 debug 签名，APK 拿到即可直接安装
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// 零第三方依赖：HttpURLConnection + org.json + Handler 全是平台自带，CI 构建最稳
dependencies {}
