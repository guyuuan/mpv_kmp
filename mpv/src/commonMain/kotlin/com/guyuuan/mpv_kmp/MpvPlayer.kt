package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.guyuuan.mpv_kmp.data.MpvDecoderInfo
import com.guyuuan.mpv_kmp.data.MpvEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

enum class MpvPlayerState {
    Idle, Loading, Playing, Paused, Stopped, Ended, Error, Disposed
}

val MpvPlayerState.isIdle: Boolean
    get() = this == MpvPlayerState.Idle || this == MpvPlayerState.Stopped || this == MpvPlayerState.Ended

@Composable
fun rememberMpvPlayer(
    scope: CoroutineScope = rememberCoroutineScope()
): MpvPlayer {
    val player = remember { createMpv() }
    val state = remember(player, scope) { MpvPlayer(player, scope) }

    DisposableEffect(state) {
        state.setup()
        onDispose {
            state.dispose()
        }
    }
    return state
}

@Stable
class MpvPlayer(
    val mpv: Mpv, private val scope: CoroutineScope
) :Mpv by mpv{
    var state by mutableStateOf(MpvPlayerState.Idle)
        private set

    val isPaused: Boolean
        get() = state == MpvPlayerState.Paused

    val isLoading: Boolean
        get() = state == MpvPlayerState.Loading

    val isPlaying: Boolean
        get() = state == MpvPlayerState.Playing

    var timePos by mutableStateOf(0.0)
        private set

    var duration by mutableStateOf(0.0)
        private set

    val decoderInfoFlow: SharedFlow<MpvDecoderInfo> = callbackFlow {
        MpvDecoderProperties.ALL.forEach { mpv.observeProperty(it) }
        val listener: MpvEventListener = { event ->
            if (event.name in MpvDecoderProperties.ALL) {
                val info = mpv.getDecoderInfo()
                trySend(info)
            }
        }
        mpv.addEventListener(listener)
        trySend(mpv.getDecoderInfo())
        awaitClose {
            mpv.removeEventListener(listener)
            MpvDecoderProperties.ALL.forEach { mpv.removePropertyObservation(it) }
        }
    }.shareIn(scope, started = SharingStarted.WhileSubscribed())

    private var hasActiveFile = false
    private var pauseProperty = false
    private var stopRequested = false

    fun setup() {
        if (mpv.initialize()) {
            updateState(MpvPlayerState.Idle)
            mpv.setCoroutineScope(scope)
            mpv.addEventListener { event ->
                scope.launch {
                    handleEvent(event)
                }
            }
            mpv.observeProperty("pause")
            mpv.observeProperty("time-pos")
            mpv.observeProperty("duration")
        } else {
            updateState(MpvPlayerState.Error)
            println("MpvPlayer: initialize failed")
        }
    }

    private fun handleEvent(event: MpvEvent) {
        if (event.error < 0) {
            hasActiveFile = false
            stopRequested = false
            updateState(MpvPlayerState.Error)
            return
        }

        when (event.type) {
            MpvEventType.PropertyChange -> {
                when (event.name) {
                    "pause" -> handlePauseProperty(event.value)
                    "time-pos" -> timePos = event.value?.toDoubleOrNull() ?: 0.0
                    "duration" -> duration = event.value?.toDoubleOrNull() ?: 0.0
                }
            }

            MpvEventType.Pause -> {
                pauseProperty = true
                if (hasActiveFile) {
                    updateState(MpvPlayerState.Paused)
                }
            }

            MpvEventType.Unpause -> {
                pauseProperty = false
                if (hasActiveFile) {
                    updateState(MpvPlayerState.Playing)
                }
            }

            MpvEventType.StartFile -> {
                hasActiveFile = false
                stopRequested = false
                updateState(MpvPlayerState.Loading)
            }

            MpvEventType.FileLoaded -> {
                hasActiveFile = true
                updateState(if (pauseProperty) MpvPlayerState.Paused else MpvPlayerState.Playing)
            }

            MpvEventType.PlaybackRestart -> {
                hasActiveFile = true
                updateState(if (pauseProperty) MpvPlayerState.Paused else MpvPlayerState.Playing)
            }

            MpvEventType.EndFile -> {
                hasActiveFile = false
                updateState(if (stopRequested) MpvPlayerState.Stopped else MpvPlayerState.Ended)
                stopRequested = false
            }


            MpvEventType.Idle -> {
                if (!hasActiveFile && state == MpvPlayerState.Loading) {
                    updateState(MpvPlayerState.Idle)
                }
            }

            MpvEventType.Shutdown -> {
                hasActiveFile = false
                stopRequested = false
                updateState(MpvPlayerState.Disposed)
            }

            else -> {}
        }
    }

    private fun handlePauseProperty(value: String?) {
        pauseProperty = value == "yes" || value == "true"
        if (!hasActiveFile) return
        updateState(if (pauseProperty) MpvPlayerState.Paused else MpvPlayerState.Playing)
    }

    private fun updateState(newState: MpvPlayerState) {
        if (state == MpvPlayerState.Disposed && newState != MpvPlayerState.Disposed) return
        state = newState
    }

    internal fun reportRenderError(message: String, cause: Throwable? = null) {
        println("MpvPlayer: render failed: $message${cause?.let { ": $it" } ?: ""}")
        updateState(MpvPlayerState.Error)
    }

   override fun load(uri: String): Int {
        val result = mpv.load(uri)
        if (result >= 0) {
            hasActiveFile = false
            stopRequested = false
            timePos = 0.0
            duration = 0.0
            updateState(MpvPlayerState.Loading)
        } else {
            updateState(MpvPlayerState.Error)
        }
        return result
    }




    override fun setSubtitle(id: Int?): Int {
        val result = mpv.setSubtitle(id)
        if (result < 0) {
            updateState(MpvPlayerState.Error)
        }
        return result
    }

    override fun setAudioTrack(id: Int?): Int {
        val result = mpv.setAudioTrack(id)
        if (result < 0) {
            updateState(MpvPlayerState.Error)
        }
        return result
    }


    override fun addExternalSubtitle(uri: String): Int {
        val result = mpv.addExternalSubtitle(uri)
        if (result < 0) {
            updateState(MpvPlayerState.Error)
        }
        return result
    }

    override fun addExternalSubtitleFile(path: String): Int {
        val result = mpv.addExternalSubtitleFile(path)
        if (result < 0) {
            updateState(MpvPlayerState.Error)
        }
        return result
    }

    override fun removeFromPlaylist(index: Int): Int {
        val result = mpv.removeFromPlaylist(index)
        if (result < 0) {
            updateState(MpvPlayerState.Error)
        }
        return result
    }

    override fun play(): Int {
        val result = mpv.play()
        if (result >= 0) {
            pauseProperty = false
            if (hasActiveFile) {
                updateState(MpvPlayerState.Playing)
            }
        } else {
            updateState(MpvPlayerState.Error)
        }
        return result
    }

    override fun pause(): Int {
        val result = mpv.pause()
        if (result >= 0) {
            pauseProperty = true
            if (hasActiveFile) {
                updateState(MpvPlayerState.Paused)
            }
        } else {
            updateState(MpvPlayerState.Error)
        }
        return result
    }

    fun togglePause() {
        if (isPaused) play() else pause()
    }

    override fun stop(): Int {
        val result = mpv.stop()
        if (result >= 0) {
            hasActiveFile = false
            stopRequested = true
            timePos = 0.0
            updateState(MpvPlayerState.Stopped)
        } else {
            updateState(MpvPlayerState.Error)
        }
        return result
    }

    fun seek(position: Double): Int {
        val result = mpv.seekTo(position)
        if (result < 0) {
            updateState(MpvPlayerState.Error)
        }
        return result
    }


    fun dispose() {
        hasActiveFile = false
        stopRequested = false
        updateState(MpvPlayerState.Disposed)
        mpv.terminate()
    }
}
