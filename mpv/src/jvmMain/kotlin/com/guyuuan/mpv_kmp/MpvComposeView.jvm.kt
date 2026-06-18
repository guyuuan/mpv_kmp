package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import javax.swing.SwingUtilities
import com.sun.jna.Pointer

@Composable
actual fun MpvComposeView(
    modifier: Modifier,
    state: MpvPlayerState
) {
    var isInit by remember { mutableStateOf(false) }
    SwingPanel(
        modifier = modifier,
        factory = {
            SkiaLayer().apply {
                renderDelegate = object : SkikoRenderDelegate {
                    private var bitmap: Bitmap? = null

                    override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                        val player = state.player
                        if (player is RenderContextSupport) {
                            if (!isInit) {
                                println("MpvComposeView: Initializing RenderContext (SW)...")
                                if (player.createRenderContext()) {
                                    println("MpvComposeView: RenderContext created successfully.")
                                    player.setRenderCallback {
                                        SwingUtilities.invokeLater {
                                            needRender()
                                        }
                                    }
                                    isInit = true
                                } else {
                                    println("MpvComposeView: Failed to create RenderContext.")
                                }
                            }

                            if (isInit) {
                                if (width <= 0 || height <= 0) return
                                // Reallocate bitmap if size changes
                                if (bitmap == null || bitmap!!.width != width || bitmap!!.height != height) {
                                    bitmap?.close()
                                    bitmap = Bitmap()
                                    bitmap!!.allocPixels(ImageInfo.makeN32Premul(width, height))
                                }

                                val b = bitmap!!
                                val pixelsAddr = b.peekPixels()?.addr
                                if (pixelsAddr != null) {
                                    val ptr = Pointer(pixelsAddr)
                                    player.renderSw(width, height, b.rowBytes, "bgr0", ptr)
                                    val totalSize = b.rowBytes.toLong() * height.toLong()
                                    if (totalSize in 1..Int.MAX_VALUE.toLong()) {
                                        val buf = ptr.getByteBuffer(0, totalSize)
                                        val visibleRowBytes = width * 4
                                        var y = 0
                                        while (y < height) {
                                            val rowStart = y * b.rowBytes
                                            var i = rowStart + 3
                                            val end = rowStart + visibleRowBytes
                                            while (i < end) {
                                                buf.put(i, 0xFF.toByte())
                                                i += 4
                                            }
                                            y++
                                        }
                                    }

                                    org.jetbrains.skia.Image.makeFromBitmap(b).use { image ->
                                        canvas.drawImage(image, 0f, 0f)
                                    }
                                }
                            }
                        }
                    }
                }
                SwingUtilities.invokeLater {
                    needRender()
                }
            }
        },
        update = {
            it.needRender()
        }
    )
}
