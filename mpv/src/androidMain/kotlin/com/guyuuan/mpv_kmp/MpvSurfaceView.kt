package com.guyuuan.mpv_kmp

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class MpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private var player: IMpvPlayer? = null
    private var attachedSurface: android.view.Surface? = null
    private var attachedWidth: Int = 0
    private var attachedHeight: Int = 0

    init {
        holder.addCallback(this)
    }

    fun setPlayer(player: IMpvPlayer) {
        if (this.player != null && this.player !== player) {
            this.player?.detach()
            clearAttachedSurface()
        }
        this.player = player
        attachSurfaceIfReady(holder)
    }

    fun release() {
        player?.detach()
        player = null
        clearAttachedSurface()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachSurfaceIfReady(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        attachSurfaceIfReady(holder, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        clearAttachedSurface()
        // SurfaceView destroys its surface when the app goes to the background.
        // Clearing mpv's wid here makes the Android VO reinitialize with no
        // surface and fail with "Missing surface pointer".
    }

    private fun attachSurfaceIfReady(
        holder: SurfaceHolder,
        width: Int = holder.surfaceFrame.width(),
        height: Int = holder.surfaceFrame.height()
    ) {
        val currentPlayer = player ?: return
        val surface = holder.surface
        if (!surface.isValid || width <= 0 || height <= 0) return
        if (attachedSurface === surface && attachedWidth == width && attachedHeight == height) {
            return
        }

        currentPlayer.setProperty(ANDROID_SURFACE_SIZE_PROPERTY, "${width}x$height")
        currentPlayer.attach(surface)
        attachedSurface = surface
        attachedWidth = width
        attachedHeight = height
    }

    private fun clearAttachedSurface() {
        attachedSurface = null
        attachedWidth = 0
        attachedHeight = 0
    }

    private companion object {
        const val ANDROID_SURFACE_SIZE_PROPERTY = "android-surface-size"
    }
}
