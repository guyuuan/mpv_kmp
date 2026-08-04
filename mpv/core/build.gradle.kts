import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

abstract class PrepareAndroidJniLibs : Sync() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        into(outputDirectory)
    }
}

val androidNativeTargetNames = setOf("androidNativeArm64", "androidNativeX64")
val nativeLegalResourcesDir = layout.projectDirectory.dir("src/commonMain/resources/META-INF")
val androidNativeBridgeDir = layout.projectDirectory.dir("src/androidNativeBridgeMain/cpp")
val androidNativeBridgeHeader = androidNativeBridgeDir.file("mpv_bridge.h").asFile
val androidNativeBridgeSource = androidNativeBridgeDir.file("mpv_jni.cpp").asFile
val androidNdkVersion = "29.0.14206865"
val androidNdkHostTag = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "darwin-x86_64"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x86_64"
    else -> "linux-x86_64"
}
val androidComponents = extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>()
val androidNdkDirectory = androidComponents.sdkComponents.sdkDirectory.map {
    it.dir("ndk/$androidNdkVersion")
}

fun KotlinNativeTarget.configureAndroidJniBridge() {
    compilations.getByName("main").cinterops.create("mpvBridge") {
        headers(androidNativeBridgeHeader)
        includeDirs(androidNativeBridgeDir)
        packageName("com.guyuuan.kmp.mpv.bridge")
        // Kotlin 2.4 requires indirect calls when C/C++ sources are compiled by cinterop.
        extraOpts(
            "-Xccall-mode",
            "indirect",
            "-Xcompile-source",
            androidNativeBridgeSource.absolutePath,
            "-Xsource-compiler-option",
            "-std=c++17",
            "-Xsource-compiler-option",
            "-fno-exceptions",
            "-Xsource-compiler-option",
            "-fno-rtti",
        )
    }
    binaries.sharedLib(listOf(NativeBuildType.RELEASE)) {
        baseName = "mpvbridge"
        linkerOpts("-ldl", "-landroid", "-llog")
    }
}

val iosNativeLibrariesDir = layout.projectDirectory.dir("src/iosMain/nativeLibs/iphoneos")
val iosNativeLibrariesZip by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Packages iOS mpv dynamic libraries for app embedding."
    archiveClassifier.set("ios-arm64-native-libs")
    from(iosNativeLibrariesDir) {
        include("lib*.dylib")
    }
    from(nativeLegalResourcesDir) {
        into("META-INF")
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
        from(nativeLegalResourcesDir) {
            into("META-INF")
        }
    }
}

mavenPublishing {
    pom {
        licenses {
            license {
                name.set("GNU Lesser General Public License, version 2.1 or later (bundled native libraries)")
                url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html")
                distribution.set("repo")
            }
            license {
                name.set("GNU Lesser General Public License, version 3 or later (bundled native libraries)")
                url.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                distribution.set("repo")
            }
        }
    }
}

kotlin {
    android {
        namespace = "com.guyuuan.kmp.mpv.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

    }
    val androidNativeArm64Target = androidNativeArm64().apply {
        configureAndroidJniBridge()
    }
    val androidNativeX64Target = androidNativeX64().apply {
        configureAndroidJniBridge()
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyHierarchyTemplate {
        withSourceSetTree(KotlinSourceSetTree.main, KotlinSourceSetTree.test)
        common {
            withCompilations { compilation ->
                compilation.target.name !in androidNativeTargetNames
            }
            group("native") {
                group("apple") {
                    group("ios") {
                        withIos()
                    }
                }
            }
        }
        group("androidNativeBridge") {
            withAndroidNative()
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
            implementation(libs.kermit)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain {
            // Native libraries are published as per-platform archives below. Keeping
            // them out of JVM resources avoids processing every OS/CPU build locally
            // and prevents the main artifact from carrying all platforms.
            desktopNativePlatforms.forEach { platform ->
                resources.exclude("$platform/**")
            }
            dependencies {
                implementation(libs.jna)
                implementation(libs.jogl.all.main)
                implementation(libs.gluegen.rt.main)
            }
        }
    }

    val prepareAndroidJniLibs by tasks.registering(PrepareAndroidJniLibs::class) {
        group = "build"
        description = "Collects Kotlin/Native JNI bridges and the Android C++ runtime for the AAR."
        outputDirectory.set(layout.buildDirectory.dir("generated/androidMain/jniLibs"))

        val arm64Bridge = androidNativeArm64Target.binaries.getSharedLib(NativeBuildType.RELEASE)
        val x64Bridge = androidNativeX64Target.binaries.getSharedLib(NativeBuildType.RELEASE)
        dependsOn(arm64Bridge.linkTaskProvider, x64Bridge.linkTaskProvider)
        from(arm64Bridge.outputFile) {
            into("arm64-v8a")
        }
        from(x64Bridge.outputFile) {
            into("x86_64")
        }

        val ndkSysroot = androidNdkDirectory.map {
            it.dir("toolchains/llvm/prebuilt/$androidNdkHostTag/sysroot/usr/lib")
        }
        from(ndkSysroot.map { it.file("aarch64-linux-android/libc++_shared.so") }) {
            into("arm64-v8a")
        }
        from(ndkSysroot.map { it.file("x86_64-linux-android/libc++_shared.so") }) {
            into("x86_64")
        }
    }

    androidComponents.onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareAndroidJniLibs) {
            it.outputDirectory
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
