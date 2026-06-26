package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.*
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun MpvComposeView(
    modifier: Modifier,
    state: MpvPlayer
) {
    UIKitView(
        modifier = modifier,
        factory = {
            val renderPlayer = state.player as? IosRenderContextSupport
            renderPlayer?.let { IosMpvRenderView(it) } ?: UIView().apply {
                backgroundColor = UIColor.blackColor
            }
        },
        update = {},
        onRelease = { view ->
            (view as? IosMpvRenderView)?.dispose()
        }
    )
}

@OptIn(ExperimentalForeignApi::class)
private class IosMpvRenderView(
    private val renderPlayer: IosRenderContextSupport
) : UIImageView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private var bitmapContext: CGContextRef? = null
    private var colorSpace: CGColorSpaceRef? = null
    private var buffer: CPointer<ByteVar>? = null
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var bufferStride = 0
    private var bufferSize = 0
    private var loggedFirstRender = false
    private var loggedEmptySize = false

    @Volatile
    private var renderPending = false
    @Volatile
    private var disposed = false

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        contentScaleFactor = UIScreen.mainScreen.scale

        if (renderPlayer.createRenderContext()) {
            renderPlayer.setRenderCallback { scheduleRender() }
            scheduleRender()
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        scheduleRender()
    }

    fun dispose() {
        disposed = true
        renderPlayer.freeRenderContext()
        releaseBitmap()
        image = null
    }

    private fun scheduleRender() {
        if (disposed || renderPending) return
        renderPending = true
        dispatch_async(dispatch_get_main_queue()) {
            renderPending = false
            renderFrame()
        }
    }

    private fun renderFrame() {
        if (disposed) return

        val (width, height) = pixelSize()
        if (width <= 0 || height <= 0) {
            if (!loggedEmptySize) {
                loggedEmptySize = true
                println("IosMpvRenderView: skip render for empty size ${width}x$height")
            }
            return
        }
        if (!ensureBitmap(width, height)) return

        val target = buffer ?: return
        val result = renderPlayer.renderSw(width, height, bufferStride, "bgr0", target.reinterpret())
        if (result != 0) return

        val context = bitmapContext ?: return
        val cgImage = CGBitmapContextCreateImage(context) ?: return
        image = UIImage(cgImage)
        if (!loggedFirstRender) {
            loggedFirstRender = true
            println("IosMpvRenderView: rendered first frame ${width}x$height")
        }
        CGImageRelease(cgImage)
    }

    private fun pixelSize(): Pair<Int, Int> {
        return bounds.useContents {
            Pair(
                (size.width * contentScaleFactor).roundToInt(),
                (size.height * contentScaleFactor).roundToInt()
            )
        }
    }

    private fun ensureBitmap(width: Int, height: Int): Boolean {
        if (bitmapContext != null && width == bufferWidth && height == bufferHeight) {
            return true
        }

        releaseBitmap()

        bufferWidth = width
        bufferHeight = height
        bufferStride = width * 4
        bufferSize = bufferStride * height
        buffer = nativeHeap.allocArray<ByteVar>(bufferSize)
        colorSpace = CGColorSpaceCreateDeviceRGB()

        val bitmapInfo = kCGBitmapByteOrder32Little or CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value
        bitmapContext = CGBitmapContextCreate(
            data = buffer,
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = bufferStride.toULong(),
            space = colorSpace,
            bitmapInfo = bitmapInfo
        )

        if (bitmapContext == null) {
            releaseBitmap()
            return false
        }

        return true
    }

    private fun releaseBitmap() {
        bitmapContext?.let { CGContextRelease(it) }
        bitmapContext = null

        colorSpace?.let { CGColorSpaceRelease(it) }
        colorSpace = null

        buffer?.let { nativeHeap.free(it.rawValue) }
        buffer = null
        bufferWidth = 0
        bufferHeight = 0
        bufferStride = 0
        bufferSize = 0
    }
}
