package com.guyuuan.kmp.mpv

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

interface AndroidMpvVideoOutput : MpvVideoOutput {
    fun onPlayerViewAttached(view: MpvSurfaceView) = Unit
    fun onPlayerViewDetached(view: MpvSurfaceView) = Unit
    fun attach(surface: android.view.Surface, width: Int, height: Int) = Unit
    fun surfaceDestroyed(surface: android.view.Surface) = Unit
    fun detach() = Unit
}

@Composable
internal actual fun MpvVideoOutputView(
    modifier: Modifier, output: MpvVideoOutput, overlay: @Composable () -> Unit
) {
    val androidOutput = remember(output) {
        when (output) {
            is AndroidMpvVideoOutput -> output
            is LocalMpvVideoOutput -> DirectAndroidMpvVideoOutput(output.mpv)
            else -> UnsupportedAndroidMpvVideoOutput
        }
    }
    Box(modifier = modifier) {
        AndroidView(modifier = Modifier.matchParentSize(), factory = { context ->
            MpvSurfaceView(context).apply {
                setVideoOutput(androidOutput)
            }
        }, update = { view ->
            view.setVideoOutput(androidOutput)
        }, onRelease = { view ->
            view.release()
        })
        overlay()
    }
}

private class DirectAndroidMpvVideoOutput(
    private val mpv: Mpv
) : AndroidMpvVideoOutput {
    override fun attach(surface: android.view.Surface, width: Int, height: Int) {
        mpv.setProperty(ANDROID_SURFACE_SIZE_PROPERTY, "${width}x$height")
        mpv.attach(surface)
    }

    override fun detach() {
        mpv.detach()
    }
}

private object UnsupportedAndroidMpvVideoOutput : AndroidMpvVideoOutput

private const val ANDROID_SURFACE_SIZE_PROPERTY = "android-surface-size"
