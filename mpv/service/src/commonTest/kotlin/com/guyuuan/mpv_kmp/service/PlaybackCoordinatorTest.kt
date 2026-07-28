package com.guyuuan.mpv_kmp.service

import com.guyuuan.mpv_kmp.AbsMpv
import com.guyuuan.mpv_kmp.MpvEventType
import com.guyuuan.mpv_kmp.RenderMode
import com.guyuuan.mpv_kmp.data.MpvEvent
import com.guyuuan.mpv_kmp.data.MpvPlaylistItem
import com.guyuuan.mpv_kmp.props.MpvAudioProperties
import com.guyuuan.mpv_kmp.props.MpvPlaybackProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PlaybackCoordinatorTest {
    @Test
    fun coordinatorOwnsPlayerUntilExplicitClose() {
        val fixture = Fixture()

        assertTrue(fixture.coordinator.start())
        assertSame(fixture.mpv, fixture.coordinator.player)
        assertFalse(fixture.mpv.terminated)
        assertTrue(
            fixture.mpv.observedProperties.containsAll(
                listOf(
                    MpvAudioProperties.VOLUME,
                    MpvPlaybackProperties.PAUSE,
                    MpvPlaybackProperties.SPEED,
                    MpvPlaybackProperties.TIME_POSITION,
                    MpvPlaybackProperties.DURATION,
                    "playlist-pos",
                    "loop-file",
                    "loop-playlist"
                )
            )
        )

        fixture.coordinator.close()

        assertTrue(fixture.mpv.terminated)
        assertEquals(fixture.mpv.observedProperties, fixture.mpv.removedProperties)
        assertEquals(PlaybackStatus.Disposed, fixture.coordinator.snapshot.value.status)
        assertEquals(1, fixture.integration.deactivateCount)
        fixture.coordinator.close()
        assertEquals(1, fixture.integration.deactivateCount)
        fixture.close()
    }

    @Test
    fun metadataAndMpvEventsDrivePlatformState() {
        val fixture = Fixture()
        fixture.coordinator.start()
        val metadata = PlaybackMetadata(
            mediaId = "episode-1",
            uri = "https://example.test/episode.mp3",
            title = "Episode 1",
            artist = "Example",
            mediaType = PlaybackMediaType.Audio
        )

        assertEquals(0, fixture.coordinator.load(metadata))
        assertEquals(metadata.uri, fixture.mpv.loadedUri)
        assertEquals(metadata, fixture.integration.metadata.last())

        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.FileLoaded))
        fixture.mpv.emit(
            MpvEvent(
                type = MpvEventType.PropertyChange,
                name = MpvPlaybackProperties.DURATION,
                value = "125.5"
            )
        )
        fixture.mpv.emit(
            MpvEvent(
                type = MpvEventType.PropertyChange,
                name = MpvPlaybackProperties.TIME_POSITION,
                value = "12.25"
            )
        )

        assertEquals(
            PlaybackSnapshot(
                metadata = metadata,
                status = PlaybackStatus.Playing,
                playWhenReady = true,
                positionMillis = 12_250,
                durationMillis = 125_500,
                queueIndex = 0,
                queueSize = 1
            ),
            fixture.coordinator.snapshot.value
        )
        assertEquals(fixture.coordinator.snapshot.value, fixture.integration.states.last())
        fixture.close()
    }

    @Test
    fun systemCommandsReturnToTheOwnedPlayer() {
        val fixture = Fixture()
        fixture.coordinator.start()
        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.FileLoaded))
        fixture.mpv.emit(
            MpvEvent(
                type = MpvEventType.PropertyChange,
                name = MpvPlaybackProperties.TIME_POSITION,
                value = "20"
            )
        )

        fixture.integration.handler.handle(MediaCommand.Pause)
        fixture.integration.handler.handle(MediaCommand.SeekBy(-5_000))
        fixture.integration.handler.handle(MediaCommand.Next)
        fixture.integration.handler.handle(MediaCommand.SetSpeed(1.5f))
        fixture.integration.handler.handle(MediaCommand.SetVolume(42f))
        fixture.integration.handler.handle(MediaCommand.SetRepeatMode(PlaybackRepeatMode.All))
        fixture.integration.handler.handle(MediaCommand.SetShuffle(true))

        assertEquals(1, fixture.mpv.pauseCount)
        assertEquals(15.0, fixture.mpv.seekPosition)
        assertEquals(1, fixture.mpv.nextCount)
        assertEquals("1.5", fixture.mpv.properties[MpvPlaybackProperties.SPEED])
        assertEquals(42.0, fixture.mpv.volume)
        assertEquals("inf", fixture.mpv.properties["loop-playlist"])
        assertEquals(listOf("playlist-shuffle"), fixture.mpv.commands)
        fixture.close()
    }

    @Test
    fun restoresQueueAndPlaybackSettingsWithoutAutoPlaying() {
        val first = PlaybackMetadata("first", "file:///first.mp3", "First")
        val second = PlaybackMetadata("second", "file:///second.mp3", "Second")
        val store = InMemoryPlaybackStateStore(
            RestorablePlaybackState(
                queue = listOf(first, second),
                currentIndex = 1,
                positionMillis = 45_000,
                speed = 1.25f,
                repeatMode = PlaybackRepeatMode.All,
                shuffleEnabled = true,
                paused = false
            )
        )
        val fixture = Fixture(stateStore = store)
        fixture.coordinator.start()

        assertTrue(fixture.coordinator.restoreSavedPlayback())

        assertEquals(first.uri, fixture.mpv.loadedUri)
        assertEquals(listOf(second.uri), fixture.mpv.addedUris)
        assertEquals("1", fixture.mpv.properties["playlist-pos"])
        assertEquals(45.0, fixture.mpv.seekPosition)
        assertEquals("1.25", fixture.mpv.properties[MpvPlaybackProperties.SPEED])
        assertEquals("inf", fixture.mpv.properties["loop-playlist"])
        assertEquals(listOf("playlist-shuffle"), fixture.mpv.commands)
        assertEquals(second, fixture.coordinator.snapshot.value.metadata)
        assertEquals(1, fixture.coordinator.snapshot.value.queueIndex)
        assertEquals(2, fixture.coordinator.snapshot.value.queueSize)
        assertEquals(0, fixture.mpv.playCount)
        assertTrue(fixture.mpv.pauseCount > 0)
        fixture.close()
    }

    @Test
    fun unavailableSystemCommandIsIgnored() {
        val fixture = Fixture(availableCommands = setOf(MediaCommandType.Play))
        fixture.coordinator.start()

        fixture.integration.handler.handle(MediaCommand.Pause)
        fixture.integration.handler.handle(MediaCommand.Play)

        assertEquals(0, fixture.mpv.pauseCount)
        assertEquals(1, fixture.mpv.playCount)
        fixture.close()
    }

    private class Fixture(
        availableCommands: Set<MediaCommandType> = DEFAULT_MEDIA_COMMANDS,
        stateStore: PlaybackStateStore? = null
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val mpv = FakeMpv()
        val integration = RecordingIntegration()
        val coordinator = PlaybackCoordinator(
            scope = scope,
            mpv = mpv,
            mediaIntegration = integration,
            stateStore = stateStore,
            availableCommands = availableCommands
        )

        fun close() {
            coordinator.close()
            scope.cancel()
        }
    }

    private class RecordingIntegration : PlatformMediaIntegration {
        lateinit var handler: MediaCommandHandler
        val metadata = mutableListOf<PlaybackMetadata?>()
        val states = mutableListOf<PlaybackSnapshot>()
        var deactivateCount = 0

        override fun activate(commandHandler: MediaCommandHandler) {
            handler = commandHandler
        }

        override fun updateMetadata(metadata: PlaybackMetadata?) {
            this.metadata += metadata
        }

        override fun updatePlaybackState(state: PlaybackSnapshot) {
            states += state
        }

        override fun deactivate() {
            deactivateCount += 1
        }
    }

    private class FakeMpv : AbsMpv() {
        override val renderMode: RenderMode = RenderMode.Software
        var terminated = false
        var loadedUri: String? = null
        var pauseCount = 0
        var playCount = 0
        var nextCount = 0
        var seekPosition: Double? = null
        var volume: Double? = null
        val properties = mutableMapOf<String, String>()
        val commands = mutableListOf<String>()
        val addedUris = mutableListOf<String>()
        val observedProperties = mutableListOf<String>()
        val removedProperties = mutableListOf<String>()

        fun emit(event: MpvEvent) {
            listeners.toList().forEach { it(event) }
        }

        override fun initialize(): Boolean = true
        override fun attach(view: Any) = Unit
        override fun detach() = Unit
        override fun commandString(cmd: String): Int {
            commands += cmd
            return 0
        }
        override fun load(uri: String): Int {
            loadedUri = uri
            return 0
        }
        override fun addToPlaylist(uri: String): Int {
            addedUris += uri
            return 0
        }
        override fun addExternalSubtitle(uri: String): Int = 0
        override fun getPlaylist(): List<MpvPlaylistItem> = emptyList()
        override fun removeFromPlaylist(index: Int): Int = 0
        override fun playlistNext(): Int {
            nextCount += 1
            return 0
        }
        override fun playlistPrev(): Int = 0
        override fun playlistClear(): Int = 0
        override fun seekTo(position: Double): Int {
            seekPosition = position
            return 0
        }
        override fun setCoroutineScope(scope: CoroutineScope) = Unit
        override fun observeProperty(name: String) {
            observedProperties += name
        }
        override fun removePropertyObservation(name: String) {
            removedProperties += name
        }
        override fun play(): Int {
            playCount += 1
            return 0
        }
        override fun pause(): Int {
            pauseCount += 1
            return 0
        }
        override fun stop(): Int = 0
        override fun setVolume(volume: Double): Int {
            this.volume = volume
            return 0
        }
        override fun setProperty(name: String, value: String): Int {
            properties[name] = value
            return 0
        }
        override fun getProperty(name: String): String? = properties[name]
        override fun terminate() {
            terminated = true
        }
        override fun startEventLoop() = Unit
    }
}
