package com.guyuuan.kmp.mpv

import com.guyuuan.kmp.mpv.data.MpvDecoderInfo
import com.guyuuan.kmp.mpv.data.MpvEvent
import com.guyuuan.kmp.mpv.data.MpvPlaylistItem
import com.guyuuan.kmp.mpv.props.MpvDecoderProperties
import kotlin.test.Test
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
        private val properties: Map<String, String?>
    ) : AbsMpv() {
        val observedProperties = mutableListOf<String>()
        val removedProperties = mutableListOf<String>()

        fun emitEvent(event: MpvEvent) {
            listeners.toList().forEach { it(event) }
        }

        override fun initialize(): Boolean = true
        override fun attach(view: Any) = Unit
        override fun detach() = Unit
        override fun commandString(cmd: String): Int = 0
        override fun load(uri: String): Int = 0
        override fun addToPlaylist(uri: String): Int = 0
        override fun addExternalSubtitle(uri: String): Int = 0
        override fun getPlaylist(): List<MpvPlaylistItem> = emptyList()
        override fun removeFromPlaylist(index: Int): Int = 0
        override fun playlistNext(): Int = 0
        override fun playlistPrev(): Int = 0
        override fun playlistClear(): Int = 0
        override fun seekTo(position: Double): Int = 0
        override fun setCoroutineScope(scope: CoroutineScope) = Unit
        override fun observeProperty(name: String) {
            observedProperties += name
        }
        override fun removePropertyObservation(name: String) {
            removedProperties += name
        }
        override fun play(): Int = 0
        override fun pause(): Int = 0
        override fun stop(): Int = 0
        override fun setVolume(volume: Double): Int = 0
        override fun setProperty(name: String, value: String): Int = 0
        override fun getProperty(name: String): String? = properties[name]
        override fun terminate() = Unit
        override fun startEventLoop() = Unit
    }
}
