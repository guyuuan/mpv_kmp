package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun MpvComposeView(
    modifier: Modifier = Modifier,
    state: Mpv,
    overlay: @Composable () -> Unit = {}
)

internal val Mpv.renderTarget: Mpv
    get() = (this as? MpvPlayer)?.mpv ?: this

internal fun Mpv.reportRenderError(message: String, cause: Throwable? = null) {
    if (this is MpvPlayer) {
        reportRenderError(message, cause)
    } else {
        println("MpvComposeView: render failed: $message${cause?.let { ": $it" } ?: ""}")
    }
}
