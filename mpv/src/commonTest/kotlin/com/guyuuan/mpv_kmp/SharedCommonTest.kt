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
        val player = FakeMpv(
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
        val info = FakeMpv(emptyMap()).getDecoderInfo()

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
    fun getSubtitleListMapsMpvTrackList() {
        val player = FakeMpv(
            mapOf(
                "track-list/count" to "4",
                "track-list/0/type" to "video",
                "track-list/0/id" to "1",
                "track-list/1/type" to "sub",
                "track-list/1/id" to "2",
                "track-list/1/title" to "English SDH",
                "track-list/1/lang" to "eng",
                "track-list/1/selected" to "yes",
                "track-list/1/external" to "yes",
                "track-list/1/external-filename" to "file:///tmp/movie.eng.srt",
                "track-list/1/codec" to "subrip",
                "track-list/1/default" to "yes",
                "track-list/1/forced" to "no",
                "track-list/2/type" to "audio",
                "track-list/2/id" to "3",
                "track-list/3/type" to "sub",
                "track-list/3/id" to "4",
                "track-list/3/title" to "Chinese",
                "track-list/3/lang" to "chi",
                "track-list/3/selected" to "no",
                "track-list/3/external" to "no",
                "track-list/3/codec" to "ass",
                "track-list/3/default" to "no",
                "track-list/3/forced" to "true"
            )
        )

        val subtitles = player.getSubtitleList()

        assertEquals(
            listOf(
                MpvSubtitleTrack(
                    index = 1,
                    id = 2,
                    title = "English SDH",
                    language = "eng",
                    selected = true,
                    external = true,
                    externalFilename = "file:///tmp/movie.eng.srt",
                    codec = "subrip",
                    defaultTrack = true,
                    forced = false
                ),
                MpvSubtitleTrack(
                    index = 3,
                    id = 4,
                    title = "Chinese",
                    language = "chi",
                    selected = false,
                    external = false,
                    codec = "ass",
                    defaultTrack = false,
                    forced = true
                )
            ),
            subtitles
        )
        assertEquals(subtitles.first(), player.getCurrentSubtitle())
    }

    @Test
    fun setSubtitleUpdatesMpvSidProperty() {
        val player = FakeMpv(emptyMap())

        assertEquals(0, player.setSubtitle(4))
        assertEquals("4", player.setProperties[MpvSubtitleProperties.SID])

        assertEquals(0, player.setSubtitle(null))
        assertEquals("no", player.setProperties[MpvSubtitleProperties.SID])
    }

    @Test
    fun addExternalSubtitleQuotesMpvCommandArgument() {
        val player = FakeMpv(emptyMap())

        assertEquals(0, player.addExternalSubtitle("file:///tmp/My \"Sub\".srt"))

        assertEquals(
            "sub-add \"file:///tmp/My \\\"Sub\\\".srt\" select",
            player.commands.single()
        )
    }

    @Test
    fun absMpvPlayerLoadsConstructorConfig() {
        val player = FakeMpv(
            properties = emptyMap(),
            config = mapOf(
                "vo" to "libmpv",
                "sub-margin-y" to "80"
            )
        )

        assertEquals(true, player.initialize())
        assertEquals(
            listOf(
                "vo" to "libmpv",
                "sub-margin-y" to "80"
            ),
            player.configOptions
        )
    }

    @Test
    fun decoderInfoFlowObservesAndRemovesDecoderPropertiesWithCollectors() = runBlocking {
        val player = FakeMpv(emptyMap())
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
        val player = FakeMpv(properties)
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

    private class FakeMpv(
        private val properties: Map<String, String?>,
        config: Map<String, String> = emptyMap()
    ) : AbsMpv(config) {
        val observedProperties = mutableListOf<String>()
        val removedProperties = mutableListOf<String>()
        val setProperties = mutableMapOf<String, String>()
        val commands = mutableListOf<String>()
        val configOptions = mutableListOf<Pair<String, String>>()

        fun emitEvent(event: MpvEvent) {
            listeners.forEach { it(event) }
        }

        override fun initialize(): Boolean = loadConfig()
        override fun setConfigOption(name: String, value: String): Int {
            configOptions += name to value
            return 0
        }
        override fun attach(view: Any) = Unit
        override fun detach() = Unit
        override fun commandString(cmd: String): Int {
            commands += cmd
            return 0
        }
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
        override fun setProperty(name: String, value: String): Int {
            setProperties[name] = value
            return 0
        }
        override fun getProperty(name: String): String? = properties[name]
        override fun terminate() = Unit
        override fun startEventLoop() {
            
        }
    }
}
