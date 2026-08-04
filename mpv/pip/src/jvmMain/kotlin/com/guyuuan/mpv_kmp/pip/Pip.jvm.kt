package com.guyuuan.mpv_kmp.pip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.guyuuan.mpv_kmp.config.MpvConfig
import com.guyuuan.mpv_kmp.rememberMpvPlayer
import com.guyuuan.mpv_kmp.util.PlatformLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Composable
actual fun rememberPipMpvPlayer(config: MpvConfig): PipMpvPlayer {
    val resolvedConfig = remember(config) {
        JvmPipPlaybackConfigurationOwner.resolveMpvConfig(config)
    }
    val player = rememberMpvPlayer(resolvedConfig)
    val pipController = remember { UnsupportedDesktopPictureInPictureController() }
    return remember(player, pipController) {
        PipMpvPlayer(
            delegate = player,
            pictureInPicture = pipController
        )
    }
}

private object JvmPipPlaybackConfigurationOwner {
    private val lock = PlatformLock()
    private var configuration: PipPlaybackConfiguration? = null

    fun resolveMpvConfig(fallback: MpvConfig): MpvConfig =
        lock.withLock { configuration?.mpvConfig ?: fallback }

    fun configure(configuration: PipPlaybackConfiguration) {
        lock.withLock {
            check(this.configuration == null) {
                "Desktop PiP playback is already configured"
            }
            this.configuration = configuration
        }
    }
}

internal actual fun installPlatformPipPlaybackConfiguration(
    configuration: PipPlaybackConfiguration
) {
    JvmPipPlaybackConfigurationOwner.configure(configuration)
}

private class UnsupportedDesktopPictureInPictureController : PictureInPictureController {
    private val mutableAvailability = MutableStateFlow(
        PictureInPictureAvailability.UnsupportedPlatform
    )
    override val availability: StateFlow<PictureInPictureAvailability> =
        mutableAvailability.asStateFlow()

    private val mutableState = MutableStateFlow(PictureInPictureState.Inactive)
    override val state: StateFlow<PictureInPictureState> = mutableState.asStateFlow()

    override fun setEligible(eligible: Boolean) = Unit
    override fun setAspectRatio(width: Int, height: Int) = Unit
    override fun requestStart(): Boolean = false
    override fun requestStop(): Boolean = false
    override fun close() = Unit
}
