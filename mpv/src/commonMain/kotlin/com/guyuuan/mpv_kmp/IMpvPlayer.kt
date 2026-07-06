package com.guyuuan.mpv_kmp

import kotlinx.coroutines.CoroutineScope
import kotlin.concurrent.Volatile

enum class MpvEventType {
    None, Shutdown, LogMessage, GetPropertyReply, SetPropertyReply,
    CommandReply, StartFile, EndFile, FileLoaded,
    TracksChanged, TrackSwitched, Idle, Pause, Unpause,
    Tick, ScriptInputDispatch, ClientMessage, VideoReconfig,
    AudioReconfig, MetadataUpdate, Seek, PlaybackRestart, PropertyChange,
    ChapterChange, QueueOverflow, Hook
}

data class MpvEvent(
    val type: MpvEventType,
    val name: String? = null,
    val value: String? = null,
    val error: Int = 0
)

data class MpvPlaylistItem(
    val index: Int,
    val filename: String,
    val title: String? = null,
    val current: Boolean = false
)

data class MpvSubtitleTrack(
    val index: Int,
    val id: Int,
    val title: String? = null,
    val language: String? = null,
    val selected: Boolean = false,
    val external: Boolean = false,
    val externalFilename: String? = null,
    val codec: String? = null,
    val defaultTrack: Boolean = false,
    val forced: Boolean = false
)

object MpvSubtitleProperties {
    const val SID = "sid"
    const val TRACK_LIST_COUNT = "track-list/count"
}

object MpvDecoderProperties {
    const val CURRENT_VIDEO_CODEC = "current-tracks/video/codec"
    const val CURRENT_VIDEO_CODEC_DESCRIPTION = "current-tracks/video/codec-desc"
    const val VIDEO_CODEC = "video-codec"
    const val HWDEC_CURRENT = "hwdec-current"
    const val VIDEO_PARAMS = "video-params"
    const val VIDEO_OUT_PARAMS = "video-out-params"
    const val CURRENT_AUDIO_CODEC = "current-tracks/audio/codec"
    const val CURRENT_AUDIO_CODEC_DESCRIPTION = "current-tracks/audio/codec-desc"
    const val AUDIO_CODEC = "audio-codec"
    const val AUDIO_CODEC_NAME = "audio-codec-name"
    const val AUDIO_PARAMS = "audio-params"
    const val AUDIO_OUT_PARAMS = "audio-out-params"

    val VIDEO: List<String> = listOf(
        CURRENT_VIDEO_CODEC,
        CURRENT_VIDEO_CODEC_DESCRIPTION,
        VIDEO_CODEC,
        HWDEC_CURRENT,
        VIDEO_PARAMS,
        VIDEO_OUT_PARAMS
    )
    val AUDIO: List<String> = listOf(
        CURRENT_AUDIO_CODEC,
        CURRENT_AUDIO_CODEC_DESCRIPTION,
        AUDIO_CODEC,
        AUDIO_CODEC_NAME,
        AUDIO_PARAMS,
        AUDIO_OUT_PARAMS
    )
    val ALL: List<String> = VIDEO + AUDIO
}

data class MpvVideoDecoderInfo(
    val codec: String?,
    val codecDescription: String?,
    val decoderCodec: String?,
    val hardwareDecoder: String?,
    val params: String?,
    val outputParams: String?
)

data class MpvAudioDecoderInfo(
    val codec: String?,
    val codecDescription: String?,
    val decoderCodec: String?,
    val decoderCodecName: String?,
    val params: String?,
    val outputParams: String?
)

data class MpvDecoderInfo(
    val video: MpvVideoDecoderInfo,
    val audio: MpvAudioDecoderInfo
)
typealias MpvEventListener = ((MpvEvent) -> Unit)

interface IMpvPlayer {
    companion object{
        val DEFAULT_CONFIG = mapOf<String,String>(
            "vo" to "gpu-next",
            "hwdec" to "auto-copy",
            "sub-margin-y" to "80",
        )
    }
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
        val count = getProperty(MpvSubtitleProperties.TRACK_LIST_COUNT)?.toIntOrNull() ?: return emptyList()
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
    fun addExternalSubtitle(uri: String): Int = commandString("sub-add ${mpvCommandArgument(uri)} select")
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
        video = getVideoDecoderInfo(),
        audio = getAudioDecoderInfo()
    )

    fun terminate()
}

abstract class AbsMpvPlayer(
    protected val config: Map<String, String> = emptyMap()
) : IMpvPlayer {

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
                println("AbsMpvPlayer: failed to set config $name=$value: $result")
                return false
            }
        }
        return true
    }

    protected open fun setConfigOption(name: String, value: String): Int = 0

    abstract fun startEventLoop()
}

expect fun createMpvPlayer(): IMpvPlayer

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
