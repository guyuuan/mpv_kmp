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

tasks.processResources {
    from(layout.projectDirectory.dir("../mpv/src/iosMain/nativeLibs")) {
        include("**/lib*.dylib")
        into("com/guyuuan/mpv_kmp/nativeLibs")
    }
    from(layout.projectDirectory.dir("../mpv/src/jvmMain/resources")) {
        include("*/*")
        into("com/guyuuan/mpv_kmp/desktopNativeLibs")
    }
}
