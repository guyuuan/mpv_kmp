package com.guyuuan.mpv_kmp

import cnames.structs.mpv_handle
import cnames.structs.mpv_render_context
import com.guyuuan.mpv_kmp.mpv.*
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.RTLD_LAZY
import platform.posix.dlopen
import platform.posix.dlsym
import kotlin.concurrent.Volatile

@OptIn(ExperimentalForeignApi::class)
internal interface IosRenderContextSupport {
    fun createRenderContext(): Boolean
    fun freeRenderContext()
    fun updateRenderContext(): Boolean
    fun renderGl(width: Int, height: Int, fbo: Int = 0, internalFormat: Int = 0): Int
    fun setRenderCallback(callback: () -> Unit)
}

@OptIn(ExperimentalForeignApi::class)
private fun mpvRenderUpdateCallback(data: COpaquePointer?) {
    data?.asStableRef<() -> Unit>()?.get()?.invoke()
}

@OptIn(ExperimentalForeignApi::class)
private fun mpvGetOpenGlProcAddress(
    ctx: COpaquePointer?, name: CPointer<ByteVar>?
): COpaquePointer? {
    val symbol = name?.toKString() ?: return null
    return dlsym(openGlEsHandle(), symbol) ?: dlsym(null, symbol)
}

@OptIn(ExperimentalForeignApi::class)
private fun openGlEsHandle(): COpaquePointer? {
    if (openGlEsFrameworkHandle == null) {
        openGlEsFrameworkHandle = dlopen(
            "/System/Library/Frameworks/OpenGLES.framework/OpenGLES", RTLD_LAZY
        )
    }
    return openGlEsFrameworkHandle
}

@Volatile
@OptIn(ExperimentalForeignApi::class)
private var openGlEsFrameworkHandle: COpaquePointer? = null

@OptIn(ExperimentalForeignApi::class)
private class IosMpvPlayer(
    config: Map<String, String> = DEFAULT_CONFIG
) : AbsMpvPlayer(config), IosRenderContextSupport {
    private companion object {
        val DEFAULT_CONFIG: Map<String, String> = IMpvPlayer.DEFAULT_CONFIG + mapOf(
            "vo" to "libmpv",
            "ao" to "audiounit"
//            "profile" to "sw-fast",
//            "hwdec" to "videotoolbox-copy",
//            "sws-fast" to "yes",
//            "zimg-fast" to "yes",
//            "vd-lavc-dr" to "no",
//            "sub-margin-y" to "80"
        )
    }

    private var handle: CPointer<mpv_handle>? = null
    private var renderContext: CPointer<mpv_render_context>? = null
    private var renderCallbackRef: StableRef<(() -> Unit)>? = null
    private var scope: CoroutineScope? = null
    private var eventJob: Job? = null
    private val observedProperties = mutableMapOf<String, ULong>()
    private var nextPropertyObserverId = 1uL

    override fun initialize(): Boolean {
        if (handle != null) return true
        handle = mpv_create()
        val h = handle ?: return false
        if (!loadConfig()) {
            mpv_terminate_destroy(h)
            handle = null
            return false
        }
        val r = mpv_initialize(h)
        if (r != 0) {
            println("IosMpvPlayer: mpv_initialize failed: $r (${mpvError(r)})")
            mpv_terminate_destroy(h)
            handle = null
            return false
        }
        mpv_request_log_messages(h, "v")
        return true
    }

    override fun setConfigOption(name: String, value: String): Int {
        val h = handle ?: return -1
        val result = mpv_set_option_string(h, name, value)
        if (result < 0) {
            println("IosMpvPlayer: failed to set $name=$value: $result (${mpvError(result)})")
        }
        return result
    }

    override fun attach(view: Any) {
        // iOS renders through mpv_render_context; the window-id path does not draw into UIKit.
    }

    override fun detach() {
        // See attach().
    }

    override fun commandString(cmd: String): Int {
        val h = handle ?: return -1
        return mpv_command_string(h, cmd)
    }

    override fun load(uri: String): Int {
        return commandString("loadfile \"$uri\"")
    }

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
        val h = handle ?: return
        if (observedProperties.containsKey(name)) return
        val observerId = allocatePropertyObserverId()
        observedProperties[name] = observerId
        val result = mpv_observe_property(h, observerId, name, MPV_FORMAT_STRING)
        if (result != 0) {
            println("IosMpvPlayer: observeProperty failed: $result (${mpvError(result)}), name=$name")
            if (observedProperties[name] == observerId) {
                observedProperties.remove(name)
            }
            return
        }
        startEventLoop()
    }

    override fun removePropertyObservation(name: String) {
        val h = handle ?: return
        val observerId = observedProperties[name] ?: return
        val result = mpv_unobserve_property(h, observerId)
        if (result < 0) {
            println("IosMpvPlayer: removePropertyObservation failed: $result (${mpvError(result)}), name=$name")
            return
        }
        observedProperties.remove(name)
    }

    private fun allocatePropertyObserverId(): ULong {
        val observerId = nextPropertyObserverId
        nextPropertyObserverId += 1uL
        if (nextPropertyObserverId == 0uL) {
            nextPropertyObserverId = 1uL
        }
        return observerId
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
        val value = mpv_get_property_string(h, name) ?: return null
        val result = value.toKString()
        mpv_free(value)
        return result
    }

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
        val h = handle
        running = false
        observedProperties.clear()
        nextPropertyObserverId = 1uL
        if (h == null) return
        freeRenderContext()
        mpv_wakeup(h)
        mpv_terminate_destroy(h)
        handle = null
        eventJob?.cancel()
        eventJob = null
    }

    override fun startEventLoop() {
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

        if (type == MpvEventType.PropertyChange && event.reply_userdata != 0uL && !observedProperties.containsValue(
                event.reply_userdata
            )
        ) {
            return
        }

        if (type == MpvEventType.LogMessage && event.data != null) {
            val log = event.data!!.reinterpret<mpv_event_log_message>().pointed
            val level = log.level?.toKString()
            val prefix = log.prefix?.toKString()
            val text = log.text?.toKString()
            println("mpv[$level] $prefix: $text")
        }

        if (type == MpvEventType.PropertyChange && event.data != null) {
            val prop = event.data!!.reinterpret<mpv_event_property>().pointed
            if (prop.name != null) {
                name = prop.name!!.toKString()
                value = getProperty(name)
            }
        }

        listeners.forEach { it.invoke(MpvEvent(type, name, value, event.error)) }
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
            MPV_EVENT_IDLE -> MpvEventType.Idle
            MPV_EVENT_TICK -> MpvEventType.Tick
            MPV_EVENT_CLIENT_MESSAGE -> MpvEventType.ClientMessage
            MPV_EVENT_VIDEO_RECONFIG -> MpvEventType.VideoReconfig
            MPV_EVENT_AUDIO_RECONFIG -> MpvEventType.AudioReconfig
            MPV_EVENT_SEEK -> MpvEventType.Seek
            MPV_EVENT_PLAYBACK_RESTART -> MpvEventType.PlaybackRestart
            MPV_EVENT_PROPERTY_CHANGE -> MpvEventType.PropertyChange
            MPV_EVENT_QUEUE_OVERFLOW -> MpvEventType.QueueOverflow
            MPV_EVENT_HOOK -> MpvEventType.Hook
            else -> MpvEventType.None
        }
    }

    override fun createRenderContext(): Boolean {
        val h = handle ?: return false
        if (renderContext != null) return true

        return memScoped {
            val api = MPV_RENDER_API_TYPE_OPENGL.cstr.getPointer(this)
            val glInitParams = alloc<mpv_opengl_init_params>()
            glInitParams.get_proc_address = staticCFunction(::mpvGetOpenGlProcAddress)
            glInitParams.get_proc_address_ctx = null

            val params = allocArray<mpv_render_param>(3)
            params[0].type = MPV_RENDER_PARAM_API_TYPE
            params[0].data = api.reinterpret()
            params[1].type = MPV_RENDER_PARAM_OPENGL_INIT_PARAMS
            params[1].data = glInitParams.ptr.reinterpret()
            params[2].type = MPV_RENDER_PARAM_INVALID
            params[2].data = null

            val out = alloc<CPointerVarOf<CPointer<mpv_render_context>>>()
            val result = mpv_render_context_create(out.ptr, h, params)
            if (result == 0) {
                renderContext = out.value
                println("IosMpvPlayer: OpenGL mpv_render_context_create success")
                true
            } else {
                println(
                    "IosMpvPlayer: OpenGL mpv_render_context_create failed: $result (${
                        mpvError(
                            result
                        )
                    })"
                )
                false
            }
        }
    }

    override fun freeRenderContext() {
        val ctx = renderContext
        if (ctx != null) {
            mpv_render_context_set_update_callback(ctx, null, null)
            mpv_render_context_free(ctx)
            renderContext = null
        }
        renderCallbackRef?.dispose()
        renderCallbackRef = null
    }

    override fun updateRenderContext(): Boolean {
        val ctx = renderContext ?: return false
        return (mpv_render_context_update(ctx) and 1uL) != 0uL
    }

    override fun renderGl(width: Int, height: Int, fbo: Int, internalFormat: Int): Int {
        val ctx = renderContext ?: return -1
        if (width <= 0 || height <= 0) return -1

        return memScoped {
            val target = alloc<mpv_opengl_fbo>()
            target.fbo = fbo
            target.w = width
            target.h = height
            target.internal_format = internalFormat

            val flipY = alloc<IntVar>()
            flipY.value = 1

            val params = allocArray<mpv_render_param>(3)
            params[0].type = MPV_RENDER_PARAM_OPENGL_FBO
            params[0].data = target.ptr.reinterpret()
            params[1].type = MPV_RENDER_PARAM_FLIP_Y
            params[1].data = flipY.ptr.reinterpret()
            params[2].type = MPV_RENDER_PARAM_INVALID
            params[2].data = null

            val result = mpv_render_context_render(ctx, params)
            if (result == 0) {
                mpv_render_context_report_swap(ctx)
            } else {
                println("IosMpvPlayer: mpv_render_context_render failed: $result (${mpvError(result)})")
            }
            result
        }
    }

    override fun setRenderCallback(callback: () -> Unit) {
        val ctx = renderContext ?: return
        mpv_render_context_set_update_callback(ctx, null, null)
        renderCallbackRef?.dispose()

        val ref = StableRef.create(callback)
        renderCallbackRef = ref
        mpv_render_context_set_update_callback(
            ctx, staticCFunction(::mpvRenderUpdateCallback), ref.asCPointer()
        )
    }

    private fun mpvError(code: Int): String {
        return mpv_error_string(code)?.toKString() ?: "unknown"
    }
}

actual fun createMpvPlayer(): IMpvPlayer = IosMpvPlayer()
