package com.guyuuan.mpv_kmp.pip

import android.content.ComponentName
import android.content.Context
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.guyuuan.mpv_kmp.AndroidMpvVideoOutput
import com.guyuuan.mpv_kmp.MpvSurfaceView
import com.guyuuan.mpv_kmp.service.MpvMediaSessionService
import com.guyuuan.mpv_kmp.service.PlaybackCoordinator
import com.guyuuan.mpv_kmp.service.PlaybackSnapshot

/**
 * Keeps the Android MediaSessionService connected while UI commands go directly through the
 * process-wide PlaybackCoordinator.
 */
@UnstableApi
internal class AndroidMediaSessionConnection(
    context: Context,
    serviceComponent: ComponentName = ComponentName(context, MpvMediaSessionService::class.java)
) {
    private val applicationContext = context.applicationContext
    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        applicationContext,
        SessionToken(applicationContext, serviceComponent)
    ).buildAsync()
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        MediaController.releaseFuture(controllerFuture)
    }
}

/**
 * Direct Android SurfaceView bridge for the PlaybackCoordinator-owned libmpv instance.
 *
 * Surface destruction is intentionally not treated as a detach: Android may immediately replace
 * the SurfaceView surface while entering PiP, and clearing libmpv's wid between those callbacks
 * would reinitialize the Android video output without a valid surface.
 */
internal class AndroidPlaybackCoordinatorVideoOutput(
    private val coordinator: PlaybackCoordinator,
    private val pictureInPictureController: AndroidPictureInPictureController
) : AndroidMpvVideoOutput {
    private var playerView: MpvSurfaceView? = null
    private var hasAttachedSurface = false
    private var lastPictureInPictureVideoSize: Pair<Int, Int>? = null
    private var closed = false

    fun updatePlaybackSnapshot(snapshot: PlaybackSnapshot) {
        if (closed || snapshot.videoWidth <= 0 || snapshot.videoHeight <= 0) return
        val size = snapshot.videoWidth to snapshot.videoHeight
        if (size == lastPictureInPictureVideoSize) return
        lastPictureInPictureVideoSize = size
        pictureInPictureController.setAspectRatio(
            width = snapshot.videoWidth,
            height = snapshot.videoHeight
        )
    }

    override fun onPlayerViewAttached(view: MpvSurfaceView) {
        if (closed) return
        playerView = view
        pictureInPictureController.trackPlayerView(view)
    }

    override fun onPlayerViewDetached(view: MpvSurfaceView) {
        if (playerView !== view) return
        playerView = null
        pictureInPictureController.trackPlayerView(null)
    }

    override fun attach(surface: Surface, width: Int, height: Int) {
        if (closed || !surface.isValid) return
        if (width > 0 && height > 0) {
            coordinator.player.setProperty(ANDROID_SURFACE_SIZE_PROPERTY, "${width}x$height")
        }
        coordinator.player.attach(surface)
        hasAttachedSurface = true
    }

    override fun surfaceDestroyed(surface: Surface) = Unit

    override fun detach() {
        if (closed) return
        detachPlayer()
    }

    fun close() {
        if (closed) return
        pictureInPictureController.trackPlayerView(null)
        playerView = null
        detachPlayer()
        closed = true
    }

    private fun detachPlayer() {
        if (!hasAttachedSurface) return
        coordinator.player.detach()
        hasAttachedSurface = false
    }

    private companion object {
        const val ANDROID_SURFACE_SIZE_PROPERTY = "android-surface-size"
    }
}
