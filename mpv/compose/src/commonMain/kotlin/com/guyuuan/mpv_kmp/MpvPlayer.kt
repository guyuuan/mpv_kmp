package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.guyuuan.mpv_kmp.data.MpvAudioTrack
import com.guyuuan.mpv_kmp.data.MpvDecoderInfo
import com.guyuuan.mpv_kmp.data.MpvEvent
import com.guyuuan.mpv_kmp.data.MpvSubtitleTrack
import com.guyuuan.mpv_kmp.props.MpvAudioProperties
import com.guyuuan.mpv_kmp.props.MpvDecoderProperties
import com.guyuuan.mpv_kmp.props.MpvPlaybackProperties
import kotlinx.coroutines.CoroutineScope
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
    val speed: Float = 1f
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
    fun play(): Int
    fun pause(): Int
    fun stop(): Int
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

/**
 * Direct libmpv output used by the local player. Platform renderers keep this internal so remote
 * player implementations never expose an application-owned libmpv instance to UI code.
 */
internal class LocalMpvVideoOutput(
    val mpv: Mpv
) : MpvVideoOutput

@Composable
fun rememberMpvPlayer(
    scope: CoroutineScope = rememberCoroutineScope()
): MpvPlayer {
    val mpv = remember { Mpv() }
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
) : MpvPlayer {
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
    private var started = false
    private var closed = false

    private val eventListener: (MpvEvent) -> Unit = { event ->
        scope.launch {
            handleEvent(event)
        }
    }

    fun setup(): Boolean {
        if (started) return snapshot.value.state != MpvPlayerState.Error
        check(!closed) { "LocalMpvPlayer is already closed" }

        started = true
        if (!mpv.initialize()) {
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
            println("LocalMpvPlayer: initialize failed")
            return false
        }

        mpv.setCoroutineScope(scope)
        mpv.addEventListener(eventListener)
        mpv.observeProperty(MpvAudioProperties.VOLUME)
        MpvPlaybackProperties.ALL.forEach(mpv::observeProperty)
        publish(snapshot.value.copy(state = MpvPlayerState.Idle))
        return true
    }

    private fun handleEvent(event: MpvEvent) {
        if (closed) return
        if (event.error < 0) {
            hasActiveFile = false
            stopRequested = false
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
                next = next.copy(
                    state = MpvPlayerState.Loading,
                    positionSeconds = 0.0,
                    durationSeconds = 0.0
                )
            }

            MpvEventType.FileLoaded,
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
                next = next.copy(
                    state = if (stopRequested) MpvPlayerState.Stopped else MpvPlayerState.Ended
                )
                stopRequested = false
            }

            MpvEventType.Idle -> {
                if (!hasActiveFile && next.state == MpvPlayerState.Loading) {
                    next = next.copy(state = MpvPlayerState.Idle)
                }
            }

            MpvEventType.Shutdown -> {
                hasActiveFile = false
                stopRequested = false
                next = next.copy(state = MpvPlayerState.Disposed)
            }

            else -> Unit
        }
        if (next != snapshot.value) publish(next)
    }

    override fun load(uri: String): Int {
        ensureUsable()
        val result = mpv.load(uri)
        if (result >= 0) {
            hasActiveFile = false
            stopRequested = false
            publish(
                snapshot.value.copy(
                    state = MpvPlayerState.Loading,
                    positionSeconds = 0.0,
                    durationSeconds = 0.0
                )
            )
        } else {
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
        }
        return result
    }

    override fun play(): Int {
        ensureUsable()
        val result = mpv.play()
        if (result >= 0) {
            pauseProperty = false
            if (hasActiveFile) publish(snapshot.value.copy(state = MpvPlayerState.Playing))
        } else {
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
        }
        return result
    }

    override fun pause(): Int {
        ensureUsable()
        val result = mpv.pause()
        if (result >= 0) {
            pauseProperty = true
            if (hasActiveFile) publish(snapshot.value.copy(state = MpvPlayerState.Paused))
        } else {
            publish(snapshot.value.copy(state = MpvPlayerState.Error))
        }
        return result
    }

    override fun stop(): Int {
        ensureUsable()
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
        if (started) {
            mpv.removeEventListener(eventListener)
            mpv.removePropertyObservation(MpvAudioProperties.VOLUME)
            MpvPlaybackProperties.ALL.forEach(mpv::removePropertyObservation)
        }
        publish(snapshot.value.copy(state = MpvPlayerState.Disposed))
        releaseMpv()
    }

    internal fun reportRenderError(message: String, cause: Throwable? = null) {
        println("LocalMpvPlayer: render failed: $message${cause?.let { ": $it" } ?: ""}")
        publish(snapshot.value.copy(state = MpvPlayerState.Error))
    }

    private fun ensureUsable() {
        check(started) { "LocalMpvPlayer has not been started" }
        check(!closed) { "LocalMpvPlayer is already closed" }
    }

    private fun publishErrorIfNeeded(result: Int) {
        if (result < 0) publish(snapshot.value.copy(state = MpvPlayerState.Error))
    }

    private fun publish(value: MpvPlayerSnapshot) {
        mutableSnapshot.value = value
    }
}

internal const val UNSUPPORTED_MPV_COMMAND: Int = -1

private fun String?.toMpvBoolean(): Boolean = this == "yes" || this == "true"
