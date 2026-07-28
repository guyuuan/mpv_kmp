package com.guyuuan.mpv_kmp.service

data class DesktopMediaIntegrationConfig(
    val applicationId: String = "mpv_kmp",
    val identity: String = "mpv-kmp",
    val desktopEntry: String? = null,
    val supportedUriSchemes: List<String> = emptyList(),
    val supportedMimeTypes: List<String> = emptyList(),
    val nativeWindowHandle: Long? = null,
    val applicationController: DesktopApplicationController = NoopDesktopApplicationController
) {
    init {
        require(applicationId.matches(Regex("[A-Za-z0-9_]+"))) {
            "Desktop application ID may only contain letters, digits, and underscores"
        }
        require(identity.isNotBlank()) { "Desktop identity must not be blank" }
    }
}

interface DesktopApplicationController {
    val canRaise: Boolean
    val canQuit: Boolean
    val canOpenUri: Boolean

    fun raise()

    fun quit()

    fun openUri(uri: String)
}

object NoopDesktopApplicationController : DesktopApplicationController {
    override val canRaise = false
    override val canQuit = false
    override val canOpenUri = false

    override fun raise() = Unit

    override fun quit() = Unit

    override fun openUri(uri: String) = Unit
}

/** Selects the native media surface for the current desktop operating system. */
fun createDesktopMediaIntegration(
    config: DesktopMediaIntegrationConfig = DesktopMediaIntegrationConfig()
): PlatformMediaIntegration = when (currentDesktopOperatingSystem()) {
    DesktopOperatingSystem.Macos -> MacosNowPlayingMediaIntegration()
    DesktopOperatingSystem.Windows -> WindowsSmtcMediaIntegration(config.nativeWindowHandle)
    DesktopOperatingSystem.Linux -> LinuxMprisMediaIntegration(config)
    DesktopOperatingSystem.Unsupported -> NoopPlatformMediaIntegration
}

internal enum class DesktopOperatingSystem {
    Macos,
    Windows,
    Linux,
    Unsupported
}

internal fun currentDesktopOperatingSystem(
    osName: String = System.getProperty("os.name").orEmpty()
): DesktopOperatingSystem = when {
    osName.contains("mac", ignoreCase = true) -> DesktopOperatingSystem.Macos
    osName.contains("win", ignoreCase = true) -> DesktopOperatingSystem.Windows
    osName.contains("linux", ignoreCase = true) -> DesktopOperatingSystem.Linux
    else -> DesktopOperatingSystem.Unsupported
}
