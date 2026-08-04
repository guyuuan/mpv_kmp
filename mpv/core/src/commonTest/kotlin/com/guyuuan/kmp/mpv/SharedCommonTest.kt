package com.guyuuan.kmp.mpv

import com.guyuuan.kmp.mpv.config.FontConfig
import com.guyuuan.kmp.mpv.config.MpvConfig
import com.guyuuan.kmp.mpv.data.MpvAudioDecoderInfo
import com.guyuuan.kmp.mpv.data.MpvAudioTrack
import com.guyuuan.kmp.mpv.data.MpvDecoderInfo
import com.guyuuan.kmp.mpv.data.MpvPlaylistItem
import com.guyuuan.kmp.mpv.data.MpvSubtitleTrack
import com.guyuuan.kmp.mpv.data.MpvVideoDecoderInfo
import com.guyuuan.kmp.mpv.props.MpvAudioProperties
import com.guyuuan.kmp.mpv.props.MpvDecoderProperties
import com.guyuuan.kmp.mpv.props.MpvPlaybackProperties
import com.guyuuan.kmp.mpv.props.MpvSubtitleProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope

class SharedCommonTest {

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
    }

    @Test
    fun decoderPropertyConstantsExposeMpvNames() {
        assertEquals(
            listOf(
                "selected-tracks/video/codec",
                "selected-tracks/video/codec-desc",
                "video-codec",
                "hwdec-selected",
                "video-params",
                "video-out-params",
                "selected-tracks/audio/codec",
                "selected-tracks/audio/codec-desc",
                "audio-codec",
                "audio-codec-name",
                "audio-params",
                "audio-out-params"
            ),
            MpvDecoderProperties.ALL
        )
    }

    @Test
    fun playbackPropertyConstantsExposeMpvNames() {
        assertEquals(
            listOf("pause", "time-pos", "duration", "speed"),
            MpvPlaybackProperties.ALL
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
    fun getAudioTrackListMapsMpvTrackList() {
        val player = FakeMpv(
            mapOf(
                "track-list/count" to "4",
                "track-list/0/type" to "video",
                "track-list/0/id" to "1",
                "track-list/1/type" to "audio",
                "track-list/1/id" to "2",
                "track-list/1/title" to "English Stereo",
                "track-list/1/lang" to "eng",
                "track-list/1/selected" to "yes",
                "track-list/1/external" to "no",
                "track-list/1/codec" to "aac",
                "track-list/1/default" to "yes",
                "track-list/2/type" to "sub",
                "track-list/2/id" to "3",
                "track-list/3/type" to "audio",
                "track-list/3/id" to "4",
                "track-list/3/title" to "Japanese 5.1",
                "track-list/3/lang" to "jpn",
                "track-list/3/selected" to "no",
                "track-list/3/external" to "yes",
                "track-list/3/external-filename" to "file:///tmp/movie.jpn.aac",
                "track-list/3/codec" to "aac",
                "track-list/3/default" to "false"
            )
        )

        val audioTracks = player.getAudioTrackList()

        assertEquals(
            listOf(
                MpvAudioTrack(
                    index = 1,
                    id = 2,
                    title = "English Stereo",
                    language = "eng",
                    selected = true,
                    external = false,
                    codec = "aac",
                    defaultTrack = true
                ),
                MpvAudioTrack(
                    index = 3,
                    id = 4,
                    title = "Japanese 5.1",
                    language = "jpn",
                    selected = false,
                    external = true,
                    externalFilename = "file:///tmp/movie.jpn.aac",
                    codec = "aac",
                    defaultTrack = false
                )
            ),
            audioTracks
        )
        assertEquals(audioTracks.first(), player.getCurrentAudioTrack())
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
    fun setFontConfigUpdatesMpvSubtitleFontProperties() {
        val player = FakeMpv(emptyMap())

        assertEquals(
            0,
            player.setFontConfig(
                FontConfig(
                    subFontsDir = "/tmp/fonts",
                    subFont = "Noto Sans CJK SC",
                    subFontSize = 42.5f,
                    subMarginY = 64
                )
            )
        )
        assertEquals("/tmp/fonts", player.setProperties[MpvSubtitleProperties.FONT_DIR])
        assertEquals("Noto Sans CJK SC", player.setProperties[MpvSubtitleProperties.FONT])
        assertEquals("42.5", player.setProperties[MpvSubtitleProperties.FONT_SIZE])
        assertEquals("64", player.setProperties[MpvSubtitleProperties.MARGIN_Y])
    }

    @Test
    fun mpvConfigMapsTypedFontConfigAndOtherOptions() {
        val config = MpvConfig(
            fontConfig = FontConfig(
                subFontsDir = "/tmp/fonts",
                subFont = "Noto Sans CJK SC",
                subFontSize = 42.5f,
                subMarginY = 64
            ),
            other = mapOf(
                "hwdec" to "no",
                MpvSubtitleProperties.FONT to "ignored-font"
            )
        )

        assertEquals(
            mapOf(
                "hwdec" to "no",
                MpvSubtitleProperties.FONT_DIR to "/tmp/fonts",
                MpvSubtitleProperties.FONT to "Noto Sans CJK SC",
                MpvSubtitleProperties.FONT_SIZE to "42.5",
                MpvSubtitleProperties.MARGIN_Y to "64"
            ),
            config.toMap()
        )
    }

    @Test
    fun setAudioTrackUpdatesMpvAidProperty() {
        val player = FakeMpv(emptyMap())

        assertEquals(0, player.setAudioTrack(4))
        assertEquals("4", player.setProperties[MpvAudioProperties.AID])

        assertEquals(0, player.setAudioTrack(null))
        assertEquals("no", player.setProperties[MpvAudioProperties.AID])
    }

    @Test
    fun setVolumeUpdatesMpvVolumeProperty() {
        val player = FakeMpv(emptyMap())

        assertEquals(0, player.setVolume(42.5))
        assertEquals("42.5", player.setProperties[MpvAudioProperties.VOLUME])
    }

    @Test
    fun setSpeedUpdatesMpvSpeedProperty() {
        val player = FakeMpv(emptyMap())

        assertEquals(0, player.setSpeed(1.5f))
        assertEquals("1.5", player.setProperties[MpvPlaybackProperties.SPEED])
    }

    @Test
    fun getSpeedMapsMpvSpeedProperty() {
        assertEquals(
            1.5f,
            FakeMpv(mapOf(MpvPlaybackProperties.SPEED to "1.5")).getSpeed()
        )
        assertNull(FakeMpv(emptyMap()).getSpeed())
        assertNull(FakeMpv(mapOf(MpvPlaybackProperties.SPEED to "invalid")).getSpeed())
    }

    @Test
    fun mpvCommandArgumentEscapesQuotes() {
        assertEquals(
            "\"file:///tmp/My \\\"Sub\\\".srt\"",
            mpvCommandArgument("file:///tmp/My \"Sub\".srt")
        )
    }

    @Test
    fun addExternalSubtitleFileConvertsPathToFileUri() {
        val player = FakeMpv(emptyMap())

        assertEquals(0, player.addExternalSubtitleFile("/tmp/My Sub.srt"))

        assertEquals(listOf("file:///tmp/My Sub.srt"), player.externalSubtitleUris)
    }

    @Test
    fun absMpvLoadsConstructorConfig() {
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

    private class FakeMpv(
        private val properties: Map<String, String?>,
        config: Map<String, String> = emptyMap()
    ) : AbsMpv(config) {
        val setProperties = mutableMapOf<String, String>()
        val commands = mutableListOf<String>()
        val configOptions = mutableListOf<Pair<String, String>>()
        val externalSubtitleUris = mutableListOf<String>()

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
        override fun addExternalSubtitle(uri: String): Int {
            externalSubtitleUris += uri
            return 0
        }
        override fun getPlaylist(): List<MpvPlaylistItem> = emptyList()
        override fun removeFromPlaylist(index: Int): Int = 0
        override fun playlistNext(): Int = 0
        override fun playlistPrev(): Int = 0
        override fun playlistClear(): Int = 0
        override fun seekTo(position: Double): Int = 0

        override fun setCoroutineScope(scope: CoroutineScope) = Unit
        override fun observeProperty(name: String) = Unit
        override fun removePropertyObservation(name: String) = Unit
        override fun play(): Int = 0
        override fun pause(): Int = 0
        override fun stop(): Int = 0
        override fun setVolume(volume: Double): Int =
            setProperty(MpvAudioProperties.VOLUME, volume.toString())
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
