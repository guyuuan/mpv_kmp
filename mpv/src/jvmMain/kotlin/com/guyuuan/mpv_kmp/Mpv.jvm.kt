package com.guyuuan.mpv_kmp

import com.guyuuan.mpv_kmp.data.MpvEvent
import com.guyuuan.mpv_kmp.data.MpvPlaylistItem
import com.guyuuan.mpv_kmp.jni.LocaleSetter
import com.guyuuan.mpv_kmp.jni.MpvNative
import com.guyuuan.mpv_kmp.jni.mpv_event
import com.guyuuan.mpv_kmp.jni.mpv_event_log_message
import com.guyuuan.mpv_kmp.jni.mpv_event_property
import com.guyuuan.mpv_kmp.jni.mpv_opengl_fbo
import com.guyuuan.mpv_kmp.jni.mpv_opengl_get_proc_address_fn
import com.guyuuan.mpv_kmp.jni.mpv_opengl_init_params
import com.guyuuan.mpv_kmp.jni.mpv_render_param
import com.guyuuan.mpv_kmp.jni.mpv_render_update_fn
import com.guyuuan.mpv_kmp.jni.nativeTrace
import com.jogamp.opengl.GL
import com.jogamp.opengl.GLContext
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

internal interface SoftwareRenderContextSupport {
    fun createSoftwareRenderContext(): Boolean
    fun freeRenderContext()
    fun render(width: Int, height: Int, stride: Int, format: String, buffer: Pointer)
    fun setRenderCallback(callback: () -> Unit)
}

internal interface HardwareRenderSupport {
    fun createHardwareRenderContext(): Boolean
    fun render(fbo: Int, width: Int, height: Int)
    fun freeOpenGlRenderContext()
    fun setRenderCallback(callback: () -> Unit)
}

private const val GL_RGBA8 = 0x8058


internal fun desktopRenderMode(): RenderMode {
    return when (System.getProperty("mpv.kmp.desktop.render")?.lowercase()) {
        "software", "sw" -> RenderMode.Software
        "hardware", "hw" -> RenderMode.Hardware
        else -> RenderMode.Software
    }
}

//private fun desktopHwdecOption(): String {
//    return when (val value = System.getProperty("mpv.kmp.desktop.hwdec")?.trim()?.lowercase()) {
//        null, "" -> "auto-safe"
//        "false", "off" -> "no"
//        else -> value
//    }
//}

internal class JvmMpv(
    config: Map<String, String> = DEFAULT_CONFIG
) : AbsMpv(config), SoftwareRenderContextSupport, HardwareRenderSupport {
    private companion object {
        val DEFAULT_CONFIG: Map<String, String> = Mpv.DEFAULT_CONFIG + mapOf(
            "vo" to "libmpv",
            "hwdec" to "auto-copy",
//            "vd-lavc-dr" to "no",
//            "sub-margin-y" to "80"
        )
    }

    private var ctx: Pointer? = null

    private var scope: CoroutineScope? = null
    private var eventJob: Job? = null
    private var renderCtx: Pointer? = null
    override val renderMode: RenderMode = desktopRenderMode()
    private var renderCallbackAdapter: mpv_render_update_fn? = null
    private var initParams: mpv_opengl_init_params? = null
    private var getProcAddrCallback: mpv_opengl_get_proc_address_fn? = null
    private val observedProperties = mutableMapOf<String, Long>()
    private var nextPropertyObserverId = 1L

    override fun initialize(): Boolean {
        if (ctx != null) return true
        val rid = System.nanoTime().toString(16)
        nativeTrace("init.$rid.begin")
        LocaleSetter.setNumericCLocale()
        val initBlock = {
            nativeTrace("init.$rid.block.enter")
            nativeTrace("init.$rid.before.client_api_version")
            val apiVersion = MpvNative.lib.mpv_client_api_version()
            nativeTrace("init.$rid.after.client_api_version=$apiVersion")
            nativeTrace("init.$rid.before.mpv_create")
            ctx = MpvNative.lib.mpv_create()
            nativeTrace("init.$rid.after.mpv_create.ctx=${ctx != null}")
            if (ctx == null) {
                false
            } else {
                val c = ctx!!
                if (!loadConfig()) {
                    MpvNative.lib.mpv_terminate_destroy(c)
                    ctx = null
                    false
                } else {
                    println("JvmMpv: desktop hwdec=${config["hwdec"]}")
                    nativeTrace("init.$rid.before.mpv_initialize")
                    val r = MpvNative.lib.mpv_initialize(c)
                    nativeTrace("init.$rid.after.mpv_initialize.ret=$r")
                    if (r != 0) {
                        val err = MpvNative.lib.mpv_error_string(r) ?: "unknown"
                        println("JvmMpv: mpv_initialize failed: $r ($err)")
                        MpvNative.lib.mpv_terminate_destroy(c)
                        ctx = null
                        false
                    } else {
                        nativeTrace("init.$rid.before.request_log")
                        MpvNative.lib.mpv_request_log_messages(c, "v")
                        nativeTrace("init.$rid.after.request_log")
                        true
                    }
                }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            nativeTrace("init.$rid.run.on_edt.direct")
            return initBlock()
        }
        val ok = AtomicBoolean(false)
        return try {
            nativeTrace("init.$rid.schedule.invokeAndWait")
            SwingUtilities.invokeAndWait {
                nativeTrace("init.$rid.invokeAndWait.enter")
                ok.set(initBlock())
                nativeTrace("init.$rid.invokeAndWait.exit.ok=${ok.get()}")
            }
            nativeTrace("init.$rid.end.ok=${ok.get()}")
            ok.get()
        } catch (e: Throwable) {
            println("JvmMpv: initialize on EDT failed: $e")
            return false
        }
    }

    override fun setConfigOption(name: String, value: String): Int {
        val c = ctx ?: return -1
        nativeTrace("init.before.set_option.$name")
        val ret = MpvNative.lib.mpv_set_option_string(c, name, value)
        nativeTrace("init.after.set_option.$name.ret=$ret")
        if (ret < 0) {
            val err = MpvNative.lib.mpv_error_string(ret) ?: "unknown"
            println("JvmMpv: failed to set option $name=$value: $ret ($err)")
        }
        return ret
    }

    override fun attach(view: Any) {
        val c = ctx ?: return
        if (view is Long) {
            val wid = view
            val mem = com.sun.jna.Memory(8)
            mem.setLong(0, wid)
            MpvNative.lib.mpv_set_property(c, "wid", 4, mem)
        }
    }

    override fun detach() {
        val c = ctx ?: return
        val mem = com.sun.jna.Memory(8)
        mem.setLong(0, 0L)
        MpvNative.lib.mpv_set_property(c, "wid", 4, mem)
    }

    override fun commandString(cmd: String): Int {
        val c = ctx ?: return -1
        val ret = MpvNative.lib.mpv_command_string(c, cmd)
        if (ret < 0) {
            val err = MpvNative.lib.mpv_error_string(ret) ?: "unknown"
            println("JvmMpv: commandString failed: $ret ($err), cmd=$cmd")
        }
        return ret
    }

    override fun load(uri: String): Int {
        return command("loadfile", uri)
    }

    override fun addToPlaylist(uri: String): Int {
        return command("loadfile", uri, "append")
    }

    override fun addExternalSubtitle(uri: String): Int {
        return command("sub-add", uri, "select")
    }

    override fun getPlaylist(): List<MpvPlaylistItem> = readPlaylist()
    override fun removeFromPlaylist(index: Int): Int = command("playlist-remove", index.toString())
    override fun playlistNext(): Int = commandString("playlist-next")
    override fun playlistPrev(): Int = commandString("playlist-prev")
    override fun playlistClear(): Int = commandString("playlist-clear")
    override fun seekTo(position: Double): Int =
        command("no-osd", "seek", position.toString(), "absolute")

    override fun setCoroutineScope(scope: CoroutineScope) {
        this.scope = scope
        startEventLoop()
    }

    override fun observeProperty(name: String) {
        val c = ctx ?: return
        if (observedProperties.containsKey(name)) return
        val observerId = allocatePropertyObserverId()
        observedProperties[name] = observerId
        // format 1 = MPV_FORMAT_STRING
        val ret = MpvNative.lib.mpv_observe_property(c, observerId, name, 1)
        if (ret < 0) {
            val err = MpvNative.lib.mpv_error_string(ret) ?: "unknown"
            println("JvmMpv: observeProperty failed: $ret ($err), name=$name")
            if (observedProperties[name] == observerId) {
                observedProperties.remove(name)
            }
            return
        }
        startEventLoop()
    }

    override fun removePropertyObservation(name: String) {
        val c = ctx ?: return
        val observerId = observedProperties[name] ?: return
        val ret = MpvNative.lib.mpv_unobserve_property(c, observerId)
        if (ret < 0) {
            val err = MpvNative.lib.mpv_error_string(ret) ?: "unknown"
            println("JvmMpv: removePropertyObservation failed: $ret ($err), name=$name")
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

    override fun play(): Int {
        return setProperty("pause", "no")
    }

    override fun pause(): Int {
        return setProperty("pause", "yes")
    }

    override fun stop(): Int {
        return command("stop")
    }

    private fun command(vararg args: String): Int {
        val c = ctx ?: return -1
        val nativeStrings = args.map { value ->
            com.sun.jna.Memory(value.toByteArray(Charsets.UTF_8).size + 1L).apply {
                setString(0, value, Charsets.UTF_8.name())
            }
        }
        val pointerSize = Native.POINTER_SIZE
        val argv = com.sun.jna.Memory(pointerSize.toLong() * (nativeStrings.size + 1))
        nativeStrings.forEachIndexed { index, value ->
            argv.setPointer(index.toLong() * pointerSize, value)
        }
        argv.setPointer(nativeStrings.size.toLong() * pointerSize, null)

        val ret = MpvNative.lib.mpv_command(c, argv)
        if (ret < 0) {
            val err = MpvNative.lib.mpv_error_string(ret) ?: "unknown"
            println("JvmMpv: command failed: $ret ($err), args=${args.joinToString(" ")}")
        }
        return ret
    }

    override fun setProperty(name: String, value: String): Int {
        val c = ctx ?: return -1
        return MpvNative.lib.mpv_set_property_string(c, name, value)
    }

    override fun getProperty(name: String): String? {
        val c = ctx ?: return null
        val ref = PointerByReference()
        val r = MpvNative.lib.mpv_get_property(c, name, 1, ref)
        if (r < 0) return null
        val p = ref.value ?: return null
        val s = p.getString(0)
        MpvNative.lib.mpv_free(p)
        return s
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

    override fun terminate() {
        val c = ctx
        running = false
        observedProperties.clear()
        nextPropertyObserverId = 1L
        if (c == null) return
        eventJob?.cancel()
        MpvNative.lib.mpv_wakeup(c) // Wake up wait_event
        renderCtx?.let {
            MpvNative.lib.mpv_render_context_set_update_callback(it, null, null)
            if (renderMode == RenderMode.Hardware) {
                println("JvmMpv: OpenGL render context still active during terminate; freeing on selected thread.")
            }
            MpvNative.lib.mpv_render_context_free(it)
            renderCtx = null
        }
        MpvNative.lib.mpv_terminate_destroy(c)
        ctx = null
        eventJob = null
        renderCallbackAdapter = null
    }

    override fun startEventLoop() {
        if (running) return
        if (scope == null) return
        running = true
        if (eventJob?.isActive == true) return
        eventJob = scope!!.launch(Dispatchers.IO) {
            val c = ctx ?: return@launch
            while (running && isActive) {
                // Wait 1.0s. If -1 is used, we rely on mpv_wakeup.
                val eventPtr = MpvNative.lib.mpv_wait_event(c, 1.0)
                if (eventPtr != null) {
                    val event = Structure.newInstance(mpv_event::class.java, eventPtr)
                    event.read()
                    if (event.event_id == 0) { // MPV_EVENT_NONE
                        continue
                    }
                    handleEvent(event)
                }
            }
        }
    }

    private fun handleEvent(event: mpv_event) {
        val type = mapEventType(event.event_id)
        var name: String? = null
        var value: String? = null

        if (type == MpvEventType.PropertyChange && event.reply_userdata != 0L && !observedProperties.containsValue(
                event.reply_userdata
            )
        ) {
            return
        }

        if (type == MpvEventType.LogMessage && event.data != null) {
            val lm = Structure.newInstance(mpv_event_log_message::class.java, event.data)
            lm.read()
            println("mpv[${lm.level}] ${lm.prefix}: ${lm.text}")
        }

        if (type == MpvEventType.PropertyChange && event.data != null) {
            val prop = Structure.newInstance(mpv_event_property::class.java, event.data)
            prop.read()
            name = prop.name
            if (prop.format == 1 && prop.data != null) {
                value = prop.data!!.getPointer(0)?.getString(0)
            }
        }

        listeners.forEach { it.invoke(MpvEvent(type, name, value, event.error)) }
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

    override fun createSoftwareRenderContext(): Boolean {
        val c = ctx ?: return false
        if (renderCtx != null) return renderMode == RenderMode.Software

        val api = com.sun.jna.Memory(8)
        api.setString(0, "sw")

        val base = mpv_render_param()
        val arr = base.toArray(2) as Array<mpv_render_param>

        arr[0].type = 1 // MPV_RENDER_PARAM_API_TYPE
        arr[0].data = api
        arr[0].write()

        arr[1].type = 0
        arr[1].data = null
        arr[1].write()

        val out = PointerByReference()
        println("JvmMpv: Calling mpv_render_context_create with sw...")
        val r = MpvNative.lib.mpv_render_context_create(out, c, arr[0].pointer)
        if (r == 0) {
            renderCtx = out.value
            println("JvmMpv: mpv_render_context_create success. ctx => $renderCtx")
            return true
        }
        val err = MpvNative.lib.mpv_error_string(r) ?: "unknown"
        val vo = getProperty("vo")
        println("JvmMpv: mpv_render_context_create failed: $r ($err), vo=$vo")
        return false
    }

    override fun freeRenderContext() {
        println("JvmMpv: Freeing RenderContext.")
        if (renderMode != RenderMode.Software) return
        renderCtx?.let {
            MpvNative.lib.mpv_render_context_set_update_callback(it, null, null)
            MpvNative.lib.mpv_render_context_free(it)
            renderCtx = null
        }
    }


    override fun render(width: Int, height: Int, stride: Int, format: String, buffer: Pointer) {
        val ctx = renderCtx ?: return
        if (renderMode != RenderMode.Software) return
        if (width <= 0 || height <= 0 || stride <= 0) return

        val sizePtr = com.sun.jna.Memory(8)
        sizePtr.setInt(0, width)
        sizePtr.setInt(4, height)

        val formatStrPtr = com.sun.jna.Memory(format.length + 1L)
        formatStrPtr.setString(0, format)

        val stridePtr = com.sun.jna.Memory(com.sun.jna.Native.SIZE_T_SIZE.toLong())
        if (com.sun.jna.Native.SIZE_T_SIZE == 8) {
            stridePtr.setLong(0, stride.toLong())
        } else {
            stridePtr.setInt(0, stride)
        }

        val params = mpv_render_param()
        val arr = params.toArray(5) as Array<mpv_render_param>

        arr[0].type = 17 // MPV_RENDER_PARAM_SW_SIZE
        arr[0].data = sizePtr
        arr[0].write()

        arr[1].type = 18 // MPV_RENDER_PARAM_SW_FORMAT
        arr[1].data = formatStrPtr // char*
        arr[1].write()

        arr[2].type = 19 // MPV_RENDER_PARAM_SW_STRIDE
        arr[2].data = stridePtr // size_t*
        arr[2].write()

        arr[3].type = 20 // MPV_RENDER_PARAM_SW_POINTER
        arr[3].data = buffer // void*
        arr[3].write()

        arr[4].type = 0
        arr[4].data = null
        arr[4].write()

        // Debug params
        // println("JvmMpv: renderSw: $width x $height, stride=$stride, format=$format, buf=$buffer")

        val err = MpvNative.lib.mpv_render_context_render(ctx, arr[0].pointer)
        if (err != 0) {
            println("JvmMpv: mpv_render_context_render failed: $err")
            return
        }

        MpvNative.lib.mpv_render_context_report_swap(ctx)
    }

    override fun createHardwareRenderContext(): Boolean {
        val c = ctx ?: return false
        if (renderCtx != null) return renderMode == RenderMode.Hardware
        if (GLContext.getCurrent() == null) {
            println("JvmMpv: OpenGL render context create requested without a selected JOGL context.")
            return false
        }
        clearCurrentOpenGlErrors("before createHardwareRenderContext")

        val callback = object : mpv_opengl_get_proc_address_fn {
            override fun invoke(ctx: Pointer?, name: String): Pointer? {
                val current = GLContext.getCurrent() ?: return null
                val address = try {
                    current.dynamicLibraryBundle.dynamicLookupFunction(name)
                } catch (_: Throwable) {
                    0L
                }
                return if (address == 0L) null else Pointer(address)
            }
        }
        getProcAddrCallback = callback

        val api = com.sun.jna.Memory("opengl".length + 1L)
        api.setString(0, "opengl")

        val params = mpv_opengl_init_params()
        params.get_proc_address = callback
        params.get_proc_address_ctx = null
        params.write()
        initParams = params

        val base = mpv_render_param()
        val arr = base.toArray(3) as Array<mpv_render_param>
        arr[0].type = 1 // MPV_RENDER_PARAM_API_TYPE
        arr[0].data = api
        arr[0].write()
        arr[1].type = 2 // MPV_RENDER_PARAM_OPENGL_INIT_PARAMS
        arr[1].data = params.pointer
        arr[1].write()
        arr[2].type = 0
        arr[2].data = null
        arr[2].write()

        val out = PointerByReference()
        println("JvmMpv: Calling mpv_render_context_create with opengl...")
        val r = MpvNative.lib.mpv_render_context_create(out, c, arr[0].pointer)
        if (r == 0) {
            clearCurrentOpenGlErrors("after createHardwareRenderContext")
            renderCtx = out.value
            println("JvmMpv: OpenGL render context created successfully. ctx => $renderCtx")
            return true
        }
        val err = MpvNative.lib.mpv_error_string(r) ?: "unknown"
        val vo = getProperty("vo")
        println("JvmMpv: OpenGL mpv_render_context_create failed: $r ($err), vo=$vo")
        getProcAddrCallback = null
        initParams = null
        return false
    }

    override fun render(fbo: Int, width: Int, height: Int) {
        val ctx = renderCtx ?: return
        if (renderMode != RenderMode.Hardware) return
        if (width <= 0 || height <= 0) return
        val current = GLContext.getCurrent()
        if (current == null) {
            println("JvmMpv: OpenGL render requested without a selected JOGL context.")
            return
        }

        MpvNative.lib.mpv_render_context_update(ctx)
        val gl = current.gl
        clearCurrentOpenGlErrors("before render")
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)

        val fboStruct = mpv_opengl_fbo()
        fboStruct.fbo = fbo
        fboStruct.w = width
        fboStruct.h = height
        fboStruct.internal_format = GL_RGBA8
        fboStruct.write()

        val flip = com.sun.jna.Memory(4)
        flip.setInt(0, 1)

        val base = mpv_render_param()
        val arr = base.toArray(3) as Array<mpv_render_param>
        arr[0].type = 3 // MPV_RENDER_PARAM_OPENGL_FBO
        arr[0].data = fboStruct.pointer
        arr[0].write()
        arr[1].type = 4 // MPV_RENDER_PARAM_FLIP_Y
        arr[1].data = flip
        arr[1].write()
        arr[2].type = 0
        arr[2].data = null
        arr[2].write()

        val err = MpvNative.lib.mpv_render_context_render(ctx, arr[0].pointer)
        if (err != 0) {
            println("JvmMpv: OpenGL mpv_render_context_render failed: $err")
            return
        }
        clearCurrentOpenGlErrors("after render")
        MpvNative.lib.mpv_render_context_report_swap(ctx)
    }

    override fun freeOpenGlRenderContext() {
        if (renderMode != RenderMode.Hardware) return
        val ctx = renderCtx ?: return
        if (GLContext.getCurrent() == null) {
            println("JvmMpv: OpenGL render context free requested without a selected JOGL context.")
            return
        }
        println("JvmMpv: Freeing OpenGL RenderContext.")
        MpvNative.lib.mpv_render_context_set_update_callback(ctx, null, null)
        MpvNative.lib.mpv_render_context_free(ctx)
        renderCtx = null
        renderCallbackAdapter = null
        getProcAddrCallback = null
        initParams = null
    }

    override fun setRenderCallback(callback: () -> Unit) {
        val ctx = renderCtx ?: return
        val adapter = object : mpv_render_update_fn {
            override fun invoke(ctx: Pointer?) {
                callback()
            }
        }
        this.renderCallbackAdapter = adapter
        MpvNative.lib.mpv_render_context_set_update_callback(ctx, adapter, null)
    }

    private fun clearCurrentOpenGlErrors(stage: String) {
        val gl = GLContext.getCurrent()?.gl ?: return
        var error = gl.glGetError()
        while (error != GL.GL_NO_ERROR) {
            println(
                "JvmMpv: cleared OpenGL error before mpv handoff at $stage: 0x${
                    error.toString(
                        16
                    )
                }"
            )
            error = gl.glGetError()
        }
    }
}


actual fun createMpv(): Mpv = JvmMpv()
