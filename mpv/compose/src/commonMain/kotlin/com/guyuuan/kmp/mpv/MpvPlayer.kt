package com.guyuuan.kmp.mpv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.guyuuan.kmp.mpv.config.MpvConfig
import com.guyuuan.kmp.mpv.data.MpvAudioTrack
import com.guyuuan.kmp.mpv.data.MpvDecoderInfo
import com.guyuuan.kmp.mpv.data.MpvEvent
import com.guyuuan.kmp.mpv.data.MpvSubtitleTrack
import com.guyuuan.kmp.mpv.props.MpvAudioProperties
import com.guyuuan.kmp.mpv.props.MpvDecoderProperties
import com.guyuuan.kmp.mpv.props.MpvPlaybackProperties
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

enum class MpvPlayerState {
    Idle,
    Loading,
    Playing,
    Paused,
    Stopped,
    Ended,
    Error,
    Disposed
}

val MpvPlayerState.isIdle: Boolean
    get() = this == MpvPlayerState.Idle ||
        this == MpvPlayerState.Stopped ||
        this == MpvPlayerState.Ended

enum class MpvPlayerCapability {
    TrackSelection,
    ExternalSubtitle,
    DecoderInfo,
    PictureInPicture
}

data class MpvPlayerSnapshot(
    val state: MpvPlayerState = MpvPlayerState.Idle,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val volume: Float = 0f,
    val speed: Float = 1f,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false
) {
    val isPaused: Boolean
        get() = state == MpvPlayerState.Paused

    val isLoading: Boolean
        get() = state == MpvPlayerState.Loading

    val isPlaying: Boolean
        get() = state == MpvPlayerState.Playing
}

/** Platform-neutral marker consumed by the platform actual of [MpvComposeView]. */
interface MpvVideoOutput

/** Lifecycle state exposed by video outputs that can report when their native target is ready. */
enum class MpvVideoOutputState {
    Detached,
    Attached
}

/** Optional capability used when loading must wait for a platform rendering target. */
interface MpvVideoOutputReadiness : MpvVideoOutput {
    val videoOutputState: StateFlow<MpvVideoOutputState>
}

/**
 * High-level player contract shared by local and platform-session-backed players.
 *
 * Ownership is deliberately not part of this interface. A UI may release its connection or video
 * output, but only the owner that created the underlying libmpv instance may terminate it.
 */
@Stable
interface MpvPlayer {
    val snapshot: StateFlow<MpvPlayerSnapshot>
    val capabilities: Set<MpvPlayerCapability>
    val videoOutput: MpvVideoOutput
    val decoderInfoFlow: Flow<MpvDecoderInfo>

    fun load(uri: String): Int
    /** Inserts [uri] at [position], or appends it when [position] is null. */
    fun addToPlaylist(uri: String, position: Int? = null): Int = UNSUPPORTED_MPV_COMMAND
    fun play(): Int
    fun pause(): Int
    fun stop(): Int
    /** Returns whether [previous] can switch to another playlist item. */
    fun canPrevious(): Boolean = snapshot.value.canPrevious
    /** Returns whether [next] can switch to another playlist item. */
    fun canNext(): Boolean = snapshot.value.canNext
    fun previous(): Boolean = false
    fun next(): Boolean = false
    fun seek(positionSeconds: Double): Int
    fun setVolume(volume: Double): Int
    fun setSpeed(speed: Float): Int

    fun getSubtitleList(): List<MpvSubtitleTrack> = emptyList()
    fun setSubtitle(id: Int?): Int = UNSUPPORTED_MPV_COMMAND
    fun setSubtitle(subtitle: MpvSubtitleTrack): Int = setSubtitle(subtitle.id)

    fun getAudioTrackList(): List<MpvAudioTrack> = emptyList()
    fun setAudioTrack(id: Int?): Int = UNSUPPORTED_MPV_COMMAND
    fun setAudioTrack(audioTrack: MpvAudioTrack): Int = setAudioTrack(audioTrack.id)

    fun addExternalSubtitle(uri: String): Int = UNSUPPORTED_MPV_COMMAND
    fun addExternalSubtitleFile(path: String): Int = UNSUPPORTED_MPV_COMMAND

    fun togglePause(): Int =
        if (snapshot.value.isPaused) play() else pause()
}

/** Optional full-queue replacement implemented by players that own a mutable playlist. */
interface MpvPlaylistController {
    fun setQueue(
        uris: List<String>,
        currentIndex: Int = 0,
        playWhenReady: Boolean = true
    ): Int
}

/**
 * Direct libmpv output used by the local player. Platform renderers keep this internal so remote
 * player implementations never expose an application-owned libmpv instance to UI code.
 */
internal class LocalMpvVideoOutput(
    val mpv: Mpv
) : MpvVideoOutput


@Composable
fun rememberMpvPlayer(
    config: MpvConfig = MpvConfig(),
    scope: CoroutineScope = rememberCoroutineScope()
): MpvPlayer {
    val mpv = remember { Mpv(config) }
    val player = remember(mpv, scope) {
        LocalMpvPlayer(
            mpv = mpv,
            scope = scope,
            releaseMpv = { Mpv.release() }
        )
    }

    DisposableEffect(player) {
        player.setup()
        onDispose {
            player.close()
        }
    }
    return player
}

/** Local, composition-owned implementation for applications that don't need background PiP. */
@Stable
class LocalMpvPlayer(
    val mpv: Mpv,
    private val scope: CoroutineScope,
    private val releaseMpv: () -> Unit = mpv::terminate
) : MpvPlayer, MpvPlaylistController {
    private enum class PendingQueueLoadPhase {
        AwaitingStart,
        Loading
    }

    private data class PendingQueueLoad(
        var playWhenReady: Boolean,
        var phase: PendingQueueLoadPhase = PendingQueueLoadPhase.AwaitingStart
    )

    private val mutableSnapshot = MutableStateFlow(MpvPlayerSnapshot())
    override val snapshot: StateFlow<MpvPlayerSnapshot> = mutableSnapshot.asStateFlow()

    override val capabilities: Set<MpvPlayerCapability> = setOf(
        MpvPlayerCapability.TrackSelection,
        MpvPlayerCapability.ExternalSubtitle,
        MpvPlayerCapability.DecoderInfo
    )

    override val videoOutput: MpvVideoOutput = LocalMpvVideoOutput(mpv)

    override val decoderInfoFlow: Flow<MpvDecoderInfo> = callbackFlow {
        MpvDecoderProperties.ALL.forEach(mpv::observeProperty)
        val listener: MpvEventListener = { event ->
            if (event.name in MpvDecoderProperties.ALL) {
                trySend(mpv.getDecoderInfo())
            }
        }
        mpv.addEventListener(listener)
        trySend(mpv.getDecoderInfo())
        awaitClose {
            mpv.removeEventListener(listener)
            MpvDecoderProperties.ALL.forEach(mpv::removePropertyObservation)
        }
    }.shareIn(scope, started = SharingStarted.WhileSubscribed())

    private var hasActiveFile = false
    private var pauseProperty = false
    private var stopRequested = false
    private var pendingQueueLoad: PendingQueueLoad? = null
    private var playlistPosition: Int? = null
    private var playlistSize = 0
    private val eventChannel = Channel<MpvEvent>(Channel.UNLIMITED)
    private var eventJob: Job? = null
    private var started = false
    private var closed = false

    private val eventListener: (MpvEvent) -> Unit = { event ->
        eventChannel.trySend(event)
    }

    fun setup(): Boolean {
        if (started) return snapshot.value.state != MpvPlayerState.Error
        check(!closed) { "LocalMpvPlayer is already closed" }

        started = true
        if (!mpv.initialize()) {
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
            Logger.e(tag = "LocalMpvPlayer") { "initialize failed" }
            return false
        }

        mpv.setCoroutineScope(scope)
        eventJob = scope.launch {
            for (event in eventChannel) {
                handleEvent(event)
            }
        }
        mpv.addEventListener(eventListener)
        mpv.observeProperty(MpvAudioProperties.VOLUME)
        MpvPlaybackProperties.ALL.forEach(mpv::observeProperty)
        PLAYLIST_PROPERTIES.forEach(mpv::observeProperty)
        playlistPosition = mpv.getProperty(PLAYLIST_POSITION).toPlaylistIndex()
        playlistSize = mpv.getProperty(PLAYLIST_COUNT)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        publish(
            snapshot.value.copy(state = MpvPlayerState.Idle).withPlaylistNavigation(
                playlistPosition,
                playlistSize
            )
        )
        return true
    }

    private fun handleEvent(event: MpvEvent) {
        if (closed) return
        if (event.error < 0) {
            hasActiveFile = false
            stopRequested = false
            resetPendingQueueLoadIfLoading()
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
            return
        }

        var next = snapshot.value
        when (event.type) {
            MpvEventType.PropertyChange -> {
                next = when (event.name) {
                    MpvPlaybackProperties.PAUSE -> {
                        pauseProperty = event.value.toMpvBoolean()
                        next.copy(
                            state = if (hasActiveFile) {
                                if (pauseProperty) {
                                    MpvPlayerState.Paused
                                } else {
                                    MpvPlayerState.Playing
                                }
                            } else {
                                next.state
                            }
                        )
                    }

                    MpvPlaybackProperties.SPEED -> next.copy(
                        speed = event.value?.toFloatOrNull() ?: 1f
                    )

                    MpvPlaybackProperties.TIME_POSITION -> next.copy(
                        positionSeconds = event.value?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    )

                    MpvPlaybackProperties.DURATION -> next.copy(
                        durationSeconds = event.value?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    )

                    MpvAudioProperties.VOLUME -> next.copy(
                        volume = event.value?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
                    )

                    PLAYLIST_POSITION -> {
                        playlistPosition = event.value.toPlaylistIndex()
                        next.withPlaylistNavigation(playlistPosition, playlistSize)
                    }

                    PLAYLIST_COUNT -> {
                        playlistSize = event.value?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        next.withPlaylistNavigation(playlistPosition, playlistSize)
                    }

                    else -> next
                }
            }

            MpvEventType.Pause -> {
                pauseProperty = true
                if (hasActiveFile) next = next.copy(state = MpvPlayerState.Paused)
            }

            MpvEventType.Unpause -> {
                pauseProperty = false
                if (hasActiveFile) next = next.copy(state = MpvPlayerState.Playing)
            }

            MpvEventType.StartFile -> {
                hasActiveFile = false
                stopRequested = false
                pendingQueueLoad?.phase = PendingQueueLoadPhase.Loading
                next = next.copy(
                    state = MpvPlayerState.Loading,
                    positionSeconds = 0.0,
                    durationSeconds = 0.0
                )
            }

            MpvEventType.FileLoaded -> {
                hasActiveFile = true
                val pendingLoad = pendingQueueLoad?.takeIf {
                    it.phase == PendingQueueLoadPhase.Loading
                }
                if (pendingLoad != null) {
                    pendingQueueLoad = null
                    if (pendingLoad.playWhenReady) {
                        val playResult = mpv.play()
                        if (playResult < 0) {
                            next = next.copy(state = MpvPlayerState.Error)
                        } else {
                            pauseProperty = false
                            next = next.copy(state = MpvPlayerState.Playing)
                        }
                    } else {
                        pauseProperty = true
                        next = next.copy(state = MpvPlayerState.Paused)
                    }
                } else {
                    next = next.copy(
                        state = if (pauseProperty) {
                            MpvPlayerState.Paused
                        } else {
                            MpvPlayerState.Playing
                        }
                    )
                }
            }

            MpvEventType.PlaybackRestart -> {
                hasActiveFile = true
                next = next.copy(
                    state = if (pauseProperty) {
                        MpvPlayerState.Paused
                    } else {
                        MpvPlayerState.Playing
                    }
                )
            }

            MpvEventType.EndFile -> {
                hasActiveFile = false
                resetPendingQueueLoadIfLoading()
                next = next.copy(
                    state = if (stopRequested) MpvPlayerState.Stopped else MpvPlayerState.Ended
                )
                stopRequested = false
            }

            MpvEventType.Idle -> {
                resetPendingQueueLoadIfLoading()
                if (!hasActiveFile && next.state == MpvPlayerState.Loading) {
                    next = next.copy(state = MpvPlayerState.Idle)
                }
            }

            MpvEventType.Shutdown -> {
                hasActiveFile = false
                stopRequested = false
                pendingQueueLoad = null
                playlistPosition = null
                playlistSize = 0
                next = next.copy(
                    state = MpvPlayerState.Disposed,
                    canPrevious = false,
                    canNext = false
                )
            }

            else -> Unit
        }
        if (next != snapshot.value) publish(next)
    }

    override fun load(uri: String): Int {
        ensureUsable()
        pendingQueueLoad = null
        val result = mpv.load(uri)
        if (result >= 0) {
            playlistPosition = 0
            playlistSize = 1
            hasActiveFile = false
            stopRequested = false
            publish(
                snapshot.value.copy(
                    state = MpvPlayerState.Loading,
                    positionSeconds = 0.0,
                    durationSeconds = 0.0,
                    canPrevious = false,
                    canNext = false
                )
            )
        } else {
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
        }
        return result
    }

    override fun setQueue(
        uris: List<String>,
        currentIndex: Int,
        playWhenReady: Boolean
    ): Int {
        ensureUsable()
        require(uris.isNotEmpty()) { "Playback queue must not be empty" }
        require(currentIndex in uris.indices) { "Current index must point to a queue item" }

        pendingQueueLoad = PendingQueueLoad(playWhenReady)
        var result = mpv.pause()
        if (result >= 0) pauseProperty = true
        if (result >= 0) result = mpv.playlistClear()
        if (result >= 0) result = mpv.load(uris[currentIndex])
        for (index in 0 until currentIndex) {
            if (result < 0) break
            result = mpv.addToPlaylist(uris[index], index)
        }
        for (index in currentIndex + 1 until uris.size) {
            if (result < 0) break
            result = mpv.addToPlaylist(uris[index])
        }
        if (result >= 0) {
            playlistPosition = currentIndex
            playlistSize = uris.size
            hasActiveFile = false
            stopRequested = false
            publish(
                snapshot.value.copy(
                    state = MpvPlayerState.Loading,
                    positionSeconds = 0.0,
                    durationSeconds = 0.0
                ).withPlaylistNavigation(playlistPosition, playlistSize)
            )
        } else {
            pendingQueueLoad = null
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
        }
        return result
    }

    override fun addToPlaylist(uri: String, position: Int?): Int {
        ensureUsable()
        require(position == null || position in 0..playlistSize) {
            "Playlist position must be between 0 and $playlistSize"
        }

        val result = mpv.addToPlaylist(uri, position)
        if (result >= 0) {
            val currentPosition = playlistPosition
            if (position != null && currentPosition != null && position <= currentPosition) {
                playlistPosition = currentPosition + 1
            }
            playlistSize += 1
            publish(snapshot.value.withPlaylistNavigation(playlistPosition, playlistSize))
        }
        return result
    }

    override fun play(): Int {
        ensureUsable()
        pendingQueueLoad?.playWhenReady = true
        val result = mpv.play()
        if (result >= 0) {
            pauseProperty = false
            if (hasActiveFile) publish(snapshot.value.copy(state = MpvPlayerState.Playing))
        } else {
            pendingQueueLoad = null
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
        }
        return result
    }

    override fun pause(): Int {
        ensureUsable()
        pendingQueueLoad?.playWhenReady = false
        val result = mpv.pause()
        if (result >= 0) {
            pauseProperty = true
            if (hasActiveFile) publish(snapshot.value.copy(state = MpvPlayerState.Paused))
        } else {
            pendingQueueLoad = null
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
        }
        return result
    }

    override fun stop(): Int {
        ensureUsable()
        pendingQueueLoad = null
        val result = mpv.stop()
        if (result >= 0) {
            hasActiveFile = false
            stopRequested = true
            publish(
                snapshot.value.copy(
                    state = MpvPlayerState.Stopped,
                    positionSeconds = 0.0
                )
            )
        } else {
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
        }
        return result
    }

    override fun previous(): Boolean = canPrevious() && mpv.playlistPrev() >= 0

    override fun next(): Boolean = canNext() && mpv.playlistNext() >= 0

    override fun seek(positionSeconds: Double): Int {
        ensureUsable()
        return mpv.seekTo(positionSeconds).also(::publishErrorIfNeeded)
    }

    override fun setVolume(volume: Double): Int {
        ensureUsable()
        return mpv.setVolume(volume).also(::publishErrorIfNeeded)
    }

    override fun setSpeed(speed: Float): Int {
        ensureUsable()
        return mpv.setSpeed(speed).also(::publishErrorIfNeeded)
    }

    override fun getSubtitleList(): List<MpvSubtitleTrack> {
        ensureUsable()
        return mpv.getSubtitleList()
    }

    override fun setSubtitle(id: Int?): Int {
        ensureUsable()
        return mpv.setSubtitle(id).also(::publishErrorIfNeeded)
    }

    override fun getAudioTrackList(): List<MpvAudioTrack> {
        ensureUsable()
        return mpv.getAudioTrackList()
    }

    override fun setAudioTrack(id: Int?): Int {
        ensureUsable()
        return mpv.setAudioTrack(id).also(::publishErrorIfNeeded)
    }

    override fun addExternalSubtitle(uri: String): Int {
        ensureUsable()
        return mpv.addExternalSubtitle(uri).also(::publishErrorIfNeeded)
    }

    override fun addExternalSubtitleFile(path: String): Int {
        ensureUsable()
        return mpv.addExternalSubtitleFile(path).also(::publishErrorIfNeeded)
    }

    fun close() {
        if (closed) return
        closed = true
        hasActiveFile = false
        stopRequested = false
        pendingQueueLoad = null
        if (started) {
            mpv.removeEventListener(eventListener)
            mpv.removePropertyObservation(MpvAudioProperties.VOLUME)
            MpvPlaybackProperties.ALL.forEach(mpv::removePropertyObservation)
            PLAYLIST_PROPERTIES.forEach(mpv::removePropertyObservation)
        }
        eventChannel.close()
        eventJob?.cancel()
        eventJob = null
        publish(
            snapshot.value.copy(
                state = MpvPlayerState.Disposed,
                canPrevious = false,
                canNext = false
            )
        )
        releaseMpv()
    }

    internal fun reportRenderError(message: String, cause: Throwable? = null) {
        Logger.e(throwable = cause, tag = "LocalMpvPlayer") { "render failed: $message" }
        publish(snapshot.value.copy(state = MpvPlayerState.Error))
    }

    private fun ensureUsable() {
        check(started) { "LocalMpvPlayer has not been started" }
        check(!closed) { "LocalMpvPlayer is already closed" }
    }

    private fun publishErrorIfNeeded(result: Int) {
        if (result < 0) publish(snapshot.value.copy(state = MpvPlayerState.Error))
    }

    private fun resetPendingQueueLoadIfLoading() {
        pendingQueueLoad?.takeIf {
            it.phase == PendingQueueLoadPhase.Loading
        }?.phase = PendingQueueLoadPhase.AwaitingStart
    }

    private fun publish(value: MpvPlayerSnapshot) {
        mutableSnapshot.value = value
    }
}

internal const val UNSUPPORTED_MPV_COMMAND: Int = -1

private fun String?.toMpvBoolean(): Boolean = this == "yes" || this == "true"

private fun String?.toPlaylistIndex(): Int? = this?.toDoubleOrNull()?.toInt()

private fun MpvPlayerSnapshot.withPlaylistNavigation(
    position: Int?,
    size: Int
): MpvPlayerSnapshot {
    val validPosition = position?.takeIf { it in 0 until size }
    return copy(
        canPrevious = validPosition != null && validPosition > 0,
        canNext = validPosition != null && validPosition < size - 1
    )
}

private const val PLAYLIST_POSITION = "playlist-pos"
private const val PLAYLIST_COUNT = "playlist/count"
private val PLAYLIST_PROPERTIES = listOf(PLAYLIST_POSITION, PLAYLIST_COUNT)
