package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.GLRunnable
import com.jogamp.opengl.awt.GLCanvas
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

@Composable
actual fun MpvComposeView(
    modifier: Modifier,
    state: MpvPlayer
) {
    MpvOpenGLView(modifier, state)
}

@Composable
private fun MpvOpenGLView(
    modifier: Modifier,
    state: MpvPlayer
) {
    val glCanvas = remember(state.player) {
        createMpvGlCanvas(state)
    }
    DisposableEffect(glCanvas) {
        onDispose {
            glCanvas.invoke(true, GLRunnable {
                val player = state.player
                if (player is EmbeddedGpuRenderSupport) {
                    player.freeOpenGlRenderContext()
                }
                true
            })
            glCanvas.destroy()
        }
    }
    SwingPanel(
        modifier = modifier,
        factory = { glCanvas },
        update = {
            it.display()
        }
    )
}

private fun createMpvGlCanvas(state: MpvPlayer): GLCanvas {
    val profile = selectMpvGlProfile()
    println("MpvComposeView: using JOGL profile ${profile.name}")
    val capabilities = GLCapabilities(profile).apply {
        doubleBuffered = true
        hardwareAccelerated = true
    }
    return GLCanvas(capabilities).apply {
        addGLEventListener(object : GLEventListener {
            private var initialized = false
            private var failed = false
            private val renderPending = AtomicBoolean(false)

            override fun init(drawable: GLAutoDrawable) {
                val player = state.player
                if (player !is EmbeddedGpuRenderSupport) {
                    failed = true
                    state.reportRenderError("player does not support embedded GPU rendering")
                    return
                }
                try {
                    if (!player.createOpenGlRenderContext()) {
                        failed = true
                        state.reportRenderError("failed to create OpenGL render context")
                        return
                    }
                    initialized = true
                    player.setRenderCallback {
                        if (renderPending.compareAndSet(false, true)) {
                            SwingUtilities.invokeLater {
                                renderPending.set(false)
                                if (isDisplayable) {
                                    display()
                                }
                            }
                        }
                    }
                    SwingUtilities.invokeLater {
                        if (isDisplayable) {
                            display()
                        }
                    }
                } catch (e: Throwable) {
                    failed = true
                    state.reportRenderError("OpenGL render context initialization threw", e)
                }
            }

            override fun display(drawable: GLAutoDrawable) {
                if (!initialized || failed) return
                val player = state.player
                if (player !is EmbeddedGpuRenderSupport) return
                val width = drawable.surfaceWidth
                val height = drawable.surfaceHeight
                if (width <= 0 || height <= 0) return
                try {
                    player.renderOpenGl(drawable.context.defaultDrawFramebuffer, width, height)
                } catch (e: Throwable) {
                    failed = true
                    state.reportRenderError("OpenGL render threw", e)
                }
            }

            override fun reshape(
                drawable: GLAutoDrawable,
                x: Int,
                y: Int,
                width: Int,
                height: Int
            ) {
                display(drawable)
            }

            override fun dispose(drawable: GLAutoDrawable) {
                val player = state.player
                if (player is EmbeddedGpuRenderSupport) {
                    try {
                        player.freeOpenGlRenderContext()
                    } catch (e: Throwable) {
                        state.reportRenderError("OpenGL render context dispose threw", e)
                    }
                }
                initialized = false
            }
        })
    }
}

private fun selectMpvGlProfile(): GLProfile {
    return try {
        GLProfile.getMaxProgrammable(true)
    } catch (_: Throwable) {
        when {
            GLProfile.isAvailable(GLProfile.GL3) -> GLProfile.get(GLProfile.GL3)
            else -> GLProfile.get(GLProfile.GL2)
        }
    }
}

@Composable
private fun MpvNativeWindowView(
    modifier: Modifier,
    state: MpvPlayer
) {
    SwingPanel(
        modifier = modifier,
        factory = {
            MpvCanvas().apply {
                setPlayer(state.player)
            }
        },
        update = {
            it.setPlayer(state.player)
        }
    )
}

@Composable
private fun MpvSoftwareView(
    modifier: Modifier,
    state: MpvPlayer
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
