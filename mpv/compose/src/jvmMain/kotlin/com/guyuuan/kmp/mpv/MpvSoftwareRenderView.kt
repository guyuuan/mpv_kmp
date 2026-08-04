package com.guyuuan.kmp.mpv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

@Composable
internal fun MpvSoftwareRenderView(
    modifier: Modifier, state: Mpv, overlay: @Composable () -> Unit
) {
    val player = state
    val frameBuffer = remember(player) { SoftwareRenderFrameBuffer() }
    val renderPending = remember(player) { AtomicBoolean(false) }
    var renderSignal by remember(player) { mutableStateOf(0) }

    DisposableEffect(player) {
        val disposed = AtomicBoolean(false)
        if (player !is SoftwareRenderContextSupport) {
            state.reportRenderError("mpv does not support software rendering")
        } else {
            try {
                if (player.createSoftwareRenderContext()) {
                    player.setRenderCallback {
                        if (renderPending.compareAndSet(false, true)) {
                            SwingUtilities.invokeLater {
                                renderPending.set(false)
                                if (!disposed.get()) {
                                    renderSignal++
                                }
                            }
                        }
                    }
                    renderSignal++
                } else {
                    state.reportRenderError("failed to create software render context")
                }
            } catch (e: Throwable) {
                state.reportRenderError("software render context initialization threw", e)
            }
        }
        onDispose {
            disposed.set(true)
            renderPending.set(false)
            if (player is SoftwareRenderContextSupport) {
                try {
                    player.freeRenderContext()
                } catch (e: Throwable) {
                    state.reportRenderError("software render context dispose threw", e)
                }
            }
            frameBuffer.close()
        }
    }

    val frame = renderSignal
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val softwarePlayer = player as? SoftwareRenderContextSupport ?: return@Canvas
            val width = size.width.roundToInt()
            val height = size.height.roundToInt()
            if (width <= 0 || height <= 0) return@Canvas

            try {
                val (bitmap, imageBitmap) = frameBuffer.ensure(width, height)
                val pixelsAddr = bitmap.peekPixels()?.addr ?: return@Canvas
                val ptr = Pointer(pixelsAddr)
                softwarePlayer.render(width, height, bitmap.rowBytes, "bgr0", ptr)
                forceOpaqueAlpha(ptr, width, height, bitmap.rowBytes)
                drawImage(
                    image = imageBitmap,
                    dstSize = IntSize(width, height),
                    filterQuality = FilterQuality.None
                )
            } catch (e: Throwable) {
                state.reportRenderError("software render threw at frame $frame", e)
            }
        }
        overlay()
    }
}

private class SoftwareRenderFrameBuffer {
    private var bitmap: Bitmap? = null
    private var imageBitmap: ImageBitmap? = null

    fun ensure(width: Int, height: Int): Pair<Bitmap, ImageBitmap> {
        val currentBitmap = bitmap
        val currentImageBitmap = imageBitmap
        if (currentBitmap != null && currentImageBitmap != null && currentBitmap.width == width && currentBitmap.height == height) {
            return currentBitmap to currentImageBitmap
        }

        currentBitmap?.close()
        val newBitmap = Bitmap().apply {
            allocPixels(ImageInfo.makeN32Premul(width, height))
        }
        val newImageBitmap = newBitmap.asComposeImageBitmap()
        bitmap = newBitmap
        imageBitmap = newImageBitmap
        return newBitmap to newImageBitmap
    }

    fun close() {
        bitmap?.close()
        bitmap = null
        imageBitmap = null
    }
}

private fun forceOpaqueAlpha(ptr: Pointer, width: Int, height: Int, rowBytes: Int) {
    val totalSize = rowBytes.toLong() * height.toLong()
    if (totalSize !in 1..Int.MAX_VALUE.toLong()) return
    val buf = ptr.getByteBuffer(0, totalSize)
    val visibleRowBytes = width * 4
    var y = 0
    while (y < height) {
        val rowStart = y * rowBytes
        var i = rowStart + 3
        val end = rowStart + visibleRowBytes
        while (i < end) {
            buf.put(i, 0xFF.toByte())
            i += 4
        }
        y++
    }
}
