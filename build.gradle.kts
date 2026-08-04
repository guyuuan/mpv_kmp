import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidLint) apply false
    alias(libs.plugins.mavenPublish) apply false
}

subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            pom {
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
            }
        }
    }
}

buildscript {
    dependencies {
        // For KGP
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}
