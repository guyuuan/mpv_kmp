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
    fun initialize(): Boolean
    fun attach(view: Any)
    fun detach()
    fun commandString(cmd: String): Int
    fun load(uri: String): Int
    fun loadFile(path: String): Int = load(mpvFileUri(path))
    fun addToPlaylist(uri: String): Int
    fun getPlaylist(): List<MpvPlaylistItem>
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

abstract class AbsMpvPlayer : IMpvPlayer {

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

    abstract fun startEventLoop()
}

expect fun createMpvPlayer(): IMpvPlayer

fun mpvFileUri(path: String): String {
    return if (path.startsWith("file://")) path else "file://$path"
}
