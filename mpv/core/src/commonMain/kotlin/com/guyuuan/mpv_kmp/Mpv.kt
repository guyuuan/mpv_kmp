package com.guyuuan.mpv_kmp

import com.guyuuan.mpv_kmp.config.FontConfig
import com.guyuuan.mpv_kmp.config.MpvConfig
import com.guyuuan.mpv_kmp.data.MpvAudioDecoderInfo
import com.guyuuan.mpv_kmp.data.MpvAudioTrack
import com.guyuuan.mpv_kmp.data.MpvDecoderInfo
import com.guyuuan.mpv_kmp.data.MpvEvent
import com.guyuuan.mpv_kmp.data.MpvPlaylistItem
import com.guyuuan.mpv_kmp.data.MpvSubtitleTrack
import com.guyuuan.mpv_kmp.data.MpvVideoDecoderInfo
import com.guyuuan.mpv_kmp.props.MpvAudioProperties
import com.guyuuan.mpv_kmp.props.MpvDecoderProperties
import com.guyuuan.mpv_kmp.props.MpvPlaybackProperties
import com.guyuuan.mpv_kmp.props.MpvSubtitleProperties
import com.guyuuan.mpv_kmp.util.PlatformLock
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.concurrent.Volatile

typealias MpvEventListener = ((MpvEvent) -> Unit)

interface Mpv {
    companion object {
        val DEFAULT_CONFIG = mapOf<String, String>(
            "vo" to "gpu-next",
            "hwdec" to "auto-copy",
            "sub-margin-y" to "80",
        )
        private var instance: Mpv? = null

        private val lock = PlatformLock()

        @OptIn(InternalCoroutinesApi::class)
        operator fun invoke(config: MpvConfig = MpvConfig()): Mpv =
            instance ?: lock.withLock { instance ?: createMpv(config).also { instance = it } }

        @OptIn(InternalCoroutinesApi::class)
        fun release() = lock.withLock {
            instance?.terminate()
            instance = null
        }
    }

    val renderMode: RenderMode
        get() = RenderMode.Hardware

    fun initialize(): Boolean
    fun attach(view: Any)
    fun detach()
    fun commandString(cmd: String): Int
    fun load(uri: String): Int
    fun loadFile(path: String): Int = load(mpvFileUri(path))
    fun addToPlaylist(uri: String): Int
    fun getPlaylist(): List<MpvPlaylistItem>
    fun getCurrentSubtitle(): MpvSubtitleTrack? = getSubtitleList().firstOrNull { it.selected }
    fun getSubtitleList(): List<MpvSubtitleTrack> {
        val count =
            getProperty(MpvSubtitleProperties.TRACK_LIST_COUNT)?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            if (getProperty("track-list/$index/type") != "sub") return@mapNotNull null
            val id = getProperty("track-list/$index/id")?.toIntOrNull() ?: return@mapNotNull null
            MpvSubtitleTrack(
                index = index,
                id = id,
                title = getProperty("track-list/$index/title"),
                language = getProperty("track-list/$index/lang"),
                selected = getProperty("track-list/$index/selected").toMpvBoolean(),
                external = getProperty("track-list/$index/external").toMpvBoolean(),
                externalFilename = getProperty("track-list/$index/external-filename"),
                codec = getProperty("track-list/$index/codec"),
                defaultTrack = getProperty("track-list/$index/default").toMpvBoolean(),
                forced = getProperty("track-list/$index/forced").toMpvBoolean()
            )
        }
    }

    fun setSubtitle(id: Int?): Int = setProperty(MpvSubtitleProperties.SID, id?.toString() ?: "no")
    fun setSubtitle(subtitle: MpvSubtitleTrack): Int = setSubtitle(subtitle.id)
    fun setFontConfig(config: FontConfig): Int {
        val properties = listOf(
            MpvSubtitleProperties.FONT_DIR to config.subFontsDir,
            MpvSubtitleProperties.FONT to config.subFont,
            MpvSubtitleProperties.FONT_SIZE to config.subFontSize.toString(),
            MpvSubtitleProperties.MARGIN_Y to config.subMarginY.toString()
        )
        properties.forEach { (name, value) ->
            val result = setProperty(name, value)
            if (result < 0) return result
        }
        return 0
    }

    fun getCurrentAudioTrack(): MpvAudioTrack? = getAudioTrackList().firstOrNull { it.selected }
    fun getAudioTrackList(): List<MpvAudioTrack> {
        val count =
            getProperty(MpvAudioProperties.TRACK_LIST_COUNT)?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            if (getProperty("track-list/$index/type") != "audio") return@mapNotNull null
            val id = getProperty("track-list/$index/id")?.toIntOrNull() ?: return@mapNotNull null
            MpvAudioTrack(
                index = index,
                id = id,
                title = getProperty("track-list/$index/title"),
                language = getProperty("track-list/$index/lang"),
                selected = getProperty("track-list/$index/selected").toMpvBoolean(),
                external = getProperty("track-list/$index/external").toMpvBoolean(),
                externalFilename = getProperty("track-list/$index/external-filename"),
                codec = getProperty("track-list/$index/codec"),
                defaultTrack = getProperty("track-list/$index/default").toMpvBoolean()
            )
        }
    }

    fun setAudioTrack(id: Int?): Int = setProperty(MpvAudioProperties.AID, id?.toString() ?: "no")
    fun setAudioTrack(audioTrack: MpvAudioTrack): Int = setAudioTrack(audioTrack.id)
    fun setVolume(volume: Double): Int
    fun setSpeed(speed: Float): Int =
        setProperty(MpvPlaybackProperties.SPEED, speed.toString())

    fun getSpeed(): Float? = getProperty(MpvPlaybackProperties.SPEED)?.toFloatOrNull()
    fun addExternalSubtitle(uri: String): Int
    fun addExternalSubtitleFile(path: String): Int = addExternalSubtitle(mpvFileUri(path))
    fun removeFromPlaylist(index: Int): Int
    fun playlistNext(): Int
    fun playlistPrev(): Int
    fun playlistClear(): Int
    fun seekTo(position: Double): Int
    fun addEventListener(listener: MpvEventListener)
    fun removeEventListener(listener: MpvEventListener)
    fun setCoroutineScope(scope: CoroutineScope)
    fun observeProperty(name: String)
    fun removePropertyObservation(name: String)
    fun play(): Int
    fun pause(): Int
    fun stop(): Int
    fun setProperty(name: String, value: String): Int
    fun getProperty(name: String): String?
    fun getVideoDecoderInfo(): MpvVideoDecoderInfo = MpvVideoDecoderInfo(
        codec = getProperty(MpvDecoderProperties.CURRENT_VIDEO_CODEC),
        codecDescription = getProperty(MpvDecoderProperties.CURRENT_VIDEO_CODEC_DESCRIPTION),
        decoderCodec = getProperty(MpvDecoderProperties.VIDEO_CODEC),
        hardwareDecoder = getProperty(MpvDecoderProperties.HWDEC_CURRENT),
        params = getProperty(MpvDecoderProperties.VIDEO_PARAMS),
        outputParams = getProperty(MpvDecoderProperties.VIDEO_OUT_PARAMS)
    )

    fun getAudioDecoderInfo(): MpvAudioDecoderInfo = MpvAudioDecoderInfo(
        codec = getProperty(MpvDecoderProperties.CURRENT_AUDIO_CODEC),
        codecDescription = getProperty(MpvDecoderProperties.CURRENT_AUDIO_CODEC_DESCRIPTION),
        decoderCodec = getProperty(MpvDecoderProperties.AUDIO_CODEC),
        decoderCodecName = getProperty(MpvDecoderProperties.AUDIO_CODEC_NAME),
        params = getProperty(MpvDecoderProperties.AUDIO_PARAMS),
        outputParams = getProperty(MpvDecoderProperties.AUDIO_OUT_PARAMS)
    )

    fun getDecoderInfo(): MpvDecoderInfo = MpvDecoderInfo(
        video = getVideoDecoderInfo(), audio = getAudioDecoderInfo()
    )

    fun terminate()
}

abstract class AbsMpv(
    protected val config: Map<String, String> = emptyMap()
) : Mpv {

    protected val listeners: MutableList<MpvEventListener> = mutableListOf()

    @Volatile
    protected var running = false

    override fun addEventListener(listener: MpvEventListener) {
        if (listener !in listeners) {
            listeners.add(listener)
        }
        startEventLoop()
    }

    override fun removeEventListener(listener: MpvEventListener) {
        if (listener in listeners) {
            listeners.remove(listener)
        }
    }

    protected fun loadConfig(): Boolean {
        config.forEach { (name, value) ->
            val result = setConfigOption(name, value)
            if (result < 0) {
                Logger.e(tag = "AbsMpv") { "failed to set config $name=$value: $result" }
                return false
            }
        }
        return true
    }

    protected open fun setConfigOption(name: String, value: String): Int = 0

    abstract fun startEventLoop()
}

internal expect fun createMpv(config: MpvConfig): Mpv

fun mpvFileUri(path: String): String {
    return if (path.startsWith("file://")) path else "file://$path"
}

private fun String?.toMpvBoolean(): Boolean = this == "yes" || this == "true"

internal fun mpvCommandArgument(value: String): String {
    val escaped = buildString {
        value.forEach { char ->
            if (char == '\\' || char == '"') append('\\')
            append(char)
        }
    }
    return "\"$escaped\""
}
