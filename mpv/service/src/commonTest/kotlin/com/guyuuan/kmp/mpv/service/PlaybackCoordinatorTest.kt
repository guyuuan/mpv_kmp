package com.guyuuan.kmp.mpv.service

import com.guyuuan.kmp.mpv.AbsMpv
import com.guyuuan.kmp.mpv.MpvEventType
import com.guyuuan.kmp.mpv.RenderMode
import com.guyuuan.kmp.mpv.data.MpvEvent
import com.guyuuan.kmp.mpv.data.MpvPlaylistItem
import com.guyuuan.kmp.mpv.props.MpvAudioProperties
import com.guyuuan.kmp.mpv.props.MpvPlaybackProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
        fixture.mpv.emit(
            MpvEvent(
                type = MpvEventType.PropertyChange,
                name = "video-out-params/dw",
                value = "1920"
            )
        )
        fixture.mpv.emit(
            MpvEvent(
                type = MpvEventType.PropertyChange,
                name = "video-out-params/dh",
                value = "1080"
            )
        )

        assertEquals(
            PlaybackSnapshot(
                metadata = metadata,
                status = PlaybackStatus.Playing,
                playWhenReady = true,
                positionMillis = 12_250,
                durationMillis = 125_500,
                videoWidth = 1920,
                videoHeight = 1080,
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

    @Test
    fun uriArtworkIsResolvedWithoutPlatformImageTypes() = runBlocking {
        val requestedArtwork = CompletableDeferred<PlaybackArtwork.Uri>()
        val response = CompletableDeferred<ByteArray?>()
        var loaderCloseCount = 0
        val loader = object : AbstractPlaybackArtworkLoader() {
            override suspend fun loadBytes(artwork: PlaybackArtwork.Uri): ByteArray? {
                requestedArtwork.complete(artwork)
                return response.await()
            }

            override fun onClosed() {
                loaderCloseCount += 1
            }
        }
        var factoryCreateCount = 0
        val factory = PlaybackArtworkLoaderFactory {
            factoryCreateCount += 1
            loader
        }
        val fixture = Fixture(artworkLoaderFactory = factory)
        assertEquals(1, factoryCreateCount)
        fixture.coordinator.start()
        fixture.coordinator.start()
        assertEquals(1, factoryCreateCount)
        val artwork = PlaybackArtwork.Uri("https://example.test/cover.jpg")
        val metadata = PlaybackMetadata(
            mediaId = "episode-with-artwork",
            uri = "https://example.test/episode.mp3",
            title = "Episode",
            artwork = artwork
        )

        fixture.coordinator.load(metadata)

        assertEquals(artwork, withTimeout(5_000) { requestedArtwork.await() })
        assertEquals(metadata, fixture.integration.metadata.last())

        val bytes = byteArrayOf(1, 2, 3, 4)
        response.complete(bytes)
        val resolved = withTimeout(5_000) { fixture.integration.resolvedMetadata.await() }
        assertEquals(PlaybackArtwork.Bytes(bytes), resolved.artwork)

        fixture.mpv.emit(
            MpvEvent(
                type = MpvEventType.PropertyChange,
                name = MpvPlaybackProperties.SPEED,
                value = "1.25"
            )
        )
        assertEquals(
            artwork,
            fixture.integration.states.last().metadata?.artwork
        )
        assertEquals(artwork, fixture.coordinator.snapshot.value.metadata?.artwork)
        fixture.close()
        fixture.close()
        assertEquals(1, loaderCloseCount)
    }

    @Test
    fun obsoleteArtworkRequestIsCancelledBeforeItCanReplaceCurrentMedia() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondBytes = byteArrayOf(5, 6, 7, 8)
        val loader = object : AbstractPlaybackArtworkLoader() {
            override suspend fun loadBytes(artwork: PlaybackArtwork.Uri): ByteArray? {
                return if (artwork.value.endsWith("first.jpg")) {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled.complete(Unit)
                    }
                } else {
                    secondBytes
                }
            }
        }
        val fixture = Fixture(
            artworkLoaderFactory = PlaybackArtworkLoaderFactory { loader }
        )
        fixture.coordinator.start()
        val first = PlaybackMetadata(
            mediaId = "first",
            uri = "https://example.test/first.mp3",
            title = "First",
            artwork = PlaybackArtwork.Uri("https://example.test/first.jpg")
        )
        val second = PlaybackMetadata(
            mediaId = "second",
            uri = "https://example.test/second.mp3",
            title = "Second",
            artwork = PlaybackArtwork.Uri("https://example.test/second.jpg")
        )

        fixture.coordinator.load(first)
        withTimeout(5_000) { firstStarted.await() }
        fixture.coordinator.updateMetadata(second)

        withTimeout(5_000) { firstCancelled.await() }
        val resolved = withTimeout(5_000) { fixture.integration.resolvedMetadata.await() }
        assertEquals("second", resolved.mediaId)
        assertEquals(PlaybackArtwork.Bytes(secondBytes), resolved.artwork)
        assertFalse(
            fixture.integration.metadata.any {
                it?.mediaId == "first" && it.artwork is PlaybackArtwork.Bytes
            }
        )
        fixture.close()
    }

    @Test
    fun artworkResultDeliveryIsSerializedWithRequestReplacement() = runBlocking {
        val firstCallbackStarted = CompletableDeferred<Unit>()
        val releaseFirstCallback = CompletableDeferred<Unit>()
        val secondDelivered = CompletableDeferred<Unit>()
        val loader = object : AbstractPlaybackArtworkLoader() {
            override suspend fun loadBytes(artwork: PlaybackArtwork.Uri): ByteArray =
                if (artwork.value.endsWith("first.jpg")) {
                    byteArrayOf(1)
                } else {
                    byteArrayOf(2)
                }
        }
        val first = PlaybackMetadata(
            mediaId = "first",
            uri = "file:///first.mp3",
            title = "First",
            artwork = PlaybackArtwork.Uri("https://example.test/first.jpg")
        )
        val second = PlaybackMetadata(
            mediaId = "second",
            uri = "file:///second.mp3",
            title = "Second",
            artwork = PlaybackArtwork.Uri("https://example.test/second.jpg")
        )

        loader.load(first) {
            firstCallbackStarted.complete(Unit)
            runBlocking { releaseFirstCallback.await() }
        }
        withTimeout(5_000) { firstCallbackStarted.await() }
        val replaceRequest = async(Dispatchers.Default) {
            loader.load(second) { secondDelivered.complete(Unit) }
        }

        val deliveredBeforeFirstCallbackFinished = withTimeoutOrNull(100) {
            secondDelivered.await()
        }
        releaseFirstCallback.complete(Unit)
        replaceRequest.await()
        withTimeout(5_000) { secondDelivered.await() }

        assertNull(deliveredBeforeFirstCallbackFinished)
        loader.close()
    }

    @Test
    fun loaderCloseFailureDoesNotSkipCoordinatorCleanup() {
        val loader = object : AbstractPlaybackArtworkLoader() {
            override suspend fun loadBytes(artwork: PlaybackArtwork.Uri): ByteArray? = null

            override fun onClosed() {
                error("Loader close failed")
            }
        }
        val fixture = Fixture(
            artworkLoaderFactory = PlaybackArtworkLoaderFactory { loader }
        )
        fixture.coordinator.start()

        val failure = assertFailsWith<IllegalStateException> {
            fixture.coordinator.close()
        }

        assertEquals("Loader close failed", failure.message)
        assertTrue(fixture.coordinator.isClosed)
        assertTrue(fixture.mpv.terminated)
        assertEquals(1, fixture.integration.deactivateCount)
    }

    private class Fixture(
        availableCommands: Set<MediaCommandType> = DEFAULT_MEDIA_COMMANDS,
        stateStore: PlaybackStateStore? = null,
        artworkLoaderFactory: PlaybackArtworkLoaderFactory? = null
    ) {
        val mpv = FakeMpv()
        val integration = RecordingIntegration()
        val coordinator = PlaybackCoordinator(
            mpv = mpv,
            mediaIntegration = integration,
            stateStore = stateStore,
            availableCommands = availableCommands,
            artworkLoaderFactory = artworkLoaderFactory
        )

        fun close() {
            coordinator.close()
        }
    }

    private class RecordingIntegration : PlatformMediaIntegration {
        lateinit var handler: MediaCommandHandler
        val metadata = mutableListOf<PlaybackMetadata?>()
        val states = mutableListOf<PlaybackSnapshot>()
        val resolvedMetadata = CompletableDeferred<PlaybackMetadata>()
        var deactivateCount = 0

        override fun activate(commandHandler: MediaCommandHandler) {
            handler = commandHandler
        }

        override fun updateMetadata(metadata: PlaybackMetadata?) {
            this.metadata += metadata
            if (metadata?.artwork is PlaybackArtwork.Bytes) {
                resolvedMetadata.complete(metadata)
            }
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
