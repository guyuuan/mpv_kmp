package com.guyuuan.mpv_kmp

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
                scope.launch {
                    handleEvent(event)
                }
            }
            player.observeProperty("pause")
            player.observeProperty("time-pos")
            player.observeProperty("duration")
        }
    }

    private fun handleEvent(event: MpvEvent) {
        println("handle event: $event")
        when (event.type) {
            MpvEventType.PropertyChange -> {
                when (event.name) {
                    "pause" -> isPaused = event.value == "yes" || event.value == "true"
                    "time-pos" -> timePos = event.value?.toDoubleOrNull() ?: 0.0
                    "duration" -> duration = event.value?.toDoubleOrNull() ?: 0.0
                }
            }
            MpvEventType.Pause -> isPaused = true
            MpvEventType.Unpause -> isPaused = false
            MpvEventType.StartFile -> isLoading = true
            MpvEventType.FileLoaded -> isLoading = false
            MpvEventType.EndFile -> isLoading = false
            else -> {}
        }
    }

    fun load(url: String): Int = player.load(url)

    fun play(): Int {
        val result = player.play()
        if (result >= 0) isPaused = false
        return result
    }

    fun pause(): Int {
        val result = player.pause()
        if (result >= 0) isPaused = true
        return result
    }
    
    fun togglePause() {
        if (isPaused) play() else pause()
    }

    fun stop(): Int = player.stop()
    
    fun seek(position: Double) {
        player.commandString("seek $position absolute")
    }

    fun dispose() {
        player.terminate()
    }
}
