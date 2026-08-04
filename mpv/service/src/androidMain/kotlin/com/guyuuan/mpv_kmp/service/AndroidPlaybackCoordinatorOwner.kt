package com.guyuuan.mpv_kmp.service

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.guyuuan.mpv_kmp.config.MpvConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.concurrent.Volatile

/**
 * Creates a custom process-wide Android playback coordinator.
 *
 * Configure the owner from `Application.onCreate` before a player or media-session service first
 * accesses it. The supplied [mediaIntegration] must be passed to the returned coordinator.
 */
@UnstableApi
fun interface AndroidPlaybackCoordinatorFactory {
    fun create(
        context: Context,
        mediaIntegration: AndroidMediaSessionIntegration
    ): PlaybackCoordinator
}

/**
 * Process-wide owner shared by the Compose PiP player and [MpvMediaSessionService].
 *
 * The owner deliberately outlives both a Compose screen and an individual Service instance. This
 * guarantees that Android UI, PiP, Media3, and system media controls all use one
 * [PlaybackCoordinator] and one libmpv instance.
 */
@UnstableApi
object AndroidPlaybackCoordinatorOwner {
    private val lock = Any()

    @Volatile
    private var playback: OwnedPlayback? = null
    private var configuredFactory: AndroidPlaybackCoordinatorFactory? = null

    /**
     * Installs application-specific coordinator creation before the owner is initialized.
     *
     * This is the Android entry point for custom state stores, command sets, or artwork loaders.
     */
    fun configure(factory: AndroidPlaybackCoordinatorFactory) {
        synchronized(lock) {

            if (playback != null) {
                println("AndroidPlaybackCoordinatorOwner is already initialized")
                return
            }
            if (configuredFactory != null) {
                println("AndroidPlaybackCoordinatorOwner is already configured")
                return
            }
            configuredFactory = factory
        }
    }

    /** Returns the single process-wide coordinator, creating and starting it if necessary. */
    fun coordinator(
        context: Context,
        mpvConfig: MpvConfig = MpvConfig()
    ): PlaybackCoordinator =
        acquire(
            context = context,
            mediaIntegrationFactory = ::AndroidMediaSessionIntegration,
            coordinatorFactory = { mediaIntegration ->
                PlaybackCoordinator(
                    mpvConfig = mpvConfig,
                    mediaIntegration = mediaIntegration,
                    stateStore = AndroidPlaybackStateStore(context)
                )
            }
        ).coordinator

    internal fun acquire(
        context: Context,
        mediaIntegrationFactory: () -> AndroidMediaSessionIntegration,
        coordinatorFactory: (AndroidMediaSessionIntegration) -> PlaybackCoordinator
    ): OwnedPlayback {
        playback?.let { return it }
        return synchronized(lock) {
            playback ?: createPlayback(
                context = context.applicationContext,
                mediaIntegrationFactory = mediaIntegrationFactory,
                coordinatorFactory = coordinatorFactory
            ).also { playback = it }
        }
    }

    private fun createPlayback(
        context: Context,
        mediaIntegrationFactory: () -> AndroidMediaSessionIntegration,
        coordinatorFactory: (AndroidMediaSessionIntegration) -> PlaybackCoordinator
    ): OwnedPlayback {
        val mediaIntegration = mediaIntegrationFactory()
        val coordinator = configuredFactory?.create(context, mediaIntegration)
            ?: coordinatorFactory(mediaIntegration)
        if (!coordinator.start()) {
            coordinator.close()
            error("Unable to initialize libmpv")
        }
        val interruptionScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val interruptionManager = AndroidPlaybackInterruptionManager(
            context = context,
            coordinator = coordinator,
            scope = interruptionScope
        )
        interruptionManager.start()
        coordinator.restoreSavedPlayback()
        return OwnedPlayback(
            coordinator = coordinator,
            mediaIntegration = mediaIntegration,
            interruptionManager = interruptionManager,
            interruptionScope = interruptionScope
        )
    }

    internal data class OwnedPlayback(
        val coordinator: PlaybackCoordinator,
        val mediaIntegration: AndroidMediaSessionIntegration,
        val interruptionManager: AndroidPlaybackInterruptionManager,
        val interruptionScope: CoroutineScope
    )
}
