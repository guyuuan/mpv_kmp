import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.mpv)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    android {
        namespace = "com.guyuuan.kmp.mpv.example.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "com.guyuuan.mpv.kmp.shared")
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.material.iconsExtended)
            val localProperties = Properties().apply {
                val file = rootProject.file("local.properties")
                if (file.exists()) {
                    file.inputStream().use(::load)
                }
            }
            val devMode = localProperties["devMode"]
            logger.warn("dev mode: $devMode")
            if(devMode == "release"){
                api(libs.mpv.pip)
                api(libs.mpv.coil)
            }else{
                api(projects.mpv.pip)
                api(projects.mpv.loaderCoil)
            }
            implementation(libs.kermit)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
