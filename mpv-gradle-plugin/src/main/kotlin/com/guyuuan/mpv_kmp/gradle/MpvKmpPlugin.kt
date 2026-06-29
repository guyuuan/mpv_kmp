package com.guyuuan.mpv_kmp.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import java.io.File
import javax.inject.Inject

open class MpvKmpExtension @Inject constructor(objects: ObjectFactory) {
    val forceDynamicFramework: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val embedForXcode: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val validateDylibs: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
}

class MpvKmpPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("mpvKmp", MpvKmpExtension::class.java)
        val extractIosDylibs = project.tasks.register(
            "extractMpvKmpIosDylibs",
            ExtractMpvKmpIosDylibsTask::class.java
        ) { task ->
            task.outputDirectory.set(project.layout.buildDirectory.dir("mpvKmp/iosNativeLibs"))
        }
        val embedIosDylibs = project.tasks.register(
            "embedMpvKmpIosDylibsForXcode",
            EmbedMpvKmpIosDylibsForXcodeTask::class.java
        ) { task ->
            task.nativeLibrariesDirectory.set(extractIosDylibs.flatMap { it.outputDirectory }.map { it.dir("iphoneos") })
            task.validateDylibs.set(extension.validateDylibs)
            task.dependsOn(extractIosDylibs)
            task.outputs.upToDateWhen { false }
        }
        val embedFramework = project.tasks.register(
            "mpvKmpEmbedAndSignAppleFrameworkForXcode",
            EmbedMpvKmpAppleFrameworkForXcodeTask::class.java
        ) { task ->
            task.outputs.upToDateWhen { false }
            task.finalizedBy(embedIosDylibs)
        }

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.targets.configureEach { target ->
                if (target !is KotlinNativeTarget || target.name != "iosArm64") return@configureEach
                target.binaries.withType(Framework::class.java).configureEach { framework ->
                    embedFramework.configure { task ->
                        task.frameworkName.convention(framework.baseName)
                    }
                    framework.linkerOpts(
                        "-L${extractIosDylibs.get().outputDirectory.get().dir("iphoneos").asFile.absolutePath}",
                        "-lmpv",
                        "-Wl,-rpath,@loader_path/.."
                    )
                    framework.linkTaskProvider.configure { task ->
                        task.dependsOn(extractIosDylibs)
                    }
                }
            }

            project.tasks.matching { it.name == "embedAndSignAppleFrameworkForXcode" }.configureEach { task ->
                task.finalizedBy(embedIosDylibs)
            }

            project.afterEvaluate {
                val iosArm64Target = kotlin.targets.findByName("iosArm64") as? KotlinNativeTarget
                if (iosArm64Target == null) {
                    project.logger.lifecycle("mpv-kmp iOS dylib integration is inactive because iosArm64 target is not configured.")
                    return@afterEvaluate
                }

                val frameworkBuildType = project.detectXcodeFrameworkBuildType()
                val linkTaskName = "link${frameworkBuildType.replaceFirstChar { it.uppercaseChar() }}FrameworkIosArm64"
                embedFramework.configure { task ->
                    task.linkedFrameworksDirectory.set(
                        project.layout.buildDirectory.dir("bin/iosArm64/${frameworkBuildType}Framework")
                    )
                    task.dependsOn(project.tasks.named(linkTaskName))
                }

                if (!extension.forceDynamicFramework.get()) return@afterEvaluate
                iosArm64Target.binaries.withType(Framework::class.java).configureEach { framework ->
                    framework.isStatic = false
                }
            }
        }
    }
}

@DisableCachingByDefault(because = "Uses Xcode build environment and code-signing identity.")
abstract class EmbedMpvKmpAppleFrameworkForXcodeTask : DefaultTask() {
    @get:InputDirectory
    abstract val linkedFrameworksDirectory: DirectoryProperty

    @get:Input
    abstract val frameworkName: Property<String>

    @TaskAction
    fun embed() {
        val platformName = System.getenv("PLATFORM_NAME")
        if (platformName != "iphoneos") {
            logger.lifecycle("Skipping mpv Kotlin framework embedding for PLATFORM_NAME=${platformName ?: "<unset>"}")
            return
        }

        val targetBuildDir = System.getenv("TARGET_BUILD_DIR")
        val frameworksFolderPath = System.getenv("FRAMEWORKS_FOLDER_PATH") ?: "Frameworks"
        require(!targetBuildDir.isNullOrBlank()) {
            "TARGET_BUILD_DIR is required. Run this task from the Xcode Kotlin framework build phase."
        }

        val name = frameworkName.get()
        val source = linkedFrameworksDirectory.get().asFile.resolve("$name.framework")
        require(source.isDirectory) { "Missing linked Kotlin framework: $source" }

        val frameworksDir = File(targetBuildDir, frameworksFolderPath)
        frameworksDir.mkdirs()
        val destination = frameworksDir.resolve(source.name)
        if (destination.exists()) {
            destination.deleteRecursively()
        }
        source.copyRecursively(destination, overwrite = true)
        val linkSearchDestination = File(targetBuildDir, source.name)
        if (linkSearchDestination.absolutePath != destination.absolutePath) {
            if (linkSearchDestination.exists()) {
                linkSearchDestination.deleteRecursively()
            }
            source.copyRecursively(linkSearchDestination, overwrite = true)
        }
        signIfNeeded(destination)
    }

    private fun signIfNeeded(framework: File) {
        if (System.getenv("CODE_SIGNING_ALLOWED") == "NO") return
        val identity = System.getenv("EXPANDED_CODE_SIGN_IDENTITY")
        if (identity.isNullOrBlank()) return
        runCommand(
            listOf(
                "codesign",
                "--force",
                "--sign",
                identity,
                "--timestamp=none",
                "--verbose=2",
                framework.absolutePath
            ),
            allowFailure = false
        )
    }

    private fun runCommand(command: List<String>, allowFailure: Boolean): String {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (!allowFailure && exitCode != 0) {
            error("Command failed (${command.joinToString(" ")}):\n$output")
        }
        return output
    }
}

@DisableCachingByDefault(because = "Copies binary resources bundled with the Gradle plugin.")
abstract class ExtractMpvKmpIosDylibsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val outputRoot = outputDirectory.get().asFile
        val outputDir = outputRoot.resolve("iphoneos")
        outputDir.mkdirs()

        NativeLibraries.names.forEach { name ->
            val resourcePath = "com/guyuuan/mpv_kmp/nativeLibs/iphoneos/$name"
            val target = outputDir.resolve(name)
            javaClass.classLoader.getResourceAsStream(resourcePath).use { input ->
                requireNotNull(input) { "Missing bundled mpv native library resource: $resourcePath" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setWritable(true)
        }
    }
}

@DisableCachingByDefault(because = "Uses Xcode build environment and code-signing identity.")
abstract class EmbedMpvKmpIosDylibsForXcodeTask : DefaultTask() {
    @get:InputDirectory
    abstract val nativeLibrariesDirectory: DirectoryProperty

    @get:Input
    abstract val validateDylibs: Property<Boolean>

    @Option(option = "force", description = "Run even if Xcode environment variables are missing.")
    @get:Input
    var force: Boolean = false

    @TaskAction
    fun embed() {
        val platformName = System.getenv("PLATFORM_NAME")
        if (platformName != "iphoneos" && !force) {
            logger.lifecycle("Skipping mpv iOS dylib embedding for PLATFORM_NAME=${platformName ?: "<unset>"}")
            return
        }

        val targetBuildDir = System.getenv("TARGET_BUILD_DIR")
        val frameworksFolderPath = System.getenv("FRAMEWORKS_FOLDER_PATH") ?: "Frameworks"
        require(!targetBuildDir.isNullOrBlank()) {
            "TARGET_BUILD_DIR is required. Run this task from the Xcode Kotlin framework build phase."
        }

        val sourceDir = nativeLibrariesDirectory.get().asFile
        require(sourceDir.isDirectory) { "Missing mpv native library directory: $sourceDir" }

        val frameworksDir = File(targetBuildDir, frameworksFolderPath)
        frameworksDir.mkdirs()

        NativeLibraries.names.forEach { name ->
            val source = sourceDir.resolve(name)
            require(source.isFile) { "Missing mpv native library: $source" }
            source.copyTo(frameworksDir.resolve(name), overwrite = true)
            frameworksDir.resolve(name).setWritable(true)
        }

        NativeLibraries.names.forEach { name ->
            val dylib = frameworksDir.resolve(name)
            if (validateDylibs.get()) {
                validateDylibReferences(dylib)
            }
            signIfNeeded(dylib)
        }
    }

    private fun validateDylibReferences(dylib: File) {
        val output = runCommand(listOf("otool", "-L", dylib.absolutePath), allowFailure = false)
        val invalidLines = output
            .lineSequence()
            .filter {
                it.contains("/usr/local/lib/lib") ||
                    Regex("""@rpath/lib.*\.[0-9]+.*\.dylib""").containsMatchIn(it)
            }
            .toList()
        require(invalidLines.isEmpty()) {
            buildString {
                appendLine("Invalid iOS dylib load path in ${dylib.name}:")
                invalidLines.forEach { appendLine(it) }
                append("Rebuild the iOS native libraries so install names use @rpath/libxxx.dylib.")
            }
        }
    }

    private fun signIfNeeded(dylib: File) {
        if (System.getenv("CODE_SIGNING_ALLOWED") == "NO") return
        val identity = System.getenv("EXPANDED_CODE_SIGN_IDENTITY")
        if (identity.isNullOrBlank()) return
        runCommand(
            listOf(
                "codesign",
                "--force",
                "--sign",
                identity,
                "--timestamp=none",
                "--verbose=2",
                dylib.absolutePath
            ),
            allowFailure = false
        )
    }

    private fun runCommand(command: List<String>, allowFailure: Boolean): String {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (!allowFailure && exitCode != 0) {
            error("Command failed (${command.joinToString(" ")}):\n$output")
        }
        return output
    }
}

private object NativeLibraries {
    val names = listOf(
        "libavcodec.dylib",
        "libavdevice.dylib",
        "libavfilter.dylib",
        "libavformat.dylib",
        "libavutil.dylib",
        "libmpv.dylib",
        "libswresample.dylib",
        "libswscale.dylib"
    )
}

private fun Project.detectXcodeFrameworkBuildType(): String {
    val explicit = providers
        .environmentVariable("KOTLIN_FRAMEWORK_BUILD_TYPE")
        .orNull
        ?.lowercase()
    if (explicit == "debug" || explicit == "release") return explicit

    val configuration = providers.environmentVariable("CONFIGURATION").orNull.orEmpty()
    return if (configuration.contains("release", ignoreCase = true)) "release" else "debug"
}
