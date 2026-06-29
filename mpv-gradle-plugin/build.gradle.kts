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
            description = "Links and embeds mpv iOS dynamic libraries for Kotlin Multiplatform apps."
        }
    }
}

tasks.processResources {
    from(layout.projectDirectory.dir("../mpv/src/iosMain/nativeLibs")) {
        include("**/lib*.dylib")
        into("com/guyuuan/mpv_kmp/nativeLibs")
    }
}
