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

    // .tflite 必须保持未压缩，否则无法用 AssetFileDescriptor 做 mmap
    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // TensorFlow Lite 运行时（CPU/NNAPI）
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // 可选：GPU 代理。需要时打开下面这行，并把 YoloDetector 里的 GPU 分支放开。
    // implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
}
