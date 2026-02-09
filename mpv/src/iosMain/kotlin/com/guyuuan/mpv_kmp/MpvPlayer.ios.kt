package com.guyuuan.mpv_kmp

import com.guyuuan.mpv_kmp.mpv.*
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.ptr
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.allocPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ByteVar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.isActive

private class IosMpvPlayer : MpvPlayer {
    private var handle: CPointer<mpv_handle>? = null
    private var listener: ((MpvEvent) -> Unit)? = null
    @Volatile private var running = false
    private var scope: CoroutineScope? = null
    private var eventJob: Job? = null

    override fun initialize(): Boolean {
        if (handle != null) return true
        handle = mpv_create()
        if (handle == null) return false
        val r = mpv_initialize(handle)
        return r == 0
    }
    override fun attach(view: Any) {
        val h = handle ?: return
        if (view is CPointer<*>) {
            memScoped {
                val widVal = view.rawValue.toLong()
                val widVar = alloc<LongVar>()
                widVar.value = widVal
                mpv_set_property(h, "wid", MPV_FORMAT_INT64, widVar.ptr)
            }
        } else if (view is Long) {
            memScoped {
                val widVar = alloc<LongVar>()
                widVar.value = view
                mpv_set_property(h, "wid", MPV_FORMAT_INT64, widVar.ptr)
            }
        }
    }
    override fun detach() {
        val h = handle ?: return
        val zero: Long = 0
        mpv_set_property_string(h, "wid", "0")
    }
    override fun commandString(cmd: String): Int {
        val h = handle ?: return -1
        return mpv_command_string(h, cmd)
    }
    override fun load(uri: String): Int {
        return commandString("loadfile \"$uri\"")
    }
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
        val h = handle ?: return
        mpv_observe_property(h, 0.toULong(), name, MPV_FORMAT_STRING)
        startEventLoop()
    }
    override fun removePropertyObservation(name: String) {
        // See JVM note
    }
    override fun play(): Int {
        return setProperty("pause", "no")
    }
    override fun pause(): Int {
        return setProperty("pause", "yes")
    }
    override fun stop(): Int {
        return commandString("stop")
    }
    override fun setProperty(name: String, value: String): Int {
        val h = handle ?: return -1
        return mpv_set_property_string(h, name, value)
    }
    override fun getProperty(name: String): String? {
        val h = handle ?: return null
        memScoped {
            val out = allocPointer<ByteVar>()
            val r = mpv_get_property_string(h, name, out.ptr)
            if (r < 0 || out.value == null) return null
            val s = out.value!!.toKString()
            mpv_free(out.value)
            return s
        }
    }
    override fun terminate() {
        val h = handle ?: return
        running = false
        mpv_wakeup(h)
        mpv_terminate_destroy(h)
        handle = null
        eventJob?.cancel()
        eventJob = null
    }
    private fun startEventLoop() {
        if (running) return
        if (scope == null) return
        running = true
        if (eventJob?.isActive == true) return
        eventJob = scope!!.launch(Dispatchers.Default) {
            val h = handle ?: return@launch
            while (running && isActive) {
                val eventPtr = mpv_wait_event(h, 1.0)
                if (eventPtr != null) {
                    val event = eventPtr.pointed
                    if (event.event_id == MPV_EVENT_NONE) continue
                    handleEvent(event)
                }
            }
        }
    }

    private fun handleEvent(event: mpv_event) {
        val type = mapEventType(event.event_id)
        var name: String? = null
        var value: String? = null
        
        if (type == MpvEventType.PropertyChange && event.data != null) {
             val prop = event.data!!.reinterpret<mpv_event_property>().pointed
             if (prop.name != null) name = prop.name!!.toKString()
             if (prop.format == MPV_FORMAT_STRING && prop.data != null) {
                 val ptrPtr = prop.data!!.reinterpret<kotlinx.cinterop.CPointerVar<kotlinx.cinterop.ByteVar>>()
                 value = ptrPtr.value?.toKString()
             }
        }

        listener?.invoke(MpvEvent(type, name, value, event.error))
    }

    private fun mapEventType(id: mpv_event_id): MpvEventType {
        return when (id) {
            MPV_EVENT_SHUTDOWN -> MpvEventType.Shutdown
            MPV_EVENT_LOG_MESSAGE -> MpvEventType.LogMessage
            MPV_EVENT_GET_PROPERTY_REPLY -> MpvEventType.GetPropertyReply
            MPV_EVENT_SET_PROPERTY_REPLY -> MpvEventType.SetPropertyReply
            MPV_EVENT_COMMAND_REPLY -> MpvEventType.CommandReply
            MPV_EVENT_START_FILE -> MpvEventType.StartFile
            MPV_EVENT_END_FILE -> MpvEventType.EndFile
            MPV_EVENT_FILE_LOADED -> MpvEventType.FileLoaded
            MPV_EVENT_TRACKS_CHANGED -> MpvEventType.TracksChanged
            MPV_EVENT_TRACK_SWITCHED -> MpvEventType.TrackSwitched
            MPV_EVENT_IDLE -> MpvEventType.Idle
            MPV_EVENT_PAUSE -> MpvEventType.Pause
            MPV_EVENT_UNPAUSE -> MpvEventType.Unpause
            MPV_EVENT_TICK -> MpvEventType.Tick
            MPV_EVENT_SCRIPT_INPUT_DISPATCH -> MpvEventType.ScriptInputDispatch
            MPV_EVENT_CLIENT_MESSAGE -> MpvEventType.ClientMessage
            MPV_EVENT_VIDEO_RECONFIG -> MpvEventType.VideoReconfig
            MPV_EVENT_AUDIO_RECONFIG -> MpvEventType.AudioReconfig
            MPV_EVENT_METADATA_UPDATE -> MpvEventType.MetadataUpdate
            MPV_EVENT_SEEK -> MpvEventType.Seek
            MPV_EVENT_PLAYBACK_RESTART -> MpvEventType.PlaybackRestart
            MPV_EVENT_PROPERTY_CHANGE -> MpvEventType.PropertyChange
            MPV_EVENT_CHAPTER_CHANGE -> MpvEventType.ChapterChange
            MPV_EVENT_QUEUE_OVERFLOW -> MpvEventType.QueueOverflow
            MPV_EVENT_HOOK -> MpvEventType.Hook
            else -> MpvEventType.None
        }
    }
}

actual fun createMpvPlayer(): MpvPlayer = IosMpvPlayer()
