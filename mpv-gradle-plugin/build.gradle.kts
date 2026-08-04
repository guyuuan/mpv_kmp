import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = providers.gradleProperty("GROUP").getOrElse("com.guyuuan.kmp.mpv")
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0-SNAPSHOT")

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
            id = "com.guyuuan.kmp.mpv"
            implementationClass = "com.guyuuan.kmp.mpv.gradle.MpvKmpPlugin"
            displayName = "mpv-kmp Gradle integration"
            description = "Embeds mpv native libraries for Kotlin Multiplatform iOS and Compose Desktop apps."
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("mpv-kmp Gradle plugin")
        description.set("Gradle integration for mpv-kmp native libraries on iOS and Desktop.")
        inceptionYear.set("2026")
        url.set("https://github.com/guyuuan/mpv_kmp")
        licenses {
            license {
                name.set("The Apache License, Version 2.0 (Gradle plugin code)")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
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
        developers {
            developer {
                id.set("guyuuan")
                name.set("guyuuan")
                email.set("guyuuan@users.noreply.github.com")
                url.set("https://github.com/guyuuan")
                organization.set("guyuuan")
                organizationUrl.set("https://github.com/guyuuan")
            }
        }
        scm {
            url.set("https://github.com/guyuuan/mpv_kmp")
            connection.set("scm:git:https://github.com/guyuuan/mpv_kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/guyuuan/mpv_kmp.git")
        }
    }
}

val nativeLibrariesRoot = layout.projectDirectory.dir("../mpv/core")
val nativeLegalResourcesDir = nativeLibrariesRoot.dir("src/commonMain/resources/META-INF")
val iosNativeLibrariesZip by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Packages iOS mpv dynamic libraries without embedding them in the plugin JAR."
    archiveClassifier.set("ios-native-libs")
    from(nativeLibrariesRoot.dir("src/iosMain/nativeLibs")) {
        include("**/lib*.dylib")
    }
    from(nativeLegalResourcesDir) {
        into("META-INF")
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
        from(nativeLegalResourcesDir) {
            into("META-INF")
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
