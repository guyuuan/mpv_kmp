package com.guyuuan.kmp.mpv.service

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import com.guyuuan.kmp.mpv.config.MpvConfig
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var nextGeneration = 1L

    /**
     * Installs application-specific coordinator creation before the owner is initialized.
     *
     * This is the Android entry point for custom state stores, command sets, or artwork loaders.
     */
    fun configure(factory: AndroidPlaybackCoordinatorFactory) {
        synchronized(lock) {

            if (playback != null) {
                Logger.w(tag = "AndroidPlaybackCoordinatorOwner") {
                    "owner is already initialized"
                }
                return
            }
            if (configuredFactory != null) {
                Logger.w(tag = "AndroidPlaybackCoordinatorOwner") {
                    "owner is already configured"
                }
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
                )
            }
        ).coordinator

    /**
     * Terminates process-wide playback and requests that its MediaSessionService stop.
     *
     * The installed factory is retained so a later player can create a fresh coordinator.
     */
    fun close(context: Context) {
        var cleanupFailure: Throwable? = null
        try {
            synchronized(lock) {
                playback?.let { ownedPlayback ->
                    // Invalidate the generation before teardown starts. A concurrent acquire sees
                    // null and blocks on this lock until the old coordinator is fully closed.
                    playback = null
                    fun cleanup(action: () -> Unit) {
                        try {
                            action()
                        } catch (error: Throwable) {
                            if (cleanupFailure == null) cleanupFailure = error
                        }
                    }
                    cleanup { ownedPlayback.interruptionManager.stop() }
                    cleanup { ownedPlayback.interruptionScope.cancel() }
                    cleanup { ownedPlayback.coordinator.close() }
                }
            }
        } catch (error: Throwable) {
            cleanupFailure = error
        }
        context.applicationContext.stopService(
            Intent(context.applicationContext, MpvMediaSessionService::class.java)
        )
        cleanupFailure?.let { throw it }
    }

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
        val generation = nextGeneration
        nextGeneration = if (nextGeneration == Long.MAX_VALUE) 1L else nextGeneration + 1L
        return OwnedPlayback(
            generation = generation,
            coordinator = coordinator,
            mediaIntegration = mediaIntegration,
            interruptionManager = interruptionManager,
            interruptionScope = interruptionScope
        )
    }

    internal data class OwnedPlayback(
        val generation: Long,
        val coordinator: PlaybackCoordinator,
        val mediaIntegration: AndroidMediaSessionIntegration,
        val interruptionManager: AndroidPlaybackInterruptionManager,
        val interruptionScope: CoroutineScope
    )
}
