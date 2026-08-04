package com.guyuuan.kmp.mpv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun MpvVideoOutputView(
    modifier: Modifier, output: MpvVideoOutput, overlay: @Composable () -> Unit
) {
    val state = (output as? LocalMpvVideoOutput)?.mpv ?: return
    when (state.renderMode) {
        RenderMode.Hardware -> MpvHardwareRenderView(modifier, state, overlay = overlay)

        RenderMode.Software -> MpvSoftwareRenderView(modifier, state, overlay = overlay)
    }
}
