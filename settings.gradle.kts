rootProject.name = "mpv_kmp"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("mpv-gradle-plugin")

    repositories {
        maven {
            name = "Central Portal Snapshots"
            setUrl ( "https://central.sonatype.com/repository/maven-snapshots/")

            // Only search this repository for the specific dependency
            content {
                includeGroupAndSubgroups( "com.guyuuan")
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            name = "Central Portal Snapshots"
            setUrl ( "https://central.sonatype.com/repository/maven-snapshots/")

            // Only search this repository for the specific dependency
            content {
                includeGroupAndSubgroups( "com.guyuuan")
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":mpv:core")
include(":mpv:compose")
include(":mpv:pip")
include(":mpv:service")
include(":mpv:loader-coil")
include(":example:shared")
include(":example:androidApp")
include(":example:desktopApp")
