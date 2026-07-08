import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("com.guyuuan.mpv-kmp")
}

dependencies {
    implementation(project(":example:shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.compose.material.iconsExtended)
}

compose.desktop {
    application {
        mainClass = "com.guyuuan.mpv_kmp.example.MainKt"
        jvmArgs(
            "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED"
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "mpv-kmp-example"
            packageVersion = "1.0.0"
        }
    }
}
