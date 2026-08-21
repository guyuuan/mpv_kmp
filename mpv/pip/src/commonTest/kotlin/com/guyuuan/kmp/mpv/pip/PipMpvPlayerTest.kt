package com.guyuuan.kmp.mpv.pip

import com.guyuuan.kmp.mpv.AbsMpv
import com.guyuuan.kmp.mpv.MpvPlayer
import com.guyuuan.kmp.mpv.MpvPlayerCapability
import com.guyuuan.kmp.mpv.MpvPlaylistController
import com.guyuuan.kmp.mpv.MpvPlayerSnapshot
import com.guyuuan.kmp.mpv.MpvPlayerState
import com.guyuuan.kmp.mpv.MpvVideoOutput
import com.guyuuan.kmp.mpv.data.MpvDecoderInfo
import com.guyuuan.kmp.mpv.data.MpvPlaylistItem
import com.guyuuan.kmp.mpv.service.PlaybackArtwork
import com.guyuuan.kmp.mpv.service.PlaybackCoordinator
import com.guyuuan.kmp.mpv.service.PlaybackMediaType
import com.guyuuan.kmp.mpv.service.PlaybackMetadata
import com.guyuuan.kmp.mpv.service.PlaybackNavigationHandler
import com.guyuuan.kmp.mpv.service.PlaybackSnapshot
import com.guyuuan.kmp.mpv.service.PlaybackStatus
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

class PipMpvPlayerTest {
    @Test
    fun exposesPipCapabilityOnlyWhenPlatformIsAvailable() {
        val available = FakePictureInPictureController(
            PictureInPictureAvailability.Available
        )
        val unsupported = FakePictureInPictureController(
            PictureInPictureAvailability.UnsupportedVideoOutput
        )

        assertTrue(
            MpvPlayerCapability.PictureInPicture in
                PipMpvPlayer(FakePlayer, available).capabilities
        )
        assertFalse(
            MpvPlayerCapability.PictureInPicture in
                PipMpvPlayer(FakePlayer, unsupported).capabilities
        )
    }

    @Test
    fun closeReleasesConnectionOnlyOnce() {
        val controller = FakePictureInPictureController(
            PictureInPictureAvailability.Available
        )
        var releaseCount = 0
        val player = PipMpvPlayer(FakePlayer, controller) {
            releaseCount++
        }

        player.close()
        player.close()

        assertEquals(1, releaseCount)
    }

    @Test
    fun forwardsNavigationHandlerRegistrationAndCleansUpOnClose() {
        val controller = FakePictureInPictureController(
            PictureInPictureAvailability.Available
        )
        val delegate = FakeNavigationPlayer()
        val player = PipMpvPlayer(delegate, controller)
        val handler = object : PlaybackNavigationHandler {
            override fun onPrevious() = Unit
            override fun onNext() = Unit
        }

        assertTrue(player.addNavigationHandler(handler))
        assertFalse(player.addNavigationHandler(handler))
        assertTrue(delegate.navigationHandler === handler)

        assertTrue(player.removeNavigationHandler(handler))
        assertFalse(player.removeNavigationHandler(handler))
        assertTrue(delegate.navigationHandler == null)

        assertTrue(player.addNavigationHandler(handler))
        player.close()

        assertTrue(delegate.navigationHandler == null)
        assertFalse(player.addNavigationHandler(handler))
    }

    @Test
    fun userCanRequestPictureInPictureThroughPlayer() {
        val controller = FakePictureInPictureController(
            availability = PictureInPictureAvailability.Available,
            requestStartResult = true
        )
        val player = PipMpvPlayer(FakePlayer, controller)

        assertTrue(player.enterPictureInPicture())
        assertEquals(1, controller.requestStartCount)

        player.close()

        assertFalse(player.enterPictureInPicture())
        assertEquals(1, controller.requestStartCount)
    }

    @Test
    fun platformCanReplaceDelegatesVideoOutput() {
        val controller = FakePictureInPictureController(
            PictureInPictureAvailability.Available
        )
        val pipVideoOutput = object : MpvVideoOutput {}

        val player = PipMpvPlayer(
            delegate = FakePlayer,
            pictureInPicture = controller,
            videoOutput = pipVideoOutput
        )

        assertSame(pipVideoOutput, player.videoOutput)
    }

    @Test
    fun forwardsRichMetadataToCoordinatorBackedDelegate() {
        val controller = FakePictureInPictureController(
            PictureInPictureAvailability.Available
        )
        val delegate = FakeMetadataPlayer()
        val player = PipMpvPlayer(delegate, controller)
        val metadata = PlaybackMetadata(
            mediaId = "episode-1",
            uri = "https://example.com/video.mp4",
            title = "Episode 1",
            artwork = PlaybackArtwork.Uri("https://example.com/artwork.jpg"),
            mediaType = PlaybackMediaType.Video
        )
        val updated = metadata.copy(title = "Updated Episode 1")

        assertEquals(RESULT_CODE, player.load(metadata))
        assertSame(metadata, delegate.loadedMetadata)

        assertEquals(RESULT_CODE, player.addToPlayList(listOf(metadata), 0, false))
        assertEquals(listOf(metadata), delegate.queuedMetadata)
        assertEquals(0, delegate.queueIndex)
        assertFalse(delegate.queuePlayWhenReady)

        assertEquals(RESULT_CODE, player.addToPlaylist(updated, position = 2))
        assertSame(updated, delegate.insertedMetadata)
        assertEquals(2, delegate.insertionPosition)

        player.updateMetadata(updated)
        assertSame(updated, delegate.updatedMetadata)
    }

    @Test
    fun forwardsUriQueueToLocalPlaylistController() {
        val controller = FakePictureInPictureController(
            PictureInPictureAvailability.UnsupportedPlatform
        )
        val delegate = FakePlaylistPlayer()
        val player = PipMpvPlayer(delegate, controller)
        val metadata = listOf(
            PlaybackMetadata("first", "file:///first.mp4", "First"),
            PlaybackMetadata("second", "file:///second.mp4", "Second")
        )

        assertEquals(RESULT_CODE, player.addToPlayList(metadata, 1, false))
        assertEquals(metadata.map(PlaybackMetadata::uri), delegate.queuedUris)
        assertEquals(1, delegate.queueIndex)
        assertFalse(delegate.queuePlayWhenReady)

        assertEquals(RESULT_CODE, player.addToPlaylist(metadata.first(), position = 1))
        assertEquals(metadata.first().uri, delegate.insertedUri)
        assertEquals(1, delegate.insertionPosition)
    }

    @Test
    fun mapsCoordinatorSnapshotToSharedPlayerSnapshot() {
        val snapshot = PlaybackSnapshot(
            status = PlaybackStatus.Playing,
            positionMillis = 12_500,
            durationMillis = 90_000,
            volume = 42f,
            speed = 1.5f,
            queueIndex = 1,
            queueSize = 3
        )

        assertEquals(
            MpvPlayerSnapshot(
                state = MpvPlayerState.Playing,
                positionSeconds = 12.5,
                durationSeconds = 90.0,
                volume = 42f,
                speed = 1.5f,
                canPrevious = true,
                canNext = true
            ),
            snapshot.toMpvPlayerSnapshot()
        )
    }

    @Test
    fun navigationUsesCoordinatorSnapshotBeforeMirroredSnapshotCollects() {
        val mpv = FakeMpv()
        val coordinator = PlaybackCoordinator(mpv = mpv)
        assertTrue(coordinator.start())
        val player = PlaybackCoordinatorMpvPlayer(
            coordinator = coordinator,
            videoOutput = object : MpvVideoOutput {},
            scope = CoroutineScope(Job() + QueuedDispatcher())
        )

        assertEquals(
            0,
            player.addToPlayList(
                listOf(
                    PlaybackMetadata("first", "file:///first", "First"),
                    PlaybackMetadata("second", "file:///second", "Second")
                ),
                currentIndex = 0,
                playWhenReady = false
            )
        )
        assertFalse(player.snapshot.value.canNext)
        assertTrue(player.canNext())
        assertTrue(player.next())
        assertEquals(1, mpv.playlistNextCount)

        player.close()
        coordinator.close()
    }

    @Test
    fun externalNavigationHandlerEnablesSingleItemPlayerNavigation() {
        val mpv = FakeMpv()
        val coordinator = PlaybackCoordinator(mpv = mpv)
        assertTrue(coordinator.start())
        val player = PlaybackCoordinatorMpvPlayer(
            coordinator = coordinator,
            videoOutput = object : MpvVideoOutput {},
            scope = CoroutineScope(Job() + QueuedDispatcher())
        )
        var previousCount = 0
        var nextCount = 0
        val handler = object : PlaybackNavigationHandler {
            override fun onPrevious() {
                previousCount += 1
            }

            override fun onNext() {
                nextCount += 1
            }
        }
        assertEquals(
            0,
            player.load(PlaybackMetadata("only", "file:///only", "Only"))
        )
        assertFalse(player.canPrevious())
        assertFalse(player.canNext())

        assertTrue(player.addNavigationHandler(handler))

        assertTrue(player.snapshot.value.canPrevious)
        assertTrue(player.snapshot.value.canNext)
        assertTrue(player.previous())
        assertTrue(player.next())
        assertEquals(1, previousCount)
        assertEquals(1, nextCount)
        assertEquals(0, mpv.playlistNextCount)

        assertTrue(player.removeNavigationHandler(handler))
        assertFalse(player.snapshot.value.canPrevious)
        assertFalse(player.snapshot.value.canNext)
        assertFalse(player.previous())
        assertFalse(player.next())

        player.close()
        coordinator.close()
    }

    private object FakePlayer : MpvPlayer {
        override val snapshot: StateFlow<MpvPlayerSnapshot> =
            MutableStateFlow(MpvPlayerSnapshot())
        override val capabilities: Set<MpvPlayerCapability> = emptySet()
        override val videoOutput: MpvVideoOutput = object : MpvVideoOutput {}
        override val decoderInfoFlow: Flow<MpvDecoderInfo> = emptyFlow()

        override fun load(uri: String): Int = 0
        override fun play(): Int = 0
        override fun pause(): Int = 0
        override fun stop(): Int = 0
        override fun seek(positionSeconds: Double): Int = 0
        override fun setVolume(volume: Double): Int = 0
        override fun setSpeed(speed: Float): Int = 0
    }

    private class FakeMetadataPlayer :
        MpvPlayer by FakePlayer,
        PlaybackMetadataController {
        var loadedMetadata: PlaybackMetadata? = null
            private set
        var updatedMetadata: PlaybackMetadata? = null
            private set
        var queuedMetadata: List<PlaybackMetadata>? = null
            private set
        var queueIndex: Int? = null
            private set
        var queuePlayWhenReady = true
            private set
        var insertedMetadata: PlaybackMetadata? = null
            private set
        var insertionPosition: Int? = null
            private set

        override fun load(metadata: PlaybackMetadata): Int {
            loadedMetadata = metadata
            return RESULT_CODE
        }

        override fun addToPlayList(
            metadata: List<PlaybackMetadata>,
            currentIndex: Int,
            playWhenReady: Boolean
        ): Int {
            queuedMetadata = metadata
            queueIndex = currentIndex
            queuePlayWhenReady = playWhenReady
            return RESULT_CODE
        }

        override fun addToPlaylist(
            metadata: PlaybackMetadata,
            position: Int?
        ): Int {
            insertedMetadata = metadata
            insertionPosition = position
            return RESULT_CODE
        }

        override fun updateMetadata(metadata: PlaybackMetadata?) {
            updatedMetadata = metadata
        }
    }

    private class FakeNavigationPlayer :
        MpvPlayer by FakePlayer,
        PlaybackNavigationController {
        var navigationHandler: PlaybackNavigationHandler? = null
            private set

        override fun addNavigationHandler(handler: PlaybackNavigationHandler): Boolean {
            if (navigationHandler != null) return false
            navigationHandler = handler
            return true
        }

        override fun removeNavigationHandler(handler: PlaybackNavigationHandler): Boolean {
            if (navigationHandler !== handler) return false
            navigationHandler = null
            return true
        }
    }

    private class FakePlaylistPlayer :
        MpvPlayer by FakePlayer,
        MpvPlaylistController {
        var queuedUris: List<String>? = null
            private set
        var queueIndex: Int? = null
            private set
        var queuePlayWhenReady = true
            private set
        var insertedUri: String? = null
            private set
        var insertionPosition: Int? = null
            private set

        override fun addToPlaylist(uri: String, position: Int?): Int {
            insertedUri = uri
            insertionPosition = position
            return RESULT_CODE
        }

        override fun setQueue(
            uris: List<String>,
            currentIndex: Int,
            playWhenReady: Boolean
        ): Int {
            queuedUris = uris
            queueIndex = currentIndex
            queuePlayWhenReady = playWhenReady
            return RESULT_CODE
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks += block
        }
    }

    private class FakeMpv : AbsMpv() {
        var playlistNextCount = 0

        override fun initialize(): Boolean = true
        override fun attach(view: Any) = Unit
        override fun detach() = Unit
        override fun commandString(cmd: String): Int = 0
        override fun load(uri: String): Int = 0
        override fun addToPlaylist(uri: String, position: Int?): Int = 0
        override fun addExternalSubtitle(uri: String): Int = 0
        override fun getPlaylist(): List<MpvPlaylistItem> = emptyList()
        override fun removeFromPlaylist(index: Int): Int = 0
        override fun playlistNext(): Int {
            playlistNextCount += 1
            return 0
        }
        override fun playlistPrev(): Int = 0
        override fun playlistClear(): Int = 0
        override fun seekTo(position: Double): Int = 0
        override fun setCoroutineScope(scope: CoroutineScope) = Unit
        override fun observeProperty(name: String) = Unit
        override fun removePropertyObservation(name: String) = Unit
        override fun play(): Int = 0
        override fun pause(): Int = 0
        override fun stop(): Int = 0
        override fun setVolume(volume: Double): Int = 0
        override fun setProperty(name: String, value: String): Int = 0
        override fun getProperty(name: String): String? = null
        override fun terminate() = Unit
        override fun startEventLoop() = Unit
    }

    private class FakePictureInPictureController(
        availability: PictureInPictureAvailability,
        private val requestStartResult: Boolean = false
    ) : PictureInPictureController {
        override val availability: StateFlow<PictureInPictureAvailability> =
            MutableStateFlow(availability)
        override val state: StateFlow<PictureInPictureState> =
            MutableStateFlow(PictureInPictureState.Inactive)
        var requestStartCount = 0
            private set

        override fun setEligible(eligible: Boolean) = Unit
        override fun setAspectRatio(width: Int, height: Int) = Unit
        override fun requestStart(): Boolean {
            requestStartCount++
            return requestStartResult
        }
        override fun requestStop(): Boolean = false
        override fun close() = Unit
    }

    private companion object {
        const val RESULT_CODE = 42
    }
}
