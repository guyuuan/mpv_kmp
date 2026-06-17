plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.guyuuan.mpv_kmp.android.nativebridge"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}
