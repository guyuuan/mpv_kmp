import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = providers.gradleProperty("GROUP").getOrElse("com.guyuuan.kmp.mpv")
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.1")

dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation(gradleTestKit())
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
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
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
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
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor-Id" to project.group
        )
    }
}
