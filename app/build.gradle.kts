plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.yolooverlay"
    // AGP 8.5.x 官方支持到 API 34；要上 35 请同时升级 AGP 到 8.7+
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.yolooverlay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // 只打包真机用的两种 ABI，去掉 x86/x86_64（只有模拟器用）
            // APK 从 ~85MB 降到 ~45MB
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 模型文件保持未压缩，方便以后需要 mmap 时直接 openFd
    androidResources {
        noCompress += listOf("tflite", "onnx")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // ONNX Runtime for Android：Maven 依赖，自带全部 ABI 的预编译 .so，不需要 NDK。
    // 自带 XNNPACK，ARM 上比 TFLite CPU 快。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
