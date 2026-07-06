package com.guyuuan.mpv_kmp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
private class AndroidMpvPlayer(
    config: Map<String, String> = DEFAULT_CONFIG
) : AbsMpvPlayer(config) {
    private companion object {
        val DEFAULT_CONFIG: Map<String, String> = IMpvPlayer.DEFAULT_CONFIG+ mapOf(
            "vo" to "gpu",
            "gpu-context" to "android",
            "gpu-api" to "opengl",
            "hwdec" to "mediacodec-copy",
            "vd-lavc-dr" to "no",
            "sub-margin-y" to "80",
            "ao" to "audiotrack"
        )
    }

    private var scope: CoroutineScope? = null
    private var eventJob: Job? = null
    private val observedProperties = mutableMapOf<String, Long>()
    private var nextPropertyObserverId = 1L
    private var initialized = false

    override fun initialize(): Boolean {
        if (initialized) return true
        if (!MpvNative.mpvCreate()) return false
        if (!loadConfig()) {
            MpvNative.mpvTerminate()
            return false
        }
        initialized = MpvNative.mpvInitialize()
        return initialized
    }
    override fun setConfigOption(name: String, value: String): Int = MpvNative.mpvSetOption(name, value)
    override fun attach(view: Any) {
        if (view is android.view.Surface) {
            MpvNative.mpvAttachSurface(view)
        }
    }
    override fun detach() {
        MpvNative.mpvDetachSurface()
    }
    override fun commandString(cmd: String): Int = MpvNative.mpvCommandString(cmd)
    override fun load(uri: String): Int = commandString("loadfile \"$uri\"")
    override fun addToPlaylist(uri: String): Int = commandString("loadfile \"$uri\" append")
    override fun getPlaylist(): List<MpvPlaylistItem> = readPlaylist()
    override fun removeFromPlaylist(index: Int): Int = commandString("playlist-remove $index")
    override fun playlistNext(): Int = commandString("playlist-next")
    override fun playlistPrev(): Int = commandString("playlist-prev")
    override fun playlistClear(): Int = commandString("playlist-clear")
    override fun seekTo(position: Double): Int = commandString("no-osd seek $position absolute")

    override fun setCoroutineScope(scope: CoroutineScope) {
        this.scope = scope
        startEventLoop()
    }
    override fun observeProperty(name: String) {
        if (observedProperties.containsKey(name)) return
        val observerId = allocatePropertyObserverId()
        observedProperties[name] = observerId
        // format 1 = MPV_FORMAT_STRING
        val result = MpvNative.mpvObserveProperty(name, observerId, 1)
        if (result < 0) {
            println("AndroidMpvPlayer: observeProperty failed: $result, name=$name")
            if (observedProperties[name] == observerId) {
                observedProperties.remove(name)
            }
            return
        }
        startEventLoop()
    }
    override fun removePropertyObservation(name: String) {
        val observerId = observedProperties[name] ?: return
        val result = MpvNative.mpvUnobserveProperty(observerId)
        if (result < 0) {
            println("AndroidMpvPlayer: removePropertyObservation failed: $result, name=$name")
            return
        }
        observedProperties.remove(name)
    }
    private fun allocatePropertyObserverId(): Long {
        val observerId = nextPropertyObserverId
        nextPropertyObserverId += 1
        if (nextPropertyObserverId == 0L) {
            nextPropertyObserverId = 1L
        }
        return observerId
    }
    override fun play(): Int = setProperty("pause", "no")
    override fun pause(): Int = setProperty("pause", "yes")
    override fun stop(): Int = commandString("stop")
    override fun setProperty(name: String, value: String): Int = MpvNative.mpvSetProperty(name, value)
    override fun getProperty(name: String): String? = MpvNative.mpvGetProperty(name)
    private fun readPlaylist(): List<MpvPlaylistItem> {
        val count = getProperty("playlist/count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val filename = getProperty("playlist/$index/filename") ?: return@mapNotNull null
            MpvPlaylistItem(
                index = index,
                filename = filename,
                title = getProperty("playlist/$index/title"),
                current = getProperty("playlist/$index/current") == "yes"
            )
        }
    }
    override fun terminate() {
        running = false
        initialized = false
        observedProperties.clear()
        nextPropertyObserverId = 1L
        MpvNative.mpvTerminate() // calls wakeup
        eventJob?.cancel()
        eventJob = null
    }
    override fun startEventLoop() {
        if (running) return
        if (scope == null) return
        running = true
        if (eventJob?.isActive == true) return
        eventJob = scope!!.launch(Dispatchers.IO) {
            while (running && isActive) {
                val event = MpvNative.mpvWaitEvent(1.0)
                if (event != null) {
                    handleEvent(event)
                }
            }
        }
    }

    private fun handleEvent(event: MpvEventDTO) {
        val type = mapEventType(event.eventId)
        if (type == MpvEventType.PropertyChange &&
            event.replyUserdata != 0L &&
            !observedProperties.containsValue(event.replyUserdata)
        ) {
            return
        }

        listeners.forEach { it.invoke(MpvEvent(type, event.propName, event.propValue, event.error)) }
    }

    private fun mapEventType(id: Int): MpvEventType {
        return when (id) {
            1 -> MpvEventType.Shutdown
            2 -> MpvEventType.LogMessage
            3 -> MpvEventType.GetPropertyReply
            4 -> MpvEventType.SetPropertyReply
            5 -> MpvEventType.CommandReply
            6 -> MpvEventType.StartFile
            7 -> MpvEventType.EndFile
            8 -> MpvEventType.FileLoaded
            9 -> MpvEventType.TracksChanged
            10 -> MpvEventType.TrackSwitched
            11 -> MpvEventType.Idle
            12 -> MpvEventType.Pause
            13 -> MpvEventType.Unpause
            14 -> MpvEventType.Tick
            15 -> MpvEventType.ScriptInputDispatch
            16 -> MpvEventType.ClientMessage
            17 -> MpvEventType.VideoReconfig
            18 -> MpvEventType.AudioReconfig
            19 -> MpvEventType.MetadataUpdate
            20 -> MpvEventType.Seek
            21 -> MpvEventType.PlaybackRestart
            22 -> MpvEventType.PropertyChange
            23 -> MpvEventType.ChapterChange
            24 -> MpvEventType.QueueOverflow
            25 -> MpvEventType.Hook
            else -> MpvEventType.None
        }
    }
}

actual fun createMpvPlayer(): IMpvPlayer = AndroidMpvPlayer()
