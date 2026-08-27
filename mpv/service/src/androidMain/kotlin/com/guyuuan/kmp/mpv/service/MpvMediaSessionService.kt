package com.guyuuan.kmp.mpv.service

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
    private var ownerGeneration: Long? = null

    protected open fun createPlatformMediaIntegration(): AndroidMediaSessionIntegration =
        AndroidMediaSessionIntegration()

    protected open fun createPlaybackCoordinator(
        mediaIntegration: PlatformMediaIntegration
    ): PlaybackCoordinator =
        PlaybackCoordinator(
            mediaIntegration = mediaIntegration
        )

    protected fun playbackCoordinator(): PlaybackCoordinator =
        checkNotNull(coordinator) { "The media session service has not been created" }

    override fun onCreate() {
        super.onCreate()
        ensureCurrentSession()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        ensureCurrentSession()

    private fun ensureCurrentSession(): MediaSession {
        val playback = AndroidPlaybackCoordinatorOwner.acquire(
            context = this,
            mediaIntegrationFactory = ::createPlatformMediaIntegration,
            coordinatorFactory = ::createPlaybackCoordinator
        )
        if (ownerGeneration == playback.generation) {
            return checkNotNull(mediaSession)
        }

        mediaSession?.release()
        val integration = playback.mediaIntegration
        return MediaSession.Builder(this, integration.player).build().also { session ->
            coordinator = playback.coordinator
            mediaIntegration = integration
            mediaSession = session
            ownerGeneration = playback.generation
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        coordinator = null
        mediaIntegration = null
        ownerGeneration = null
        super.onDestroy()
    }
}
