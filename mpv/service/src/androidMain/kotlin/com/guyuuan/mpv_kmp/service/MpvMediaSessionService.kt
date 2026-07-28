package com.guyuuan.mpv_kmp.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Android foreground playback owner backed by libmpv and a Media3 media session. */
@UnstableApi
open class MpvMediaSessionService : MediaSessionService() {
    private lateinit var serviceScope: CoroutineScope
    private var coordinator: PlaybackCoordinator? = null
    private var media3Player: MpvMedia3Player? = null
    private var mediaSession: MediaSession? = null
    private var interruptionManager: AndroidPlaybackInterruptionManager? = null

    protected open fun createPlaybackCoordinator(scope: CoroutineScope): PlaybackCoordinator =
        PlaybackCoordinator(
            scope = scope,
            stateStore = AndroidPlaybackStateStore(this)
        )

    protected fun playbackCoordinator(): PlaybackCoordinator =
        checkNotNull(coordinator) { "The media session service has not been created" }

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val coordinator = createPlaybackCoordinator(serviceScope)
        if (!coordinator.start()) {
            coordinator.close()
            error("Unable to initialize libmpv")
        }
        coordinator.restoreSavedPlayback()
        val player = MpvMedia3Player(coordinator)
        val session = MediaSession.Builder(this, player).build()
        val interruptionManager = AndroidPlaybackInterruptionManager(
            context = this,
            coordinator = coordinator,
            scope = serviceScope
        )
        interruptionManager.start()

        this.coordinator = coordinator
        this.media3Player = player
        this.mediaSession = session
        this.interruptionManager = interruptionManager
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        interruptionManager?.stop()
        interruptionManager = null
        media3Player?.release()
        media3Player = null
        mediaSession?.release()
        mediaSession = null
        coordinator?.close()
        coordinator = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
