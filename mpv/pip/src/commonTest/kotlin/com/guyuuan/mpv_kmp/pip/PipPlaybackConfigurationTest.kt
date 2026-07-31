package com.guyuuan.mpv_kmp.pip

import com.guyuuan.mpv_kmp.service.InMemoryPlaybackStateStore
import com.guyuuan.mpv_kmp.service.MediaCommandType
import com.guyuuan.mpv_kmp.service.NoopPlatformMediaIntegration
import com.guyuuan.mpv_kmp.service.PlaybackArtworkLoaderFactory
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
        var receivedEnvironment: PipPlaybackCoordinatorEnvironment? = null
        val configuration = pipPlaybackConfiguration {
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
        assertSame(loaderFactory, receivedEnvironment?.artworkLoaderFactory)
        assertEquals(
            setOf(MediaCommandType.Play),
            receivedEnvironment?.availableCommands
        )
    }
}
