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
    fun insertsMetadataAtRequestedPlaylistPosition() {
        val fixture = Fixture()
        fixture.coordinator.start()
        val first = PlaybackMetadata("first", "file:///first.mp3", "First")
        val second = PlaybackMetadata("second", "file:///second.mp3", "Second")
        val inserted = PlaybackMetadata("inserted", "file:///inserted.mp3", "Inserted")
        assertEquals(
            0,
            fixture.coordinator.setQueue(
                items = listOf(first, second),
                currentIndex = 1,
                playWhenReady = false
            )
        )

        assertEquals(0, fixture.coordinator.addToPlaylist(inserted, position = 1))

        assertEquals(listOf(first, inserted, second), fixture.coordinator.queueItems)
        assertEquals(listOf(first.uri, inserted.uri, second.uri), fixture.mpv.playlistUris)
        assertEquals(2, fixture.coordinator.snapshot.value.queueIndex)
        assertEquals(3, fixture.coordinator.snapshot.value.queueSize)
        assertSame(second, fixture.coordinator.snapshot.value.metadata)
        fixture.close()
    }

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
                    MpvPlaybackProperties.CACHE_BUFFERING_STATE,
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
        assertEquals(1, fixture.mpv.pauseCount)
        assertEquals(0, fixture.mpv.playCount)

        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.FileLoaded))
        assertEquals(1, fixture.mpv.playCount)
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
                name = MpvPlaybackProperties.CACHE_BUFFERING_STATE,
                value = "42"
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
                bufferingProgress = 0.42f,
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
    fun successfulStopClearsPublishedMediaInformation() {
        val fixture = Fixture()
        fixture.coordinator.start()
        val metadata = PlaybackMetadata(
            mediaId = "episode-1",
            uri = "https://example.test/episode.mp3",
            title = "Episode 1"
        )
        fixture.coordinator.load(metadata)

        assertEquals(0, fixture.coordinator.execute(MediaCommand.Stop))

        assertNull(fixture.coordinator.snapshot.value.metadata)
        assertEquals(PlaybackStatus.Stopped, fixture.coordinator.snapshot.value.status)
        assertFalse(fixture.coordinator.snapshot.value.playWhenReady)
        assertNull(fixture.integration.metadata.last())
        assertEquals(listOf(metadata), fixture.coordinator.queueItems)
        fixture.close()
    }

    @Test
    fun queuedPlaybackEventsAfterStopDoNotRestoreClearedMediaState() {
        val fixture = Fixture()
        fixture.coordinator.start()
        val metadata = PlaybackMetadata(
            mediaId = "episode-1",
            uri = "https://example.test/episode.mp3",
            title = "Episode 1"
        )
        fixture.coordinator.load(metadata)
        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.FileLoaded))

        assertEquals(0, fixture.coordinator.execute(MediaCommand.Stop))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.Unpause))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.PlaybackRestart))
        fixture.mpv.emit(
            MpvEvent(
                type = MpvEventType.PropertyChange,
                name = "playlist-pos",
                value = "0"
            )
        )

        assertNull(fixture.coordinator.snapshot.value.metadata)
        assertEquals(PlaybackStatus.Stopped, fixture.coordinator.snapshot.value.status)
        assertFalse(fixture.coordinator.snapshot.value.playWhenReady)
        assertNull(fixture.integration.metadata.last())
        fixture.close()
    }

    @Test
    fun pauseDuringQueueLoadingOverridesAutoPlay() {
        val fixture = Fixture()
        fixture.coordinator.start()
        val metadata = PlaybackMetadata("first", "file:///first.mp3", "First")

        assertEquals(0, fixture.coordinator.setQueue(listOf(metadata), playWhenReady = true))
        assertEquals(0, fixture.coordinator.execute(MediaCommand.Pause))

        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.FileLoaded))

        assertEquals(0, fixture.mpv.playCount)
        assertEquals(PlaybackStatus.Paused, fixture.coordinator.snapshot.value.status)
        assertFalse(fixture.coordinator.snapshot.value.playWhenReady)
        fixture.close()
    }

    @Test
    fun playDuringPausedQueueLoadingOverridesInitialIntent() {
        val fixture = Fixture()
        fixture.coordinator.start()
        val metadata = PlaybackMetadata("first", "file:///first.mp3", "First")

        assertEquals(0, fixture.coordinator.setQueue(listOf(metadata), playWhenReady = false))
        assertEquals(0, fixture.coordinator.execute(MediaCommand.Play))

        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.FileLoaded))

        assertEquals(2, fixture.mpv.playCount)
        assertEquals(PlaybackStatus.Playing, fixture.coordinator.snapshot.value.status)
        assertTrue(fixture.coordinator.snapshot.value.playWhenReady)
        fixture.close()
    }

    @Test
    fun previousFileEndingDoesNotCancelPendingQueuePlayback() {
        val fixture = Fixture()
        fixture.coordinator.start()
        val metadata = PlaybackMetadata("first", "file:///first.mp3", "First")

        assertEquals(0, fixture.coordinator.setQueue(listOf(metadata), playWhenReady = true))

        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.EndFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.FileLoaded))

        assertEquals(1, fixture.mpv.playCount)
        assertEquals(PlaybackStatus.Playing, fixture.coordinator.snapshot.value.status)
        assertTrue(fixture.coordinator.snapshot.value.playWhenReady)
        fixture.close()
    }

    @Test
    fun replacingQueueKeepsLatestPendingPlaybackIntent() {
        val fixture = Fixture()
        fixture.coordinator.start()
        val old = PlaybackMetadata("old", "file:///old.mp3", "Old")
        val new = PlaybackMetadata("new", "file:///new.mp3", "New")

        assertEquals(0, fixture.coordinator.setQueue(listOf(old), playWhenReady = false))
        assertEquals(0, fixture.coordinator.setQueue(listOf(new), playWhenReady = true))

        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.EndFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.FileLoaded))

        assertEquals(new.uri, fixture.mpv.loadedUri)
        assertEquals(1, fixture.mpv.playCount)
        assertEquals(PlaybackStatus.Playing, fixture.coordinator.snapshot.value.status)
        assertTrue(fixture.coordinator.snapshot.value.playWhenReady)
        fixture.close()
    }

    @Test
    fun failedQueueItemCarriesPlaybackIntentToNextItem() {
        val fixture = Fixture()
        fixture.coordinator.start()
        val metadata = PlaybackMetadata("first", "file:///first.mp3", "First")

        assertEquals(0, fixture.coordinator.setQueue(listOf(metadata), playWhenReady = true))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.EndFile, error = -1))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.StartFile))
        fixture.mpv.emit(MpvEvent(type = MpvEventType.FileLoaded))

        assertEquals(1, fixture.mpv.playCount)
        assertEquals(PlaybackStatus.Playing, fixture.coordinator.snapshot.value.status)
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
        fixture.integration.handler.handle(MediaCommand.Previous)
        fixture.integration.handler.handle(MediaCommand.SetSpeed(1.5f))
        fixture.integration.handler.handle(MediaCommand.SetVolume(42f))
        fixture.integration.handler.handle(MediaCommand.SetRepeatMode(PlaybackRepeatMode.All))
        fixture.integration.handler.handle(MediaCommand.SetShuffle(true))

        assertEquals(1, fixture.mpv.pauseCount)
        assertEquals(15.0, fixture.mpv.seekPosition)
        assertEquals(1, fixture.mpv.nextCount)
        assertEquals(1, fixture.mpv.previousCount)
        assertEquals("1.5", fixture.mpv.properties[MpvPlaybackProperties.SPEED])
        assertEquals(42.0, fixture.mpv.volume)
        assertEquals("inf", fixture.mpv.properties["loop-playlist"])
        assertEquals(listOf("playlist-shuffle"), fixture.mpv.commands)
        fixture.close()
    }

    @Test
    fun navigationHandlerCanBeAddedAndRemovedWithoutUsingMpvPlaylist() {
        val received = mutableListOf<MediaCommand>()
        val fixture = Fixture()
        val handler = object : PlaybackNavigationHandler {
            override fun onPrevious() {
                received += MediaCommand.Previous
            }

            override fun onNext() {
                received += MediaCommand.Next
            }
        }
        fixture.coordinator.start()

        assertTrue(fixture.coordinator.addNavigationHandler(handler))
        assertFalse(fixture.coordinator.addNavigationHandler(handler))
        assertEquals(0, fixture.coordinator.execute(MediaCommand.Next))
        assertEquals(0, fixture.coordinator.execute(MediaCommand.Previous))

        assertEquals(listOf(MediaCommand.Next, MediaCommand.Previous), received)
        assertEquals(0, fixture.mpv.nextCount)
        assertEquals(0, fixture.mpv.previousCount)

        assertTrue(fixture.coordinator.removeNavigationHandler(handler))
        assertFalse(fixture.coordinator.removeNavigationHandler(handler))
        assertEquals(0, fixture.coordinator.execute(MediaCommand.Next))
        assertEquals(1, fixture.mpv.nextCount)
        fixture.close()
    }

    @Test
    fun secondNavigationHandlerIsRejectedUntilFirstIsRemoved() {
        val fixture = Fixture()
        var firstCount = 0
        var secondCount = 0
        val first = navigationHandler(onNext = { firstCount += 1 })
        val second = navigationHandler(onNext = { secondCount += 1 })
        fixture.coordinator.start()
        assertTrue(fixture.coordinator.addNavigationHandler(first))
        assertFalse(fixture.coordinator.addNavigationHandler(second))

        assertEquals(0, fixture.coordinator.execute(MediaCommand.Next))

        assertEquals(1, firstCount)
        assertEquals(0, secondCount)
        assertEquals(0, fixture.mpv.nextCount)

        assertFalse(fixture.coordinator.removeNavigationHandler(second))
        assertTrue(fixture.coordinator.removeNavigationHandler(first))
        assertTrue(fixture.coordinator.addNavigationHandler(second))
        assertEquals(0, fixture.coordinator.execute(MediaCommand.Next))
        assertEquals(1, firstCount)
        assertEquals(1, secondCount)
        fixture.close()
    }

    @Test
    fun unavailableNavigationCommandDoesNotReachNavigationHandler() {
        var nextCount = 0
        val fixture = Fixture(
            availableCommands = DEFAULT_MEDIA_COMMANDS - MediaCommandType.Next
        )
        val handler = object : PlaybackNavigationHandler {
            override fun onPrevious() = Unit

            override fun onNext() {
                nextCount += 1
            }
        }
        fixture.coordinator.start()
        fixture.coordinator.addNavigationHandler(handler)

        assertEquals(0, fixture.coordinator.execute(MediaCommand.Next))

        assertEquals(0, nextCount)
        assertEquals(0, fixture.mpv.nextCount)
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
        artworkLoaderFactory: PlaybackArtworkLoaderFactory? = null
    ) {
        val mpv = FakeMpv()
        val integration = RecordingIntegration()
        val coordinator = PlaybackCoordinator(
            mpv = mpv,
            mediaIntegration = integration,
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

    private fun navigationHandler(
        onPrevious: () -> Unit = {},
        onNext: () -> Unit = {}
    ): PlaybackNavigationHandler = object : PlaybackNavigationHandler {
        override fun onPrevious() = onPrevious()
        override fun onNext() = onNext()
    }

    private class FakeMpv : AbsMpv() {
        override val renderMode: RenderMode = RenderMode.Software
        var terminated = false
        var loadedUri: String? = null
        var pauseCount = 0
        var playCount = 0
        var nextCount = 0
        var previousCount = 0
        var seekPosition: Double? = null
        var volume: Double? = null
        val properties = mutableMapOf<String, String>()
        val commands = mutableListOf<String>()
        val addedItems = mutableListOf<Pair<String, Int?>>()
        val playlistUris = mutableListOf<String>()
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
            playlistUris.clear()
            playlistUris += uri
            return 0
        }
        override fun addToPlaylist(uri: String, position: Int?): Int {
            addedItems += uri to position
            if (position == null || position !in 0..playlistUris.size) {
                playlistUris += uri
            } else {
                playlistUris.add(position, uri)
            }
            return 0
        }
        override fun addExternalSubtitle(uri: String): Int = 0
        override fun getPlaylist(): List<MpvPlaylistItem> = emptyList()
        override fun removeFromPlaylist(index: Int): Int = 0
        override fun playlistNext(): Int {
            nextCount += 1
            return 0
        }
        override fun playlistPrev(): Int {
            previousCount += 1
            return 0
        }
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
