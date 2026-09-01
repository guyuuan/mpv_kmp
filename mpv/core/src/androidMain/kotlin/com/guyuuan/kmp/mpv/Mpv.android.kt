package com.guyuuan.kmp.mpv

import com.guyuuan.kmp.mpv.config.MpvConfig
import com.guyuuan.kmp.mpv.config.MpvLogLevel
import com.guyuuan.kmp.mpv.data.MpvEvent
import com.guyuuan.kmp.mpv.data.MpvPlaylistItem
import com.guyuuan.kmp.mpv.props.MpvAudioProperties
import com.guyuuan.kmp.mpv.props.MpvPlaybackProperties
import com.guyuuan.kmp.mpv.util.MpvNativeCallGate
import com.guyuuan.kmp.mpv.util.PlatformLock
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
private class AndroidMpv(
    config: Map<String, String> = emptyMap(),
    private var logLevel: MpvLogLevel = MpvLogLevel.Warn
) : AbsMpv(DEFAULT_CONFIG + config, DEFAULT_CONFIG) {
    private companion object {
        val DEFAULT_CONFIG: Map<String, String> = Mpv.DEFAULT_CONFIG+ mapOf(
            "vo" to "gpu",
            "gpu-context" to "android",
            "gpu-api" to "opengl",
            "hwdec" to "mediacodec-copy",
            "vd-lavc-dr" to "no",
            "keepaspect" to "yes",
            "sub-margin-y" to "80",
            "ao" to "audiotrack"
        )
    }

    private var scope: CoroutineScope? = null
    private var eventJob: Job? = null
    private val observedProperties = mutableMapOf<String, Long>()
    private var nextPropertyObserverId = 1L
    private var initialized = false
    private val callGate = MpvNativeCallGate()
    private val lifecycleLock = PlatformLock()
    private val inEventLoop = ThreadLocal.withInitial { false }
    @kotlin.concurrent.Volatile
    private var terminating = false

    override fun initialize(): Boolean = lifecycleLock.withLock {
        if (initialized) return@withLock true
        terminating = false
        callGate.reopen()
        if (!callGate.withControlCall(
                onClosing = { false },
                action = MpvNative::mpvCreate
            )
        ) return@withLock false
        if (!loadConfig()) {
            destroyNativeHandle()
            return@withLock false
        }
        initialized = callGate.withControlCall(
            onClosing = { false },
        ) { MpvNative.mpvInitialize(logLevel.value) }
        if (!initialized) destroyNativeHandle()
        initialized
    }
    override fun setConfigOption(name: String, value: String): Int =
        callGate.withControlCall(onClosing = { -1 }) {
            MpvNative.mpvSetOption(name, value)
        }

    internal override fun updateConfig(config: MpvConfig) = lifecycleLock.withLock {
        super.updateConfig(config)
        logLevel = config.logLevel ?: MpvLogLevel.Warn
    }

    override fun attach(view: Any) {
        if (view is android.view.Surface) {
            callGate.withControlCall(onClosing = {}) {
                MpvNative.mpvAttachSurface(view)
            }
        }
    }
    override fun detach() {
        callGate.withControlCall(onClosing = {}) {
            MpvNative.mpvDetachSurface()
        }
    }
    override fun commandString(cmd: String): Int =
        callGate.withControlCall(onClosing = { -1 }) {
            MpvNative.mpvCommandString(cmd)
        }
    override fun load(uri: String): Int = commandString("loadfile \"$uri\"")
    override fun addToPlaylist(uri: String, position: Int?): Int {
        val action = position?.let { "insert-at $it" } ?: "append"
        return commandString("loadfile \"$uri\" $action")
    }
    override fun addExternalSubtitle(uri: String): Int =
        commandString("sub-add ${mpvCommandArgument(uri)} select")

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
        val result = callGate.withControlCall(onClosing = { -1 }) {
            MpvNative.mpvObserveProperty(name, observerId, 1)
        }
        if (result < 0) {
            Logger.e(tag = "AndroidMpv") { "observeProperty failed: $result, name=$name" }
            if (observedProperties[name] == observerId) {
                observedProperties.remove(name)
            }
            return
        }
        startEventLoop()
    }
    override fun removePropertyObservation(name: String) {
        val observerId = observedProperties[name] ?: return
        val result = callGate.withControlCall(onClosing = { -1 }) {
            MpvNative.mpvUnobserveProperty(observerId)
        }
        if (result < 0) {
            Logger.e(tag = "AndroidMpv") {
                "removePropertyObservation failed: $result, name=$name"
            }
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
    override fun play(): Int = setProperty(MpvPlaybackProperties.PAUSE, "no")
    override fun pause(): Int = setProperty(MpvPlaybackProperties.PAUSE, "yes")
    override fun stop(): Int = commandString("stop")
    override fun setVolume(volume: Double): Int =
        setProperty(MpvAudioProperties.VOLUME, volume.toString())
    override fun setProperty(name: String, value: String): Int =
        callGate.withControlCall(onClosing = { -1 }) {
            MpvNative.mpvSetProperty(name, value)
        }

    override fun getProperty(name: String): String? =
        callGate.withControlCall(onClosing = { null }) {
            MpvNative.mpvGetProperty(name)
        }
    private fun readPlaylist(): List<MpvPlaylistItem> {
        val count = getProperty("playlist/count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val filename = getProperty("playlist/$index/filename") ?: return@mapNotNull null
            MpvPlaylistItem(
                index = index,
                filename = filename,
                title = getProperty("playlist/$index/title"),
                selected = getProperty("playlist/$index/selected") == "yes"
            )
        }
    }
    override fun terminate() = lifecycleLock.withLock {
        check(inEventLoop.get() != true) {
            "Mpv.terminate() must not be called from an MpvEventListener"
        }
        val job = eventJob
        if (!initialized && job == null) return@withLock

        callGate.beginClosing()
        terminating = true
        running = false
        initialized = false
        eventJob = null
        job?.cancel()
        MpvNative.mpvWakeup()
        val interrupted = joinEventJobUninterruptibly(job)
        try {
            callGate.closeWhenIdle {
                MpvNative.mpvTerminate()
            }
            observedProperties.clear()
            nextPropertyObserverId = 1L
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    override fun startEventLoop() {
        if (terminating) return
        lifecycleLock.withLock {
            if (terminating) return@withLock
            if (running) return@withLock
            if (!initialized) return@withLock
            if (scope == null) return@withLock
            running = true
            if (eventJob?.isActive == true) return@withLock
            eventJob = scope!!.launch(Dispatchers.IO) {
                inEventLoop.set(true)
                try {
                    while (running && isActive) {
                        val event = callGate.withEventCall(onClosing = { null }) {
                            MpvNative.mpvWaitEvent(1.0)
                        }
                        if (event != null) {
                            handleEvent(event)
                        }
                    }
                } finally {
                    inEventLoop.remove()
                }
            }
        }
    }

    private fun destroyNativeHandle() {
        callGate.beginClosing()
        callGate.closeWhenIdle {
            MpvNative.mpvTerminate()
        }
    }

    private fun joinEventJobUninterruptibly(job: Job?): Boolean {
        job ?: return false
        var interrupted = false
        while (true) {
            try {
                runBlocking { job.cancelAndJoin() }
                return interrupted
            } catch (_: InterruptedException) {
                interrupted = true
                job.cancel()
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

        dispatchEvent(MpvEvent(type, event.propName, event.propValue, event.error))
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

internal actual fun createMpv(config: MpvConfig): Mpv =
    AndroidMpv(config.toMap(), config.logLevel ?: MpvLogLevel.Warn)
