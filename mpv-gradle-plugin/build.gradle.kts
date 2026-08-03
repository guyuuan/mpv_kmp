import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.guyuuan.mpv_kmp"
version = "0.1.0"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

gradlePlugin {
    plugins {
        create("mpvKmp") {
            id = "com.guyuuan.mpv-kmp"
            implementationClass = "com.guyuuan.mpv_kmp.gradle.MpvKmpPlugin"
            displayName = "mpv-kmp Gradle integration"
            description = "Embeds mpv native libraries for Kotlin Multiplatform iOS and Compose Desktop apps."
        }
    }
}

val nativeLibrariesRoot = layout.projectDirectory.dir("../mpv/core")
val iosNativeLibrariesZip by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Packages iOS mpv dynamic libraries without embedding them in the plugin JAR."
    archiveClassifier.set("ios-native-libs")
    from(nativeLibrariesRoot.dir("src/iosMain/nativeLibs")) {
        include("**/lib*.dylib")
    }
}

val desktopNativeResourcesDir = nativeLibrariesRoot.dir("src/jvmMain/resources").asFile
val desktopNativePlatforms = desktopNativeResourcesDir
    .listFiles()
    ?.filter { candidate -> candidate.isDirectory && candidate.listFiles()?.any { it.isFile } == true }
    ?.map { it.name }
    ?.sorted()
    ?: emptyList()

fun String.toNativeTaskSuffix(): String =
    split("-").joinToString("") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

val desktopNativeLibrariesZips = desktopNativePlatforms.map { platform ->
    tasks.register<Zip>("${platform.toNativeTaskSuffix()}MpvKmpPluginNativeLibrariesZip") {
        group = "publishing"
        description = "Packages mpv desktop native libraries for $platform."
        archiveClassifier.set("jvm-$platform-native-libs")
        from(desktopNativeResourcesDir.resolve(platform)) {
            include("*")
        }
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor-Id" to project.group
        )
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifact(iosNativeLibrariesZip)
            desktopNativeLibrariesZips.forEach { artifact(it) }
        }
    }
}
