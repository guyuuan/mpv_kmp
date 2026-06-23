import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.androidLibrary)
}

val androidMpvLibs = listOf(
    "arm64-v8a" to "android-arm64",
    "x86_64" to "android-x86_64",
)
val androidNdkVersion = "29.0.14206865"
val generatedAndroidMpvJniLibsDir = layout.buildDirectory.dir("generated/androidMpvJniLibs").get().asFile

val copyAndroidMpvJniLibs by tasks.registering(Sync::class) {
    description = "copy so to libs"
    androidMpvLibs.forEach { (abi, prefix) ->
        from(rootProject.file("buildscripts/prefix/$prefix/lib")) {
            include("*.so")
            into(abi)
        }
    }
    into(generatedAndroidMpvJniLibsDir)
}

android {
    namespace = "com.guyuuan.mpv_kmp.android.nativebridge"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = androidNdkVersion

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            abiFilters += androidMpvLibs.map { it.first }
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
            jniLibs.directories.add(generatedAndroidMpvJniLibsDir.path)
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(copyAndroidMpvJniLibs)
}
