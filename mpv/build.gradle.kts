import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    `maven-publish`
}

val iosNativeLibrariesDir = layout.projectDirectory.dir("src/iosMain/nativeLibs/iphoneos")
val iosNativeLibrariesZip by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Packages iOS mpv dynamic libraries for app embedding."
    archiveClassifier.set("ios-arm64-native-libs")
    from(iosNativeLibrariesDir) {
        include("lib*.dylib")
    }
}
val desktopNativeResourcesDir = layout.projectDirectory.dir("src/jvmMain/resources").asFile
val desktopNativePlatforms = desktopNativeResourcesDir
    .listFiles()
    ?.filter { candidate -> candidate.isDirectory && candidate.listFiles()?.any { it.isFile } == true }
    ?.map { it.name }
    ?.sorted()
    ?: emptyList()

fun String.toDesktopNativeTaskSuffix(): String =
    split("-").joinToString("") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

val desktopNativeLibrariesZips = desktopNativePlatforms.map { platform ->
    tasks.register<Zip>("${platform.toDesktopNativeTaskSuffix()}MpvKmpDesktopNativeLibrariesZip") {
        group = "publishing"
        description = "Packages JVM desktop mpv native libraries for $platform."
        archiveClassifier.set("jvm-$platform-native-libs")
        from(desktopNativeResourcesDir.resolve(platform)) {
            include("*")
        }
    }
}

kotlin {
    android {
        namespace = "com.guyuuan.mpv_kmp.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
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
            binaryOption("bundleId", "com.guyuuan.mpv.kmp.mpv")
        }
        iosTarget.compilations.getByName("main").cinterops.create("mpv") {
            defFile("src/nativeInterop/cinterop/mpv.def")
            includeDirs(project.file("libs/include"))
        }
    }
    
    jvm()
    
    sourceSets {
        commonMain.dependencies {
            // put your Multiplatform dependencies here
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(project(":mpv:androidNative"))
        }
        jvmMain.dependencies {
            implementation(libs.jna)
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "kotlinMultiplatform") {
            artifact(iosNativeLibrariesZip)
            desktopNativeLibrariesZips.forEach { artifact(it) }
        }
    }
}
