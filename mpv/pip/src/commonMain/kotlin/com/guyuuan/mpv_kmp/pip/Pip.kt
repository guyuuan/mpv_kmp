package com.guyuuan.mpv_kmp.pip

import androidx.compose.runtime.Composable
import com.guyuuan.mpv_kmp.MpvPlayer
import com.guyuuan.mpv_kmp.MpvPlayerCapability
import com.guyuuan.mpv_kmp.MpvVideoOutput
import kotlinx.coroutines.flow.StateFlow

enum class PictureInPictureAvailability {
    Available,
    UnsupportedPlatform,
    UnsupportedVideoOutput,
    MissingHostCapability,
    MissingSystemFeature
}

enum class PictureInPictureState {
    Inactive,
    Active
}

/**
 * Platform PiP contract. It intentionally contains no libmpv reference: the platform video-output
 * bridge and the playback coordinator remain the sole owners of decoder and renderer resources.
 */
interface PictureInPictureController {
    val availability: StateFlow<PictureInPictureAvailability>
    val state: StateFlow<PictureInPictureState>

    fun setEligible(eligible: Boolean)
    fun setAspectRatio(width: Int, height: Int)
    fun requestStart(): Boolean
    fun requestStop(): Boolean
    fun close()
}

class PipMpvPlayer internal constructor(
    private val delegate: MpvPlayer,
    val pictureInPicture: PictureInPictureController,
    override val videoOutput: MpvVideoOutput = delegate.videoOutput,
    private val release: () -> Unit = { pictureInPicture.close() }
) : MpvPlayer by delegate {
    override val capabilities: Set<MpvPlayerCapability> =
        if (pictureInPicture.availability.value == PictureInPictureAvailability.Available) {
            delegate.capabilities + MpvPlayerCapability.PictureInPicture
        } else {
            delegate.capabilities
        }

    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        release()
    }
}

@Composable
expect fun rememberPipMpvPlayer(): PipMpvPlayer
