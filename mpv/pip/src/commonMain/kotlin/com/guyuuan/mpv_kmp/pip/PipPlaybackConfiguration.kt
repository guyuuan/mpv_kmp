package com.guyuuan.mpv_kmp.pip

import com.guyuuan.mpv_kmp.service.DEFAULT_MEDIA_COMMANDS
import com.guyuuan.mpv_kmp.service.MediaCommandType
import com.guyuuan.mpv_kmp.service.PlatformMediaIntegration
import com.guyuuan.mpv_kmp.service.PlaybackArtworkLoaderFactory
import com.guyuuan.mpv_kmp.service.PlaybackCoordinator
import com.guyuuan.mpv_kmp.service.PlaybackStateStore

/**
 * Application-level configuration used when the platform creates its process-wide PiP playback
 * coordinator.
 *
 * Install it with [configurePipPlayback] before a PiP player or platform media service is first
 * accessed. The configuration stores factories rather than loader instances so the coordinator
 * remains the sole owner of the created resources.
 */
class PipPlaybackConfiguration private constructor(
    internal val artworkLoaderFactory: PlaybackArtworkLoaderFactory?,
    internal val availableCommands: Set<MediaCommandType>,
    internal val coordinatorFactory: PipPlaybackCoordinatorFactory?
) {
    class Builder {
        /** Creates the one artwork loader owned by the process-wide playback coordinator. */
        var artworkLoaderFactory: PlaybackArtworkLoaderFactory? = null

        /** Commands exposed to platform media controls. */
        var availableCommands: Set<MediaCommandType> = DEFAULT_MEDIA_COMMANDS

        /**
         * Optional advanced coordinator factory.
         *
         * Most applications should only set [artworkLoaderFactory]. A custom factory should call
         * [PipPlaybackCoordinatorEnvironment.createDefault] unless it needs to replace coordinator
         * construction completely.
         */
        var coordinatorFactory: PipPlaybackCoordinatorFactory? = null

        fun build(): PipPlaybackConfiguration = PipPlaybackConfiguration(
            artworkLoaderFactory = artworkLoaderFactory,
            availableCommands = availableCommands.toSet(),
            coordinatorFactory = coordinatorFactory
        )
    }

    internal fun createCoordinator(
        mediaIntegration: PlatformMediaIntegration,
        defaultStateStore: PlaybackStateStore?
    ): PlaybackCoordinator {
        val environment = PipPlaybackCoordinatorEnvironment(
            mediaIntegration = mediaIntegration,
            defaultStateStore = defaultStateStore,
            artworkLoaderFactory = artworkLoaderFactory,
            availableCommands = availableCommands
        )
        return coordinatorFactory?.create(environment) ?: environment.createDefault()
    }

    internal companion object {
        fun default(): PipPlaybackConfiguration = Builder().build()
    }
}

/**
 * Advanced extension point for applications that need to customize coordinator construction.
 *
 * The platform-provided media integration must remain connected to the returned coordinator.
 */
fun interface PipPlaybackCoordinatorFactory {
    fun create(environment: PipPlaybackCoordinatorEnvironment): PlaybackCoordinator
}

/**
 * Platform dependencies and application configuration available to a custom coordinator factory.
 */
class PipPlaybackCoordinatorEnvironment internal constructor(
    val mediaIntegration: PlatformMediaIntegration,
    val defaultStateStore: PlaybackStateStore?,
    val artworkLoaderFactory: PlaybackArtworkLoaderFactory?,
    availableCommands: Set<MediaCommandType>
) {
    val availableCommands: Set<MediaCommandType> = availableCommands.toSet()

    /** Creates the standard coordinator while preserving every configured extension. */
    fun createDefault(): PlaybackCoordinator = PlaybackCoordinator(
        mediaIntegration = mediaIntegration,
        stateStore = defaultStateStore,
        availableCommands = availableCommands,
        artworkLoaderFactory = artworkLoaderFactory
    )
}

/** Builds a reusable PiP playback configuration without installing it. */
fun pipPlaybackConfiguration(
    configure: PipPlaybackConfiguration.Builder.() -> Unit
): PipPlaybackConfiguration = PipPlaybackConfiguration.Builder()
    .apply(configure)
    .build()

/**
 * Installs [configuration] for the application-level PiP playback owner.
 *
 * This is a one-time startup operation and must happen before [rememberPipMpvPlayer] or the
 * platform media service first initializes playback.
 */
fun configurePipPlayback(configuration: PipPlaybackConfiguration) {
    installPlatformPipPlaybackConfiguration(configuration)
}

/** DSL overload of [configurePipPlayback]. */
fun configurePipPlayback(
    configure: PipPlaybackConfiguration.Builder.() -> Unit
) {
    configurePipPlayback(pipPlaybackConfiguration(configure))
}

internal expect fun installPlatformPipPlaybackConfiguration(
    configuration: PipPlaybackConfiguration
)
