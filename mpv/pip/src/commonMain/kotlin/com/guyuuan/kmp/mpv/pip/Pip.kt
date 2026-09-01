package com.guyuuan.kmp.mpv.pip

import androidx.compose.runtime.Composable
import com.guyuuan.kmp.mpv.MpvPlayer
import com.guyuuan.kmp.mpv.MpvPlayerCapability
import com.guyuuan.kmp.mpv.MpvPlaylistController
import com.guyuuan.kmp.mpv.MpvPlayerSnapshot
import com.guyuuan.kmp.mpv.MpvPlayerState
import com.guyuuan.kmp.mpv.MpvVideoOutput
import com.guyuuan.kmp.mpv.config.MpvConfig
import com.guyuuan.kmp.mpv.data.MpvAudioTrack
import com.guyuuan.kmp.mpv.data.MpvDecoderInfo
import com.guyuuan.kmp.mpv.data.MpvSubtitleTrack
import com.guyuuan.kmp.mpv.service.MediaCommand
import com.guyuuan.kmp.mpv.service.MediaCommandType
import com.guyuuan.kmp.mpv.service.PlaybackCoordinator
import com.guyuuan.kmp.mpv.service.PlaybackMediaType
import com.guyuuan.kmp.mpv.service.PlaybackMetadata
import com.guyuuan.kmp.mpv.service.PlaybackNavigationHandler
import com.guyuuan.kmp.mpv.service.PlaybackSnapshot
import com.guyuuan.kmp.mpv.service.PlaybackStatus
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
    Active,

    /** The platform has started preparing or animating the transition into PiP. */
    Entering
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
    private var navigationHandler: PlaybackNavigationHandler? = null

    /**
     * Requests an immediate, user-initiated transition into picture-in-picture.
     *
     * A `true` result means the platform accepted or scheduled the request. Observe
     * [PictureInPictureController.state] through [pictureInPicture] for the completed transition.
     */
    fun enterPictureInPicture(): Boolean =
        if (closed) false else pictureInPicture.requestStart()

    /**
     * Loads a media item together with metadata published to platform media controls.
     *
     * A URI [PlaybackMetadata.artwork] is resolved by the application-level artwork loader
     * configured through [configurePipPlayback].
     */
    fun load(metadata: PlaybackMetadata): Int =
        (delegate as? PlaybackMetadataController)?.load(metadata)
            ?: delegate.load(metadata.uri)

    fun addToPlayList(
        metadata: List<PlaybackMetadata>,
        currentIndex: Int = 0,
        playWhenReady: Boolean = true
    ): Int? = when (val player = delegate) {
        is PlaybackMetadataController -> player.addToPlayList(
            metadata,
            currentIndex,
            playWhenReady
        )

        is MpvPlaylistController -> player.setQueue(
            metadata.map(PlaybackMetadata::uri),
            currentIndex,
            playWhenReady
        )

        else -> null
    }

    /** Inserts rich media metadata at [position], or appends it when [position] is null. */
    fun addToPlaylist(metadata: PlaybackMetadata, position: Int? = null): Int =
        (delegate as? PlaybackMetadataController)?.addToPlaylist(metadata, position)
            ?: delegate.addToPlaylist(metadata.uri, position)

    /** Replaces metadata for the currently playing item without reloading its media URI. */
    fun updateMetadata(metadata: PlaybackMetadata?) {
        (delegate as? PlaybackMetadataController)?.updateMetadata(metadata)
    }

    /** Adds a handler for system previous/next commands when libmpv playlist is not used. */
    fun addNavigationHandler(handler: PlaybackNavigationHandler): Boolean {
        if (closed) return false
        val added = (delegate as? PlaybackNavigationController)
            ?.addNavigationHandler(handler)
            ?: false
        if (added) navigationHandler = handler
        return added
    }

    /** Removes a handler previously added through this player. */
    fun removeNavigationHandler(handler: PlaybackNavigationHandler): Boolean {
        if (closed || navigationHandler !== handler) return false
        val removed = (delegate as? PlaybackNavigationController)
            ?.removeNavigationHandler(handler)
            ?: false
        navigationHandler = null
        return removed
    }

    fun close() {
        if (closed) return
        closed = true
        val navigationController = delegate as? PlaybackNavigationController
        navigationHandler?.let { navigationController?.removeNavigationHandler(it) }
        navigationHandler = null
        release()
    }
}

internal interface PlaybackMetadataController {
    fun load(metadata: PlaybackMetadata): Int
    fun updateMetadata(metadata: PlaybackMetadata?)
    fun addToPlaylist(metadata: PlaybackMetadata, position: Int? = null): Int
    fun addToPlayList(
        metadata: List<PlaybackMetadata>,
        currentIndex: Int,
        playWhenReady: Boolean
    ): Int
}

internal interface PlaybackNavigationController {
    fun addNavigationHandler(handler: PlaybackNavigationHandler): Boolean
    fun removeNavigationHandler(handler: PlaybackNavigationHandler): Boolean
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
) : MpvPlayer, PlaybackMetadataController, PlaybackNavigationController {
    private var navigationHandler: PlaybackNavigationHandler? = null
    private val mutableSnapshot = MutableStateFlow(
        coordinator.snapshot.value.toMpvPlayerSnapshot(hasExternalNavigation = false)
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
                mutableSnapshot.value = snapshot.toMpvPlayerSnapshot(
                    hasExternalNavigation = navigationHandler != null
                )
                onSnapshot(snapshot)
            }
        }
    }

    override fun load(uri: String): Int {
        val title = uri.substringAfterLast('/').substringBefore('?').ifBlank { uri }
        return load(
            PlaybackMetadata(
                mediaId = uri,
                uri = uri,
                title = title,
                mediaType = PlaybackMediaType.Video
            )
        )
    }

    override fun load(metadata: PlaybackMetadata): Int = coordinator.load(metadata)

    override fun addToPlaylist(uri: String, position: Int?): Int = addToPlaylist(
        metadata = PlaybackMetadata(
            mediaId = uri,
            uri = uri,
            title = uri.substringAfterLast('/').substringBefore('?').ifBlank { uri },
            mediaType = PlaybackMediaType.Video
        ),
        position = position
    )

    override fun addToPlaylist(metadata: PlaybackMetadata, position: Int?): Int =
        coordinator.addToPlaylist(metadata, position)

    override fun addToPlayList(
        metadata: List<PlaybackMetadata>,
        currentIndex: Int,
        playWhenReady: Boolean
    ) = coordinator.setQueue(metadata, currentIndex, playWhenReady)

    override fun updateMetadata(metadata: PlaybackMetadata?) {
        coordinator.updateMetadata(metadata)
    }

    override fun addNavigationHandler(handler: PlaybackNavigationHandler): Boolean {
        val added = coordinator.addNavigationHandler(handler)
        if (added) {
            navigationHandler = handler
            refreshNavigationAvailability()
        }
        return added
    }

    override fun removeNavigationHandler(handler: PlaybackNavigationHandler): Boolean {
        if (navigationHandler !== handler) return false
        val removed = coordinator.removeNavigationHandler(handler)
        navigationHandler = null
        refreshNavigationAvailability()
        return removed
    }

    override fun play(): Int = coordinator.execute(MediaCommand.Play)
    override fun pause(): Int = coordinator.execute(MediaCommand.Pause)
    override fun stop(): Int = coordinator.execute(MediaCommand.Stop)
    override fun canPrevious(): Boolean = coordinator.snapshot.value.canGoPrevious(
        hasExternalNavigation = navigationHandler != null
    )

    override fun canNext(): Boolean = coordinator.snapshot.value.canGoNext(
        hasExternalNavigation = navigationHandler != null
    )

    override fun previous(): Boolean =
        canPrevious() && coordinator.execute(MediaCommand.Previous) >= 0

    override fun next(): Boolean = canNext() && coordinator.execute(MediaCommand.Next) >= 0

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
        navigationHandler?.let(coordinator::removeNavigationHandler)
        navigationHandler = null
        scope.cancel()
        mutableSnapshot.value = mutableSnapshot.value.copy(
            state = MpvPlayerState.Disposed,
            canPrevious = false,
            canNext = false
        )
    }

    private fun refreshNavigationAvailability() {
        mutableSnapshot.value = coordinator.snapshot.value.toMpvPlayerSnapshot(
            hasExternalNavigation = navigationHandler != null
        )
    }
}

internal fun PlaybackSnapshot.toMpvPlayerSnapshot(
    hasExternalNavigation: Boolean = false
): MpvPlayerSnapshot = MpvPlayerSnapshot(
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
    bufferingProgress = bufferingProgress,
    volume = volume,
    speed = speed,
    canPrevious = canGoPrevious(hasExternalNavigation),
    canNext = canGoNext(hasExternalNavigation)
)

private fun PlaybackSnapshot.canGoPrevious(hasExternalNavigation: Boolean): Boolean =
    MediaCommandType.Previous in availableCommands && (
        hasExternalNavigation || queueIndex?.let { it > 0 } == true
    )

private fun PlaybackSnapshot.canGoNext(hasExternalNavigation: Boolean): Boolean =
    MediaCommandType.Next in availableCommands && (
        hasExternalNavigation || queueIndex?.let { it < queueSize - 1 } == true
    )

@Composable
expect fun rememberPipMpvPlayer(config: MpvConfig = MpvConfig()): PipMpvPlayer

private const val MILLIS_PER_SECOND = 1_000.0
private const val MAX_MPV_VOLUME = 100f
