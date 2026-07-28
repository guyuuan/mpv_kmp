package com.guyuuan.mpv_kmp.pip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.guyuuan.mpv_kmp.rememberMpvPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS capability placeholder.
 *
 * The current iOS renderer is GLKView-based, which AVKit cannot use as a custom PiP source.
 * A future implementation can provide an IosMpvVideoOutput backed by AVSampleBufferDisplayLayer
 * and route AVPictureInPictureController playback delegate commands through the same player.
 */
@Composable
actual fun rememberPipMpvPlayer(): PipMpvPlayer {
    val player = rememberMpvPlayer()
    val pipController = remember { UnsupportedIosPictureInPictureController() }
    return remember(player, pipController) {
        PipMpvPlayer(
            delegate = player,
            pictureInPicture = pipController
        )
    }
}

private class UnsupportedIosPictureInPictureController : PictureInPictureController {
    private val mutableAvailability = MutableStateFlow(
        PictureInPictureAvailability.UnsupportedVideoOutput
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
