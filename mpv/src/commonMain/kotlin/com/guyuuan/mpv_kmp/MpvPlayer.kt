package com.guyuuan.mpv_kmp

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
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

@Composable
fun rememberMpvPlayer(
    scope: CoroutineScope = rememberCoroutineScope()
): MpvPlayer {
    val player = remember { createMpvPlayer() }
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
    val player: IMpvPlayer,
    private val scope: CoroutineScope
) {
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

    private var hasActiveFile = false
    private var pauseProperty = false
    private var stopRequested = false

    fun setup() {
        if (player.initialize()) {
            updateState(MpvPlayerState.Idle)
            player.setCoroutineScope(scope)
            player.setEventListener { event ->
                scope.launch {
                    handleEvent(event)
                }
            }
            player.observeProperty("pause")
            player.observeProperty("time-pos")
            player.observeProperty("duration")
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

    fun load(url: String): Int {
        val result = player.load(url)
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

    fun getPlaylist(): List<MpvPlaylistItem> = player.getPlaylist()

    fun removeFromPlaylist(index: Int): Int {
        val result = player.removeFromPlaylist(index)
        if (result < 0) {
            updateState(MpvPlayerState.Error)
        }
        return result
    }

    fun play(): Int {
        val result = player.play()
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

    fun pause(): Int {
        val result = player.pause()
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

    fun stop(): Int {
        val result = player.stop()
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
        val result = player.seekTo(position)
        if (result < 0) {
            updateState(MpvPlayerState.Error)
        }
        return result
    }

    fun dispose() {
        hasActiveFile = false
        stopRequested = false
        updateState(MpvPlayerState.Disposed)
        player.terminate()
    }
}
