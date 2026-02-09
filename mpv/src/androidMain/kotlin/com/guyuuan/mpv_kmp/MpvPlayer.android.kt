package com.guyuuan.mpv_kmp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

private class AndroidMpvPlayer : MpvPlayer {
    private var listener: ((MpvEvent) -> Unit)? = null
    @Volatile private var running = false
    private var scope: CoroutineScope? = null
    private var eventJob: Job? = null

    override fun initialize(): Boolean = MpvNative.mpvInit()
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
    override fun playlistNext(): Int = commandString("playlist-next")
    override fun playlistPrev(): Int = commandString("playlist-prev")
    override fun playlistClear(): Int = commandString("playlist-clear")
    override fun setEventListener(listener: (MpvEvent) -> Unit) {
        this.listener = listener
        startEventLoop()
    }
    override fun setCoroutineScope(scope: CoroutineScope) {
        this.scope = scope
        startEventLoop()
    }
    override fun observeProperty(name: String) {
        // format 1 = MPV_FORMAT_STRING
        MpvNative.mpvObserveProperty(name, 1)
        startEventLoop()
    }
    override fun removePropertyObservation(name: String) {
        // See JVM implementation note
    }
    override fun play(): Int = setProperty("pause", "no")
    override fun pause(): Int = setProperty("pause", "yes")
    override fun stop(): Int = commandString("stop")
    override fun setProperty(name: String, value: String): Int = MpvNative.mpvSetProperty(name, value)
    override fun getProperty(name: String): String? = MpvNative.mpvGetProperty(name)
    override fun terminate() {
        running = false
        MpvNative.mpvTerminate() // calls wakeup
        eventJob?.cancel()
        eventJob = null
    }
    private fun startEventLoop() {
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
        listener?.invoke(MpvEvent(type, event.propName, event.propValue, event.error))
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

actual fun createMpvPlayer(): MpvPlayer = AndroidMpvPlayer()
