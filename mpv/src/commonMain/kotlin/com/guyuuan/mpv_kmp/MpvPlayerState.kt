package com.guyuuan.mpv_kmp

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope

@Composable
fun rememberMpvPlayerState(
    scope: CoroutineScope = rememberCoroutineScope()
): MpvPlayerState {
    val player = remember { createMpvPlayer() }
    val state = remember(player, scope) { MpvPlayerState(player, scope) }

    DisposableEffect(state) {
        state.setup()
        onDispose {
            state.dispose()
        }
    }
    return state
}

@Stable
class MpvPlayerState(
    val player: MpvPlayer,
    private val scope: CoroutineScope
) {
    var isPaused by mutableStateOf(false)
        private set

    var timePos by mutableStateOf(0.0)
        private set

    var duration by mutableStateOf(0.0)
        private set

    var isLoading by mutableStateOf(false)
        private set

    fun setup() {
        if (player.initialize()) {
            player.setCoroutineScope(scope)
            player.setEventListener { event ->
                handleEvent(event)
            }
            player.observeProperty("pause")
            player.observeProperty("time-pos")
            player.observeProperty("duration")
        }
    }

    private fun handleEvent(event: MpvEvent) {
        when (event.type) {
            MpvEventType.PropertyChange -> {
                when (event.name) {
                    "pause" -> isPaused = event.value == "yes"
                    "time-pos" -> timePos = event.value?.toDoubleOrNull() ?: 0.0
                    "duration" -> duration = event.value?.toDoubleOrNull() ?: 0.0
                }
            }
            MpvEventType.StartFile -> isLoading = true
            MpvEventType.FileLoaded -> isLoading = false
            MpvEventType.EndFile -> isLoading = false
            else -> {}
        }
    }

    fun load(url: String) {
        player.load(url)
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }
    
    fun togglePause() {
        if (isPaused) play() else pause()
    }

    fun stop() {
        player.stop()
    }
    
    fun seek(position: Double) {
        player.commandString("seek $position absolute")
    }

    fun dispose() {
        player.terminate()
    }
}
