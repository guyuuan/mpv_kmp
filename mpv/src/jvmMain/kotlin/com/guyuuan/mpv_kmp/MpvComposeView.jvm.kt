package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import java.awt.Color
import javax.swing.JPanel
import javax.swing.BoxLayout

@Composable
actual fun MpvComposeView(
    modifier: Modifier,
    state: MpvPlayerState
) {
    SwingPanel(
        modifier = modifier,
        factory = {
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                val canvas = MpvCanvas()
                canvas.setPlayer(state.player)
                add(canvas)
                if (state.player is RenderContextSupport) {
                    (state.player as RenderContextSupport).createRenderContext()
                }
            }
        },
        update = { panel ->
            // Update logic if needed
        }
    )
}
