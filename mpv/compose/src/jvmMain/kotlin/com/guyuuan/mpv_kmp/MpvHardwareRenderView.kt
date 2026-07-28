package com.guyuuan.mpv_kmp

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.SwingPanel
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.awt.GLCanvas
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MpvHardwareRenderView(
    modifier: Modifier, state: Mpv, overlay: @Composable () -> Unit
) {
    val player = state
    val glCanvas = remember(player) {
        createMpvGlCanvas(state)
    }
    DisposableEffect(glCanvas) {
        onDispose {
            glCanvas.invoke(true) {
                if (player is HardwareRenderSupport) {
                    player.freeOpenGlRenderContext()
                }
                true
            }
            glCanvas.destroy()
        }
    }
    SwingPanel(factory = {
        ComposePanel().apply {
            setContent {
                Box {
                    SwingPanel(
                        modifier = Modifier.matchParentSize(),
                        factory = { glCanvas },
                        update = { it.display() })
                    Box {
                        SwingPanel(modifier = Modifier.matchParentSize(), factory = {
                            ComposePanel().apply {
                                setContent {
                                    overlay()
                                }
                            }
                        })
                    }
                }

            }
        }
    }, modifier = modifier)
}

private fun createMpvGlCanvas(state: Mpv): GLCanvas {
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
                val player = state
                if (player !is HardwareRenderSupport) {
                    failed = true
                    state.reportRenderError("mpv does not support embedded GPU rendering")
                    return
                }
                try {
                    if (!player.createHardwareRenderContext()) {
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
                val player = state
                if (player !is HardwareRenderSupport) return
                val width = drawable.surfaceWidth
                val height = drawable.surfaceHeight
                if (width <= 0 || height <= 0) return
                try {
                    player.render(drawable.context.defaultDrawFramebuffer, width, height)
                } catch (e: Throwable) {
                    failed = true
                    state.reportRenderError("OpenGL render threw", e)
                }
            }

            override fun reshape(
                drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int
            ) {
                display(drawable)
            }

            override fun dispose(drawable: GLAutoDrawable) {
                val player = state
                if (player is HardwareRenderSupport) {
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
