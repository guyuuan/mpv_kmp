package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger

@Composable
fun MpvComposeView(
    modifier: Modifier = Modifier,
    player: MpvPlayer,
    overlay: @Composable () -> Unit = {}
) {
    MpvVideoOutputView(modifier, player.videoOutput, overlay)
}

/**
 * Direct-libmpv overload kept for source compatibility. New code should pass an [MpvPlayer].
 */
@Composable
fun MpvComposeView(
    modifier: Modifier = Modifier,
    state: Mpv,
    overlay: @Composable () -> Unit = {}
) {
    MpvVideoOutputView(modifier, LocalMpvVideoOutput(state), overlay)
}

@Composable
internal expect fun MpvVideoOutputView(
    modifier: Modifier,
    output: MpvVideoOutput,
    overlay: @Composable () -> Unit
)

internal fun Mpv.reportRenderError(message: String, cause: Throwable? = null) {
    Logger.e(throwable = cause, tag = "MpvComposeView") { "render failed: $message" }
}
