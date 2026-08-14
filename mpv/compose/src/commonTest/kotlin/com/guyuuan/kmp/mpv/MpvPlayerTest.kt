package com.guyuuan.kmp.mpv

import com.guyuuan.kmp.mpv.data.MpvDecoderInfo
import com.guyuuan.kmp.mpv.data.MpvEvent
import com.guyuuan.kmp.mpv.data.MpvPlaylistItem
import com.guyuuan.kmp.mpv.props.MpvDecoderProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MpvPlayerTest {
    @Test
    fun localPlayerBuildsQueueAroundSelectedItem() = runBlocking {
        val mpv = FakeMpv(emptyMap())
        val playerScope = CoroutineScope(Job())
        val player = LocalMpvPlayer(mpv, playerScope)
        player.setup()

        assertEquals(
            0,
            player.setQueue(
                uris = listOf("first", "second", "third"),
                currentIndex = 1,
                playWhenReady = true
            )
        )
        assertEquals("second", mpv.loadedUri)
        assertEquals(listOf("first" to 0, "third" to null), mpv.addedPlaylistItems)
        assertEquals(1, mpv.playlistClearCount)
        assertEquals(0, mpv.playCount)
        assertEquals(1, mpv.pauseCount)
        assertEquals(MpvPlayerState.Loading, player.snapshot.value.state)

        mpv.emitEvent(MpvEvent(MpvEventType.StartFile))
        mpv.emitEvent(MpvEvent(MpvEventType.FileLoaded))

        eventually {
            mpv.playCount == 1 && player.snapshot.value.state == MpvPlayerState.Playing
        }

        player.close()
        playerScope.cancel()
    }

    @Test
    fun localPlayerBuildsPausedQueueWithoutStartingPlayback() = runBlocking {
        val mpv = FakeMpv(emptyMap())
        val playerScope = CoroutineScope(Job())
        val player = LocalMpvPlayer(mpv, playerScope)
        player.setup()

        assertEquals(
            0,
            player.setQueue(
                uris = listOf("first", "second"),
                currentIndex = 0,
                playWhenReady = false
            )
        )
        assertEquals("first", mpv.loadedUri)
        assertEquals(listOf<Pair<String, Int?>>("second" to null), mpv.addedPlaylistItems)
        assertEquals(0, mpv.playCount)
        assertEquals(1, mpv.pauseCount)

        mpv.emitEvent(MpvEvent(MpvEventType.StartFile))
        mpv.emitEvent(MpvEvent(MpvEventType.FileLoaded))

        eventually {
            mpv.playCount == 0 && player.snapshot.value.state == MpvPlayerState.Paused
        }

        player.close()
        playerScope.cancel()
    }

    @Test
    fun pauseDuringQueueLoadingOverridesAutoPlay() = runBlocking {
        val mpv = FakeMpv(emptyMap())
        val playerScope = CoroutineScope(Job())
        val player = LocalMpvPlayer(mpv, playerScope)
        player.setup()

        assertEquals(
            0,
            player.setQueue(
                uris = listOf("first", "second"),
                currentIndex = 0,
                playWhenReady = true
            )
        )
        assertEquals(0, player.pause())

        mpv.emitEvent(MpvEvent(MpvEventType.StartFile))
        mpv.emitEvent(MpvEvent(MpvEventType.FileLoaded))

        eventually {
            mpv.playCount == 0 && player.snapshot.value.state == MpvPlayerState.Paused
        }

        player.close()
        playerScope.cancel()
    }

    @Test
    fun previousFileEndingDoesNotCancelPendingQueuePlayback() = runBlocking {
        val mpv = FakeMpv(emptyMap())
        val playerScope = CoroutineScope(Job())
        val player = LocalMpvPlayer(mpv, playerScope)
        player.setup()

        assertEquals(
            0,
            player.setQueue(
                uris = listOf("first", "second"),
                currentIndex = 0,
                playWhenReady = true
            )
        )

        mpv.emitEvent(MpvEvent(MpvEventType.StartFile))
        mpv.emitEvent(MpvEvent(MpvEventType.EndFile))
        mpv.emitEvent(MpvEvent(MpvEventType.StartFile))
        mpv.emitEvent(MpvEvent(MpvEventType.FileLoaded))

        eventually {
            mpv.playCount == 1 && player.snapshot.value.state == MpvPlayerState.Playing
        }

        player.close()
        playerScope.cancel()
    }

    @Test
    fun replacingQueueKeepsLatestPendingPlaybackIntent() = runBlocking {
        val mpv = FakeMpv(emptyMap())
        val playerScope = CoroutineScope(Job())
        val player = LocalMpvPlayer(mpv, playerScope)
        player.setup()

        assertEquals(
            0,
            player.setQueue(listOf("old"), playWhenReady = false)
        )
        assertEquals(
            0,
            player.setQueue(listOf("new"), playWhenReady = true)
        )

        mpv.emitEvent(MpvEvent(MpvEventType.StartFile))
        mpv.emitEvent(MpvEvent(MpvEventType.EndFile))
        mpv.emitEvent(MpvEvent(MpvEventType.StartFile))
        mpv.emitEvent(MpvEvent(MpvEventType.FileLoaded))

        eventually {
            mpv.loadedUri == "new" &&
                mpv.playCount == 1 &&
                player.snapshot.value.state == MpvPlayerState.Playing
        }

        player.close()
        playerScope.cancel()
    }

    @Test
    fun playDuringPausedQueueLoadingOverridesInitialIntent() = runBlocking {
        val mpv = FakeMpv(emptyMap())
        val playerScope = CoroutineScope(Job())
        val player = LocalMpvPlayer(mpv, playerScope)
        player.setup()

        assertEquals(
            0,
            player.setQueue(
                uris = listOf("first", "second"),
                currentIndex = 0,
                playWhenReady = false
            )
        )
        assertEquals(0, player.play())

        mpv.emitEvent(MpvEvent(MpvEventType.StartFile))
        mpv.emitEvent(MpvEvent(MpvEventType.FileLoaded))

        eventually {
            mpv.playCount == 2 && player.snapshot.value.state == MpvPlayerState.Playing
        }

        player.close()
        playerScope.cancel()
    }

    @Test
    fun failedQueueLoadDoesNotAffectNextLoad() = runBlocking {
        val mpv = FakeMpv(emptyMap())
        val playerScope = CoroutineScope(Job())
        val player = LocalMpvPlayer(mpv, playerScope)
        player.setup()

        assertEquals(
            0,
            player.setQueue(
                uris = listOf("first", "second"),
                currentIndex = 0,
                playWhenReady = true
            )
        )
        mpv.emitEvent(MpvEvent(MpvEventType.StartFile))
        mpv.emitEvent(MpvEvent(MpvEventType.EndFile, error = -1))
        eventually { player.snapshot.value.state == MpvPlayerState.Error }

        assertEquals(0, player.load("replacement"))
        mpv.emitEvent(MpvEvent(MpvEventType.StartFile))
        mpv.emitEvent(MpvEvent(MpvEventType.FileLoaded))

        eventually {
            mpv.playCount == 0 && player.snapshot.value.state == MpvPlayerState.Paused
        }

        player.close()
        playerScope.cancel()
    }

    @Test
    fun localPlayerReturnsPlaylistNavigationResult() = runBlocking {
        val mpv = FakeMpv(
            properties = emptyMap(),
            playlistNextResult = 0,
            playlistPrevResult = -1
        )
        val playerScope = CoroutineScope(Job())
        val player = LocalMpvPlayer(mpv, playerScope)
        player.setup()
        mpv.emitPlaylistState(position = 1, size = 3)
        eventually { player.snapshot.value.canPrevious && player.snapshot.value.canNext }

        assertEquals(true, player.next())
        assertEquals(false, player.previous())

        player.close()
        playerScope.cancel()
    }

    @Test
    fun localPlayerReportsAvailablePlaylistNavigationInSnapshot() = runBlocking {
        val mpv = FakeMpv(emptyMap())
        val playerScope = CoroutineScope(Job())
        val player = LocalMpvPlayer(mpv, playerScope)
        player.setup()

        mpv.emitPlaylistState(position = 1, size = 3)
        eventually { player.snapshot.value.canPrevious && player.snapshot.value.canNext }
        assertEquals(true, player.canPrevious())
        assertEquals(true, player.canNext())

        mpv.emitPlaylistState(position = 0, size = 3)
        eventually { !player.snapshot.value.canPrevious && player.snapshot.value.canNext }
        assertEquals(false, player.canPrevious())
        assertEquals(true, player.canNext())

        mpv.emitPlaylistState(position = 2, size = 3)
        eventually { player.snapshot.value.canPrevious && !player.snapshot.value.canNext }
        assertEquals(true, player.canPrevious())
        assertEquals(false, player.canNext())

        mpv.emitPlaylistState(position = null, size = 0)
        eventually { !player.snapshot.value.canPrevious && !player.snapshot.value.canNext }
        assertEquals(false, player.canPrevious())
        assertEquals(false, player.canNext())

        player.close()
        playerScope.cancel()
    }

    @Test
    fun decoderInfoFlowObservesAndRemovesDecoderPropertiesWithCollectors() = runBlocking {
        val player = FakeMpv(emptyMap())
        val playerScope = CoroutineScope(Job())
        val state = LocalMpvPlayer(player, playerScope)
        state.setup()

        val job = launch {
            state.decoderInfoFlow.collect {}
        }
        eventually {
            player.observedProperties.containsAll(MpvDecoderProperties.ALL)
        }

        job.cancelAndJoin()
        eventually {
            player.removedProperties.containsAll(MpvDecoderProperties.ALL)
        }
        state.close()
        playerScope.cancel()
    }

    @Test
    fun decoderInfoFlowEmitsWhenDecoderPropertyChanges() = runBlocking {
        val properties = mutableMapOf<String, String?>(
            MpvDecoderProperties.VIDEO_CODEC to "h264"
        )
        val player = FakeMpv(properties)
        val playerScope = CoroutineScope(Job())
        val state = LocalMpvPlayer(player, playerScope)
        val received = mutableListOf<MpvDecoderInfo>()
        state.setup()

        val job = launch {
            state.decoderInfoFlow.collect {
                received += it
            }
        }
        eventually {
            received.any { it.video.decoderCodec == "h264" }
        }

        properties[MpvDecoderProperties.VIDEO_CODEC] = "hevc"
        player.emitEvent(
            MpvEvent(
                type = MpvEventType.PropertyChange,
                name = MpvDecoderProperties.VIDEO_CODEC,
                value = "hevc"
            )
        )
        eventually {
            received.any { it.video.decoderCodec == "hevc" }
        }

        job.cancelAndJoin()
        state.close()
        playerScope.cancel()
    }

    private suspend fun eventually(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            delay(10)
        }
        fail("Condition was not met")
    }

    private class FakeMpv(
        private val properties: Map<String, String?>,
        private val playlistNextResult: Int = 0,
        private val playlistPrevResult: Int = 0
    ) : AbsMpv() {
        val observedProperties = mutableListOf<String>()
        val removedProperties = mutableListOf<String>()
        val addedPlaylistItems = mutableListOf<Pair<String, Int?>>()
        var loadedUri: String? = null
        var playlistClearCount = 0
        var playCount = 0
        var pauseCount = 0

        fun emitEvent(event: MpvEvent) {
            listeners.toList().forEach { it(event) }
        }

        fun emitPlaylistState(position: Int?, size: Int) {
            emitEvent(
                MpvEvent(
                    type = MpvEventType.PropertyChange,
                    name = "playlist/count",
                    value = size.toString()
                )
            )
            emitEvent(
                MpvEvent(
                    type = MpvEventType.PropertyChange,
                    name = "playlist-pos",
                    value = position?.toString()
                )
            )
        }

        override fun initialize(): Boolean = true
        override fun attach(view: Any) = Unit
        override fun detach() = Unit
        override fun commandString(cmd: String): Int = 0
        override fun load(uri: String): Int {
            loadedUri = uri
            return 0
        }
        override fun addToPlaylist(uri: String, position: Int?): Int {
            addedPlaylistItems += uri to position
            return 0
        }
        override fun addExternalSubtitle(uri: String): Int = 0
        override fun getPlaylist(): List<MpvPlaylistItem> = emptyList()
        override fun removeFromPlaylist(index: Int): Int = 0
        override fun playlistNext(): Int = playlistNextResult
        override fun playlistPrev(): Int = playlistPrevResult
        override fun playlistClear(): Int {
            playlistClearCount++
            return 0
        }
        override fun seekTo(position: Double): Int = 0
        override fun setCoroutineScope(scope: CoroutineScope) = Unit
        override fun observeProperty(name: String) {
            observedProperties += name
        }
        override fun removePropertyObservation(name: String) {
            removedProperties += name
        }
        override fun play(): Int {
            playCount++
            return 0
        }
        override fun pause(): Int {
            pauseCount++
            return 0
        }
        override fun stop(): Int = 0
        override fun setVolume(volume: Double): Int = 0
        override fun setProperty(name: String, value: String): Int = 0
        override fun getProperty(name: String): String? = properties[name]
        override fun terminate() = Unit
        override fun startEventLoop() = Unit
    }
}
