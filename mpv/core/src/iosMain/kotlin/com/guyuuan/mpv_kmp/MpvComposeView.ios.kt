package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.EAGL.EAGLContext
import platform.EAGL.kEAGLRenderingAPIOpenGLES2
import platform.GLKit.GLKView
import platform.GLKit.GLKViewDelegateProtocol
import platform.GLKit.GLKViewDrawableColorFormatRGBA8888
import platform.GLKit.GLKViewDrawableDepthFormatNone
import platform.GLKit.GLKViewDrawableMultisampleNone
import platform.GLKit.GLKViewDrawableStencilFormatNone
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.gles2.GL_FRAMEBUFFER_BINDING
import platform.gles2.GL_RGBA
import platform.gles2.glFlush
import platform.gles2.glGetIntegerv
import kotlin.concurrent.Volatile
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun MpvComposeView(
    modifier: Modifier,
    state: MpvPlayer,
    overlay: @Composable () -> Unit
) {
    UIKitView(
        modifier = modifier,
        factory = {
            val renderPlayer = state.mpv as? IosRenderContextSupport
            renderPlayer?.let { IosMpvGlView(it) } ?: UIView().apply {
                backgroundColor = UIColor.blackColor
            }
        },
        update = {},
        onRelease = { view ->
            (view as? IosMpvGlView)?.dispose()
        }
    )
}

@OptIn(ExperimentalForeignApi::class)
private class IosMpvGlView(
    private val renderPlayer: IosRenderContextSupport
) : GLKView(
    frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
    context = createEaglContext()
), GLKViewDelegateProtocol {
    private val glContext: EAGLContext = context
    private var loggedFirstRender = false
    private var loggedEmptySize = false
    private var loggedContextFailure = false
    private var renderCallbackRegistered = false

    @Volatile
    private var renderPending = false

    @Volatile
    private var forceRenderPending = false

    @Volatile
    private var forceNextDraw = false

    @Volatile
    private var disposed = false

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        contentScaleFactor = UIScreen.mainScreen.scale
        drawableColorFormat = GLKViewDrawableColorFormatRGBA8888
        drawableDepthFormat = GLKViewDrawableDepthFormatNone
        drawableStencilFormat = GLKViewDrawableStencilFormatNone
        drawableMultisample = GLKViewDrawableMultisampleNone
        enableSetNeedsDisplay = false
        delegate = this

        ensureRenderContext()
        scheduleRender(force = true)
        scheduleRenderTick()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        scheduleRender(force = true)
    }

    override fun glkView(view: GLKView, drawInRect: CValue<CGRect>) {
        val force = forceNextDraw
        forceNextDraw = false
        renderFrame(force)
    }

    fun dispose() {
        disposed = true
        delegate = null
        makeCurrent()
        renderPlayer.freeRenderContext()
        if (EAGLContext.currentContext() == glContext) {
            EAGLContext.setCurrentContext(null)
        }
    }

    private fun scheduleRender(force: Boolean = false) {
        if (disposed) return
        if (force) forceRenderPending = true
        if (renderPending) return
        renderPending = true
        dispatch_async(dispatch_get_main_queue()) {
            if (disposed) {
                renderPending = false
                return@dispatch_async
            }
            val shouldForce = forceRenderPending
            forceRenderPending = false
            renderPending = false
            forceNextDraw = shouldForce
            display()
        }
    }

    private fun scheduleRenderTick() {
        if (disposed) return
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, (NSEC_PER_SEC / 60u).toLong()),
            dispatch_get_main_queue()
        ) {
            if (!disposed) {
                scheduleRender(force = true)
                scheduleRenderTick()
            }
        }
    }

    private fun renderFrame(force: Boolean) {
        if (disposed || !makeCurrent()) return
        if (!ensureRenderContext()) return

        bindDrawable()
        val width = drawableWidth.toInt()
        val height = drawableHeight.toInt()
        if (width <= 0 || height <= 0) {
            if (!loggedEmptySize) {
                loggedEmptySize = true
                println("IosMpvGlView: skip render for empty drawable ${width}x$height")
            }
            return
        }

        val hasFrameUpdate = renderPlayer.updateRenderContext()
        if (!force && !hasFrameUpdate) return

        val fbo = currentFramebuffer()
        val result = renderPlayer.renderGl(width, height, fbo = fbo, internalFormat = GL_RGBA)
        if (result != 0) return

        glFlush()
        if (!loggedFirstRender) {
            loggedFirstRender = true
            println("IosMpvGlView: rendered first GL frame ${width}x$height")
        }
    }

    private fun makeCurrent(): Boolean {
        val success = EAGLContext.setCurrentContext(glContext)
        if (!success && !loggedContextFailure) {
            loggedContextFailure = true
            println("IosMpvGlView: failed to set EAGLContext selected")
        }
        return success
    }

    private fun currentFramebuffer(): Int {
        val value = intArrayOf(0)
        value.usePinned {
            glGetIntegerv(GL_FRAMEBUFFER_BINDING.toUInt(), it.addressOf(0))
        }
        return value[0]
    }

    private fun ensureRenderContext(): Boolean {
        if (disposed || !makeCurrent()) return false
        if (!renderPlayer.createRenderContext()) return false
        if (!renderCallbackRegistered) {
            renderPlayer.setRenderCallback { scheduleRender() }
            renderCallbackRegistered = true
        }
        return true
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createEaglContext(): EAGLContext {
    return EAGLContext(kEAGLRenderingAPIOpenGLES2)
}
