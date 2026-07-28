package com.guyuuan.mpv_kmp

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun MpvComposeView(
    modifier: Modifier, state: Mpv, overlay: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        AndroidView(modifier = Modifier.matchParentSize(), factory = { context ->
            MpvSurfaceView(context).apply {
                setPlayer(state.renderTarget)
            }
        }, update = { view ->
            view.setPlayer(state.renderTarget)
        }, onRelease = { view ->
            view.release()
        })
        overlay()
    }
}
