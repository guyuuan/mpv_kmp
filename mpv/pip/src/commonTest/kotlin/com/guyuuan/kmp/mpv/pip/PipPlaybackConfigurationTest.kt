package com.guyuuan.kmp.mpv.pip

import com.guyuuan.kmp.mpv.config.FontConfig
import com.guyuuan.kmp.mpv.config.MpvConfig
import com.guyuuan.kmp.mpv.service.InMemoryPlaybackStateStore
import com.guyuuan.kmp.mpv.service.MediaCommandType
import com.guyuuan.kmp.mpv.service.NoopPlatformMediaIntegration
import com.guyuuan.kmp.mpv.service.PlaybackArtworkLoaderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PipPlaybackConfigurationTest {
    @Test
    fun builderTakesAnImmutableSnapshotOfCommands() {
        val commands = mutableSetOf(MediaCommandType.Play, MediaCommandType.Pause)
        val configuration = pipPlaybackConfiguration {
            availableCommands = commands
        }

        commands.clear()

        assertEquals(
            setOf(MediaCommandType.Play, MediaCommandType.Pause),
            configuration.availableCommands
        )
    }

    @Test
    fun customCoordinatorFactoryReceivesPlatformAndArtworkDependencies() {
        val loaderFactory = PlaybackArtworkLoaderFactory {
            error("The coordinator factory test must not create a loader")
        }
        val stateStore = InMemoryPlaybackStateStore()
        val expectedFailure = IllegalStateException("factory invoked")
        val mpvConfig = MpvConfig(
            fontConfig = FontConfig(subFont = "Noto Sans CJK SC")
        )
        var receivedEnvironment: PipPlaybackCoordinatorEnvironment? = null
        val configuration = pipPlaybackConfiguration {
            this.mpvConfig = mpvConfig
            artworkLoaderFactory = loaderFactory
            availableCommands = setOf(MediaCommandType.Play)
            coordinatorFactory = PipPlaybackCoordinatorFactory { environment ->
                receivedEnvironment = environment
                throw expectedFailure
            }
        }

        val actualFailure = assertFailsWith<IllegalStateException> {
            configuration.createCoordinator(
                mediaIntegration = NoopPlatformMediaIntegration,
                defaultStateStore = stateStore
            )
        }

        assertSame(expectedFailure, actualFailure)
        assertSame(NoopPlatformMediaIntegration, receivedEnvironment?.mediaIntegration)
        assertSame(stateStore, receivedEnvironment?.defaultStateStore)
        assertSame(mpvConfig, receivedEnvironment?.mpvConfig)
        assertSame(loaderFactory, receivedEnvironment?.artworkLoaderFactory)
        assertEquals(
            setOf(MediaCommandType.Play),
            receivedEnvironment?.availableCommands
        )
    }
}
