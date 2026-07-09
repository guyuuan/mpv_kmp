package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun MpvComposeView(
    modifier: Modifier,
    state: MpvPlayer,
    overlay: @Composable () -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MpvSurfaceView(context).apply {
                setPlayer(state.mpv)
            }
        },
        update = { view ->
            view.setPlayer(state.mpv)
        },
        onRelease = { view ->
            view.release()
        }
    )
}
