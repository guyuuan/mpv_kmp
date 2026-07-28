import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    `maven-publish`
}

val generatedJvmResources = layout.buildDirectory.dir("generated/serviceResources/jvmMain")
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val windowsCppWinRtIncludeDirectory = providers
    .gradleProperty("mpvKmp.cppWinRtIncludeDir")
    .orNull
    ?.let(::file)
    ?: if (isWindowsHost) {
        val sdkIncludeRoot = System.getenv("WindowsSdkDir")
            ?.let(::file)
            ?.resolve("Include")
        val sdkVersion = System.getenv("WindowsSDKVersion")
            ?.trimEnd('\\', '/')
        val environmentCandidate = sdkVersion
            ?.let { sdkIncludeRoot?.resolve(it)?.resolve("cppwinrt") }
        environmentCandidate?.takeIf { it.resolve("winrt/base.h").isFile }
            ?: sdkIncludeRoot
                ?.listFiles()
                ?.filter { it.resolve("cppwinrt/winrt/base.h").isFile }
                ?.maxByOrNull { it.name }
                ?.resolve("cppwinrt")
    } else {
        null
    }
if (isWindowsHost) {
    checkNotNull(windowsCppWinRtIncludeDirectory) {
        "C++/WinRT headers were not found. Set mpvKmp.cppWinRtIncludeDir to the " +
            "Windows SDK cppwinrt directory."
    }
}
val macosMediaBridgeSource = layout.projectDirectory.file(
    "src/jvmMain/native/macos_media_bridge.m"
)
val macosMediaBridgeTasks = mapOf(
    "x86_64" to "darwin-x86-64",
    "arm64" to "darwin-aarch64"
).map { (architecture, resourceDirectory) ->
    tasks.register<Exec>(
        "compile${architecture.replace("_", "").replaceFirstChar(Char::uppercaseChar)}MacosMediaBridge"
    ) {
        val output = generatedJvmResources.map {
            it.file("$resourceDirectory/libmpv_kmp_service_media.dylib")
        }
        inputs.file(macosMediaBridgeSource)
        outputs.file(output)
        onlyIf { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
        doFirst { output.get().asFile.parentFile.mkdirs() }
        commandLine(
            "xcrun",
            "clang",
            "-fobjc-arc",
            "-fblocks",
            "-dynamiclib",
            "-arch",
            architecture,
            "-mmacosx-version-min=10.15",
            "-framework",
            "Foundation",
            "-framework",
            "AppKit",
            "-framework",
            "MediaPlayer",
            macosMediaBridgeSource.asFile.absolutePath,
            "-Wl,-install_name,@rpath/libmpv_kmp_service_media.dylib",
            "-o",
            output.get().asFile.absolutePath
        )
    }
}

val windowsSmtcBridgeTask = tasks.register<Exec>("compileWindowsX8664SmtcBridge") {
    val source = layout.projectDirectory.file("src/jvmMain/native/windows_smtc_bridge.cpp")
    val output = generatedJvmResources.map {
        it.file("windows-x86-64/mpv_kmp_service_media.dll")
    }
    val intermediates = layout.buildDirectory.dir("intermediates/serviceNative/windows-x86-64")
    inputs.file(source)
    outputs.file(output)
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    doFirst {
        output.get().asFile.parentFile.mkdirs()
        intermediates.get().asFile.mkdirs()
    }
    commandLine(
        "cl",
        "/nologo",
        "/std:c++20",
        "/EHsc",
        "/MD",
        "/LD",
        "/DWIN32_LEAN_AND_MEAN",
        windowsCppWinRtIncludeDirectory?.let { "/I${it.absolutePath}" }.orEmpty(),
        source.asFile.absolutePath,
        "/Fo:${intermediates.get().file("windows_smtc_bridge.obj").asFile.absolutePath}",
        "/Fe:${output.get().asFile.absolutePath}",
        "/link",
        "/IMPLIB:${intermediates.get().file("mpv_kmp_service_media.lib").asFile.absolutePath}",
        "windowsapp.lib",
        "runtimeobject.lib",
        "ole32.lib"
    )
}

kotlin {
    android {
        namespace = "com.guyuuan.mpv_kmp.service"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.mpv.core)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.media3.common)
            implementation(libs.androidx.media3.session)
            implementation(libs.kotlinx.coroutines.android)
        }
        jvmMain.dependencies {
            implementation(libs.jna)
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.native.unixsocket)
        }
    }
}

tasks.named<ProcessResources>("jvmProcessResources") {
    dependsOn(macosMediaBridgeTasks)
    dependsOn(windowsSmtcBridgeTask)
    from(generatedJvmResources)
}
