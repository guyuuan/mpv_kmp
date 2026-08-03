plugins {
    alias(libs.plugins.androidLibrary)
}

val androidMpvAbis = listOf("arm64-v8a", "x86_64")
val androidNdkVersion = "29.0.14206865"

android {
    namespace = "com.guyuuan.mpv_kmp.android.nativebridge"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = androidNdkVersion

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            abiFilters += androidMpvAbis
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            // buildall.sh already copies and strips the final libraries here.
            // Referencing them directly prevents Gradle from overwriting the
            // packaged files with unstripped copies from buildscripts/prefix.
            jniLibs.directories.add(file("libs").path)
        }
    }
}
