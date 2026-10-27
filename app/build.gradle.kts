plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dpibreak"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dpibreak"
        minSdk = 24          // Android 7.0+ — покрывает все актуальные Samsung
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Список ABI должен совпадать с APP_ABI в app/src/main/jni/Application.mk
            // (иначе Gradle отфильтрует собранную .so и на устройстве будет
            // UnsatisfiedLinkError). arm64-v8a — основной, x86_64 — эмулятор.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // TODO(Задача 12): релизная подпись — signingConfig из локальных пропертис,
            // ключ НЕ коммитить (см. docs/qwen/TASKS.md)
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
    externalNativeBuild {
        ndkBuild {
            // ndk-build: our bridge + byedpi + hev-socks5-tunnel (see app/src/main/jni/Android.mk)
            path = file("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
}
