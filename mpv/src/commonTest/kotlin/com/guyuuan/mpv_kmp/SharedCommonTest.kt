package com.guyuuan.mpv_kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SharedCommonTest {

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
    }

    @Test
    fun decoderPropertyConstantsExposeMpvNames() {
        assertEquals(
            listOf(
                "current-tracks/video/codec",
                "current-tracks/video/codec-desc",
                "video-codec",
                "hwdec-current",
                "video-params",
                "video-out-params",
                "current-tracks/audio/codec",
                "current-tracks/audio/codec-desc",
                "audio-codec",
                "audio-codec-name",
                "audio-params",
                "audio-out-params"
            ),
            MpvDecoderProperties.ALL
        )
    }

    @Test
    fun getDecoderInfoMapsMpvProperties() {
        val player = FakeMpvPlayer(
            mapOf(
                MpvDecoderProperties.CURRENT_VIDEO_CODEC to "h264",
                MpvDecoderProperties.CURRENT_VIDEO_CODEC_DESCRIPTION to "H.264 / AVC",
                MpvDecoderProperties.VIDEO_CODEC to "h264",
                MpvDecoderProperties.HWDEC_CURRENT to "videotoolbox",
                MpvDecoderProperties.VIDEO_PARAMS to "1920x1080 yuv420p",
                MpvDecoderProperties.VIDEO_OUT_PARAMS to "1920x1080 bgra",
                MpvDecoderProperties.CURRENT_AUDIO_CODEC to "aac",
                MpvDecoderProperties.CURRENT_AUDIO_CODEC_DESCRIPTION to "AAC",
                MpvDecoderProperties.AUDIO_CODEC to "aac",
                MpvDecoderProperties.AUDIO_CODEC_NAME to "aac",
                MpvDecoderProperties.AUDIO_PARAMS to "stereo 48000Hz",
                MpvDecoderProperties.AUDIO_OUT_PARAMS to "stereo 48000Hz"
            )
        )

        assertEquals(
            MpvDecoderInfo(
                video = MpvVideoDecoderInfo(
                    codec = "h264",
                    codecDescription = "H.264 / AVC",
                    decoderCodec = "h264",
                    hardwareDecoder = "videotoolbox",
                    params = "1920x1080 yuv420p",
                    outputParams = "1920x1080 bgra"
                ),
                audio = MpvAudioDecoderInfo(
                    codec = "aac",
                    codecDescription = "AAC",
                    decoderCodec = "aac",
                    decoderCodecName = "aac",
                    params = "stereo 48000Hz",
                    outputParams = "stereo 48000Hz"
                )
            ),
            player.getDecoderInfo()
        )
    }

    @Test
    fun getDecoderInfoReturnsNullForMissingProperties() {
        val info = FakeMpvPlayer(emptyMap()).getDecoderInfo()

        assertNull(info.video.codec)
        assertNull(info.video.codecDescription)
        assertNull(info.video.decoderCodec)
        assertNull(info.video.hardwareDecoder)
        assertNull(info.video.params)
        assertNull(info.video.outputParams)
        assertNull(info.audio.codec)
        assertNull(info.audio.codecDescription)
        assertNull(info.audio.decoderCodec)
        assertNull(info.audio.decoderCodecName)
        assertNull(info.audio.params)
        assertNull(info.audio.outputParams)
    }

    @Test
    fun decoderInfoFlowObservesAndRemovesDecoderPropertiesWithCollectors() = runBlocking {
        val player = FakeMpvPlayer(emptyMap())
        val playerScope = CoroutineScope(Job())
        val state = MpvPlayer(player, playerScope)
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
        state.dispose()
        playerScope.cancel()
    }

    @Test
    fun decoderInfoFlowEmitsWhenDecoderPropertyChanges() = runBlocking {
        val properties = mutableMapOf<String, String?>(
            MpvDecoderProperties.VIDEO_CODEC to "h264"
        )
        val player = FakeMpvPlayer(properties)
        val playerScope = CoroutineScope(Job())
        val state = MpvPlayer(player, playerScope)
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
        state.dispose()
        playerScope.cancel()
    }

    private suspend fun eventually(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            delay(10)
        }
        fail("Condition was not met")
    }

    private class FakeMpvPlayer(
        private val properties: Map<String, String?>
    ) : AbsMpvPlayer() {
        val observedProperties = mutableListOf<String>()
        val removedProperties = mutableListOf<String>()

        fun emitEvent(event: MpvEvent) {
            listeners.forEach { it(event) }
        }

        override fun initialize(): Boolean = true
        override fun attach(view: Any) = Unit
        override fun detach() = Unit
        override fun commandString(cmd: String): Int = 0
        override fun load(uri: String): Int = 0
        override fun addToPlaylist(uri: String): Int = 0
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
        override fun setProperty(name: String, value: String): Int = 0
        override fun getProperty(name: String): String? = properties[name]
        override fun terminate() = Unit
        override fun startEventLoop() {
            
        }
    }
}
