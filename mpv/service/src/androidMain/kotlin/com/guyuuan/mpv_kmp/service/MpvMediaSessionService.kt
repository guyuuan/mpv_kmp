package com.guyuuan.mpv_kmp.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Android foreground media-session host backed by the process-wide
 * [AndroidPlaybackCoordinatorOwner].
 */
@UnstableApi
open class MpvMediaSessionService : MediaSessionService() {
    private var coordinator: PlaybackCoordinator? = null
    private var mediaIntegration: AndroidMediaSessionIntegration? = null
    private var mediaSession: MediaSession? = null

    protected open fun createPlatformMediaIntegration(): AndroidMediaSessionIntegration =
        AndroidMediaSessionIntegration()

    protected open fun createPlaybackCoordinator(
        mediaIntegration: PlatformMediaIntegration
    ): PlaybackCoordinator =
        PlaybackCoordinator(
            mediaIntegration = mediaIntegration,
            stateStore = AndroidPlaybackStateStore(this)
        )

    protected fun playbackCoordinator(): PlaybackCoordinator =
        checkNotNull(coordinator) { "The media session service has not been created" }

    override fun onCreate() {
        super.onCreate()

        val playback = AndroidPlaybackCoordinatorOwner.acquire(
            context = this,
            mediaIntegrationFactory = ::createPlatformMediaIntegration,
            coordinatorFactory = ::createPlaybackCoordinator
        )
        val mediaIntegration = playback.mediaIntegration
        val coordinator = playback.coordinator
        val session = MediaSession.Builder(this, mediaIntegration.player).build()

        this.coordinator = coordinator
        this.mediaIntegration = mediaIntegration
        this.mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        coordinator?.persistPlaybackState()
        mediaSession?.release()
        mediaSession = null
        coordinator = null
        mediaIntegration = null
        super.onDestroy()
    }
}
