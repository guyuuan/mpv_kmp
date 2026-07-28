package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun MpvComposeView(
    modifier: Modifier, state: Mpv, overlay: @Composable () -> Unit
) {
    when (state.renderMode) {
        RenderMode.Hardware -> MpvHardwareRenderView(modifier, state, overlay = overlay)

        RenderMode.Software -> MpvSoftwareRenderView(modifier, state, overlay = overlay)
    }
}
