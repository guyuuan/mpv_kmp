package com.guyuuan.mpv_kmp.pip

import androidx.compose.runtime.Composable
import com.guyuuan.mpv_kmp.MpvPlayer
import com.guyuuan.mpv_kmp.MpvPlayerCapability
import com.guyuuan.mpv_kmp.MpvPlayerSnapshot
import com.guyuuan.mpv_kmp.MpvPlayerState
import com.guyuuan.mpv_kmp.MpvVideoOutput
import com.guyuuan.mpv_kmp.data.MpvAudioTrack
import com.guyuuan.mpv_kmp.data.MpvDecoderInfo
import com.guyuuan.mpv_kmp.data.MpvSubtitleTrack
import com.guyuuan.mpv_kmp.service.MediaCommand
import com.guyuuan.mpv_kmp.service.PlaybackCoordinator
import com.guyuuan.mpv_kmp.service.PlaybackMediaType
import com.guyuuan.mpv_kmp.service.PlaybackMetadata
import com.guyuuan.mpv_kmp.service.PlaybackSnapshot
import com.guyuuan.mpv_kmp.service.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

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

/**
 * Shared PiP player facade backed by the application-level [PlaybackCoordinator].
 *
 * Closing this facade only disconnects its snapshot observer. The platform playback owner keeps
 * the coordinator and its single libmpv instance alive across Compose screen lifecycles.
 */
internal class PlaybackCoordinatorMpvPlayer(
    private val coordinator: PlaybackCoordinator,
    override val videoOutput: MpvVideoOutput,
    private val scope: CoroutineScope,
    private val onSnapshot: (PlaybackSnapshot) -> Unit = {}
) : MpvPlayer {
    private val mutableSnapshot = MutableStateFlow(
        coordinator.snapshot.value.toMpvPlayerSnapshot()
    )
    override val snapshot: StateFlow<MpvPlayerSnapshot> = mutableSnapshot.asStateFlow()

    override val capabilities: Set<MpvPlayerCapability> = setOf(
        MpvPlayerCapability.TrackSelection,
        MpvPlayerCapability.ExternalSubtitle
    )
    override val decoderInfoFlow: Flow<MpvDecoderInfo> = emptyFlow()
    private var closed = false

    init {
        scope.launch {
            coordinator.snapshot.collect { snapshot ->
                mutableSnapshot.value = snapshot.toMpvPlayerSnapshot()
                onSnapshot(snapshot)
            }
        }
    }

    override fun load(uri: String): Int {
        val title = uri.substringAfterLast('/').substringBefore('?').ifBlank { uri }
        return coordinator.load(
            PlaybackMetadata(
                mediaId = uri,
                uri = uri,
                title = title,
                mediaType = PlaybackMediaType.Video
            )
        )
    }

    override fun play(): Int = coordinator.execute(MediaCommand.Play)
    override fun pause(): Int = coordinator.execute(MediaCommand.Pause)
    override fun stop(): Int = coordinator.execute(MediaCommand.Stop)

    override fun seek(positionSeconds: Double): Int = coordinator.execute(
        MediaCommand.SeekTo(
            (positionSeconds.coerceAtLeast(0.0) * MILLIS_PER_SECOND).toLong()
        )
    )

    override fun setVolume(volume: Double): Int = coordinator.execute(
        MediaCommand.SetVolume(volume.toFloat().coerceIn(0f, MAX_MPV_VOLUME))
    )

    override fun setSpeed(speed: Float): Int =
        coordinator.execute(MediaCommand.SetSpeed(speed))

    override fun getSubtitleList(): List<MpvSubtitleTrack> =
        coordinator.player.getSubtitleList()

    override fun setSubtitle(id: Int?): Int = coordinator.player.setSubtitle(id)

    override fun getAudioTrackList(): List<MpvAudioTrack> =
        coordinator.player.getAudioTrackList()

    override fun setAudioTrack(id: Int?): Int = coordinator.player.setAudioTrack(id)

    override fun addExternalSubtitle(uri: String): Int =
        coordinator.player.addExternalSubtitle(uri)

    override fun addExternalSubtitleFile(path: String): Int =
        coordinator.player.addExternalSubtitleFile(path)

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        mutableSnapshot.value = mutableSnapshot.value.copy(state = MpvPlayerState.Disposed)
    }
}

internal fun PlaybackSnapshot.toMpvPlayerSnapshot(): MpvPlayerSnapshot = MpvPlayerSnapshot(
    state = when (status) {
        PlaybackStatus.Idle -> MpvPlayerState.Idle
        PlaybackStatus.Loading -> MpvPlayerState.Loading
        PlaybackStatus.Playing -> MpvPlayerState.Playing
        PlaybackStatus.Paused -> MpvPlayerState.Paused
        PlaybackStatus.Stopped -> MpvPlayerState.Stopped
        PlaybackStatus.Ended -> MpvPlayerState.Ended
        PlaybackStatus.Error -> MpvPlayerState.Error
        PlaybackStatus.Disposed -> MpvPlayerState.Disposed
    },
    positionSeconds = positionMillis / MILLIS_PER_SECOND,
    durationSeconds = durationMillis / MILLIS_PER_SECOND,
    volume = volume,
    speed = speed
)

@Composable
expect fun rememberPipMpvPlayer(): PipMpvPlayer

private const val MILLIS_PER_SECOND = 1_000.0
private const val MAX_MPV_VOLUME = 100f
