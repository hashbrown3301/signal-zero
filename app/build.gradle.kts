plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.itantra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itantra"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

    }

    // One APK per CPU type instead of one with all three (296 MB → ~250 MB each):
    // app-arm64-v8a-debug.apk (S25, M21), app-armeabi-v7a-debug.apk (A03 Core), app-x86_64-debug.apk (emulator).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    androidResources {
        // sherpa-onnx memory-maps models straight out of the APK, so keep them uncompressed.
        noCompress += "onnx"
    }

    buildTypes {
        release {
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Downloaded by scripts/fetch_models.py (not committed).
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
    // The prepared Java AAR shares sherpa's ORT 1.27.1 native runtime; no pickFirst.
    implementation(files("libs/onnxruntime-android-1.27.0-shared.aar"))
    implementation(files("libs/onnxruntime-extensions-android-0.13.0.aar"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Provides Linux/macOS/Windows native libraries for JVM inference smoke tests.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.27.0")
}
