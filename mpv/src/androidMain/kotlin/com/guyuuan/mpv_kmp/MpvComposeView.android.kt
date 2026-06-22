package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun MpvComposeView(
    modifier: Modifier,
    state: MpvPlayer
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MpvSurfaceView(context).apply {
                setPlayer(state.player)
            }
        },
        update = { view ->
            // MpvSurfaceView handles attach internally when surface is created
            // If state.player changes, we might need to update, but usually MpvPlayer holds stable player
        }
    )
}
