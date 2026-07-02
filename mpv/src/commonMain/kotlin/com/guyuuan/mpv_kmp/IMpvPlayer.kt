package com.guyuuan.mpv_kmp

import kotlinx.coroutines.CoroutineScope

enum class MpvEventType {
    None, Shutdown, LogMessage, GetPropertyReply, SetPropertyReply,
    CommandReply, StartFile, EndFile, FileLoaded,
    TracksChanged, TrackSwitched, Idle, Pause, Unpause,
    Tick, ScriptInputDispatch, ClientMessage, VideoReconfig,
    AudioReconfig, MetadataUpdate, Seek, PlaybackRestart, PropertyChange,
    ChapterChange, QueueOverflow, Hook
}
data class MpvEvent(val type: MpvEventType, val name: String? = null, val value: String? = null, val error: Int = 0)
data class MpvPlaylistItem(
    val index: Int,
    val filename: String,
    val title: String? = null,
    val current: Boolean = false
)

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
    fun setEventListener(listener: (MpvEvent) -> Unit)
    fun setCoroutineScope(scope: CoroutineScope)
    fun observeProperty(name: String)
    fun removePropertyObservation(name: String)
    fun play(): Int
    fun pause(): Int
    fun stop(): Int
    fun setProperty(name: String, value: String): Int
    fun getProperty(name: String): String?
    fun terminate()
}

expect fun createMpvPlayer(): IMpvPlayer

fun mpvFileUri(path: String): String {
    return if (path.startsWith("file://")) path else "file://$path"
}
