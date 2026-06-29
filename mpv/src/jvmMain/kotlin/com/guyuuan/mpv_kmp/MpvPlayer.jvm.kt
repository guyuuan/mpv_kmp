package com.guyuuan.mpv_kmp

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarFile
import javax.swing.SwingUtilities

private fun nativeTrace(stage: String) {
    val t = Thread.currentThread()
    println(
        "NativeTrace[$stage] thread=${t.name}(${t.id}) edt=${SwingUtilities.isEventDispatchThread()} os=${
            System.getProperty(
                "os.name"
            )
        } arch=${System.getProperty("os.arch")}"
    )
}

class mpv_event : Structure() {
    @JvmField
    var event_id: Int = 0

    @JvmField
    var error: Int = 0

    @JvmField
    var reply_userdata: Long = 0

    @JvmField
    var data: Pointer? = null

    override fun getFieldOrder(): List<String> {
        return listOf("event_id", "error", "reply_userdata", "data")
    }
}

class mpv_event_property : Structure() {
    @JvmField
    var name: String = ""

    @JvmField
    var format: Int = 0

    @JvmField
    var data: Pointer? = null

    override fun getFieldOrder(): List<String> {
        return listOf("name", "format", "data")
    }
}

class mpv_render_param : Structure() {
    @JvmField
    var type: Int = 0

    @JvmField
    var data: Pointer? = null

    override fun getFieldOrder(): List<String> {
        return listOf("type", "data")
    }
}

class mpv_opengl_fbo : Structure() {
    @JvmField
    var fbo: Int = 0

    @JvmField
    var w: Int = 0

    @JvmField
    var h: Int = 0

    @JvmField
    var internal_format: Int = 0
    override fun getFieldOrder(): List<String> = listOf("fbo", "w", "h", "internal_format")
}

interface mpv_render_update_fn : com.sun.jna.Callback {
    fun invoke(ctx: Pointer?)
}

interface mpv_opengl_get_proc_address_fn : Callback {
    fun invoke(ctx: Pointer?, name: String): Pointer?
}

class mpv_opengl_init_params : Structure() {
    @JvmField
    var get_proc_address: mpv_opengl_get_proc_address_fn? = null

    @JvmField
    var get_proc_address_ctx: Pointer? = null

    @JvmField
    var extra_exts: String? = null

    override fun getFieldOrder() = listOf("get_proc_address", "get_proc_address_ctx", "extra_exts")
}

private interface MPVLibrary : Library {
    fun mpv_client_api_version(): Long
    fun mpv_create(): Pointer?
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_error_string(error: Int): String?
    fun mpv_request_log_messages(ctx: Pointer, min_level: String): Int
    fun mpv_command(ctx: Pointer, args: Pointer): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: PointerByReference): Int
    fun mpv_observe_property(ctx: Pointer, reply_userdata: Long, name: String, format: Int): Int
    fun mpv_unobserve_property(ctx: Pointer, registered_reply_userdata: Long): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_free(data: Pointer)
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_render_context_create(res: PointerByReference, ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(
        ctx: Pointer, cb: com.sun.jna.Callback?, data: Pointer?
    )

    fun mpv_render_context_report_swap(ctx: Pointer)
}

class mpv_event_log_message : Structure() {
    @JvmField
    var prefix: String = ""

    @JvmField
    var level: String = ""

    @JvmField
    var text: String = ""

    @JvmField
    var log_level: Int = 0
    override fun getFieldOrder(): List<String> {
        return listOf("prefix", "level", "text", "log_level")
    }
}

private object LocaleSetter {
    @Volatile
    private var initialized = false

    fun setNumericCLocale() {
        if (initialized) {
            nativeTrace("locale.skip.already_initialized")
            return
        }
        nativeTrace("locale.begin")
        val libs = if (osId() == "windows") listOf("msvcrt") else listOf("c", "System", "System.B")
        val category = 4
        for (name in libs) {
            try {
                nativeTrace("locale.try.$name")
                val lib = NativeLibrary.getInstance(name)
                val fn = lib.getFunction("setlocale")
                val ret = fn.invokePointer(arrayOf<Any>(category, "C"))
                nativeTrace("locale.call.$name.ret=${ret != null}")
                if (ret != null) {
                    initialized = true
                    nativeTrace("locale.success.$name")
                    return
                }
            } catch (e: Throwable) {
                println("NativeTrace[locale.fail.$name] $e")
            }
        }
        nativeTrace("locale.end.no_success")
    }
}

private fun osId(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") -> "darwin"
        os.contains("win") -> "windows"
        os.contains("linux") -> "linux"
        else -> "unknown"
    }
}

private fun archId(): String {
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
        arch.contains("x86_64") || arch.contains("amd64") -> "x86-64"
        arch.contains("x86") -> "x86"
        else -> arch
    }
}

private fun libNameFor(os: String): String {
    return when (os) {
        "darwin" -> "libmpv.dylib"
        "linux" -> "libmpv.so"
        "windows" -> "mpv.dll"
        else -> "mpv"
    }
}

private const val MPV_NATIVE_DIR_PROPERTY = "mpv.kmp.native.dir"
private const val COMPOSE_RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"
private const val COMPOSE_NATIVE_RESOURCE_DIR = "mpv-kmp"

private data class DesktopNativePlatform(
    val os: String, val arch: String, val id: String, val mainLibraryCandidates: List<String>
)

private data class ResolvedMpvLibrary(
    val platform: DesktopNativePlatform,
    val mainLibraryPath: String,
    val directory: File,
    val dependencyLibraryPaths: List<String>,
    val source: String
)

private fun currentDesktopNativePlatform(): DesktopNativePlatform {
    val os = osId()
    val arch = archId()
    val mainLibraryCandidates = when (os) {
        "darwin" -> listOf("libmpv.dylib", "libmpv.2.dylib")
        "linux" -> listOf("libmpv.so", "libmpv.so.2")
        "windows" -> listOf("mpv.dll", "mpv-2.dll", "libmpv.dll")
        else -> listOf(libNameFor(os))
    }
    return DesktopNativePlatform(os, arch, "$os-$arch", mainLibraryCandidates)
}

private fun findMainLibrary(directory: File, platform: DesktopNativePlatform): File? {
    if (!directory.isDirectory) return null
    return platform.mainLibraryCandidates.map { File(directory, it) }.firstOrNull { it.isFile }
}

private fun resolvePlatformDirectory(root: File, platform: DesktopNativePlatform): File? {
    val dir = root.absoluteFile
    if (!dir.isDirectory) return null
    val platformDir = File(dir, platform.id)
    return when {
        findMainLibrary(platformDir, platform) != null -> platformDir
        findMainLibrary(dir, platform) != null -> dir
        platformDir.isDirectory -> platformDir
        else -> dir
    }
}

private fun resolveMpvLibraryInDirectory(
    directory: File, platform: DesktopNativePlatform, source: String
): ResolvedMpvLibrary? {
    val main = findMainLibrary(directory, platform) ?: return null
    val dependencies = if (platform.os == "darwin") {
        directory.listFiles()?.filter { it.isFile && it.extension == "dylib" }
            ?.filter { it.absolutePath != main.absolutePath && it.name != "libmpv_kmp_macos_shim.dylib" }
            ?.sortedWith(compareBy<File> { macosDependencyLoadPriority(it.name) }.thenBy { it.name })
            ?.map { it.absolutePath } ?: emptyList()
    } else {
        emptyList()
    }
    return ResolvedMpvLibrary(platform, main.absolutePath, directory, dependencies, source)
}

private fun configuredNativeLibrary(
    platform: DesktopNativePlatform, failures: MutableList<Throwable>
): ResolvedMpvLibrary? {
    val configuredDir =
        System.getProperty(MPV_NATIVE_DIR_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
    if (configuredDir != null) {
        val root = File(configuredDir)
        val directory = resolvePlatformDirectory(root, platform)
        val resolved = directory?.let {
            resolveMpvLibraryInDirectory(it, platform, "$MPV_NATIVE_DIR_PROPERTY=$configuredDir")
        }
        if (resolved != null) return resolved
        failures += IllegalStateException(
            "Configured $MPV_NATIVE_DIR_PROPERTY does not contain ${platform.mainLibraryCandidates} " + "for ${platform.id}: ${root.absolutePath}"
        )
    }

    val composeResourcesDir =
        System.getProperty(COMPOSE_RESOURCES_DIR_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
    val directory = File(composeResourcesDir, "$COMPOSE_NATIVE_RESOURCE_DIR/${platform.id}")
    val resolved = resolveMpvLibraryInDirectory(
        directory,
        platform,
        "$COMPOSE_RESOURCES_DIR_PROPERTY/$COMPOSE_NATIVE_RESOURCE_DIR/${platform.id}"
    )
    if (resolved != null) return resolved
    failures += IllegalStateException(
        "Compose resources native directory does not contain ${platform.mainLibraryCandidates}: " + directory.absolutePath
    )
    return null
}

private fun extractLibFromResources(platform: DesktopNativePlatform): ResolvedMpvLibrary? {
    val platformId = platform.id

    val libs = bundledLibraryNames(platformId)
    if (libs.isEmpty()) {
        println("NativeTrace[extract.resources.empty] platform=$platformId")
        return null
    }

    val tmpDir = Files.createTempDirectory("mpv-libs-$platformId").toFile()
    tmpDir.deleteOnExit()

    for (name in libs) {
        val resourcePath = "/$platformId/$name"
        val stream = IMpvPlayer::class.java.getResourceAsStream(resourcePath)
        if (stream == null) {
            println("Resource not found: $resourcePath")
            continue
        }

        val dest = File(tmpDir, name)
        dest.deleteOnExit()

        try {
            Files.copy(stream, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            println("NativeTrace[extract.copy] ${dest.absolutePath} size=${dest.length()}")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stream.close()
        }
    }

    val resolved = resolveMpvLibraryInDirectory(tmpDir, platform, "jar-resources:${platform.id}")
    if (resolved != null) {
        println("Extracted mpv libs to $tmpDir, main lib: ${resolved.mainLibraryPath}")
    } else {
        println("Failed to extract mpv main library from resources for ${platform.id}.")
    }
    return resolved
}

private fun bundledLibraryNames(platform: String): List<String> {
    val classLoader = IMpvPlayer::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
    val urls = classLoader.getResources(platform).toList()
    val names = linkedSetOf<String>()

    for (url in urls) {
        when (url.protocol) {
            "file" -> {
                val dir = Path.of(url.toURI())
                if (Files.isDirectory(dir)) {
                    Files.list(dir).use { paths ->
                        paths.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }
                            .forEach { names += it }
                    }
                }
            }

            "jar" -> {
                val connection = url.openConnection() as? JarURLConnection ?: continue
                val prefix = connection.entryName.trimEnd('/') + "/"
                val entries = connection.jarFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                    val relativeName = entry.name.removePrefix(prefix)
                    if (!relativeName.contains('/')) names += relativeName
                }
            }
        }
    }

    if (names.isEmpty()) {
        addBundledLibraryNamesFromCodeSource(platform, names)
    }

    return names.sortedWith(compareBy<String> {
        when (it) {
            "libmpv_kmp_macos_shim.dylib" -> 0
            "libmpv.2.dylib" -> 1
            "libmpv.dylib" -> 2
            else -> 3
        }
    }.thenBy { it })
}

private fun macosDependencyLoadPriority(name: String): Int {
    return when (name) {
        "libavutil.dylib" -> 0
        "libswresample.dylib" -> 1
        "libswscale.dylib" -> 2
        "libavcodec.dylib" -> 3
        "libavformat.dylib" -> 4
        "libavfilter.dylib" -> 5
        "libavdevice.dylib" -> 6
        "libmpv.2.dylib" -> 100
        "libmpv.dylib" -> 101
        else -> 50
    }
}

private fun addBundledLibraryNamesFromCodeSource(platform: String, names: MutableSet<String>) {
    val location = IMpvPlayer::class.java.protectionDomain?.codeSource?.location ?: return
    val path = Path.of(location.toURI())

    if (Files.isDirectory(path)) {
        val dir = path.resolve(platform)
        if (!Files.isDirectory(dir)) return
        Files.list(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }
                .forEach { names += it }
        }
        return
    }

    if (!Files.isRegularFile(path)) return
    JarFile(path.toFile()).use { jar ->
        val prefix = "$platform/"
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
            val relativeName = entry.name.removePrefix(prefix)
            if (!relativeName.contains('/')) names += relativeName
        }
    }
}

private interface GLLibrary : Library {
    fun glGetIntegerv(pname: Int, params: IntByReference)
}

private interface WindowsKernel32Library : Library {
    fun SetDllDirectoryW(pathName: WString?): Boolean
}

private object GL {
    val libName: String? = try {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("mac") -> "OpenGL"
            os.contains("win") -> "opengl32"
            os.contains("linux") -> "GL"
            else -> "GL"
        }
    } catch (e: Throwable) {
        null
    }

    val nativeLib: NativeLibrary? = if (libName != null) {
        try {
            NativeLibrary.getInstance(libName)
        } catch (e: Throwable) {
            null
        }
    } else null

    val lib: GLLibrary? = if (libName != null) {
        try {
            Native.load(libName, GLLibrary::class.java)
        } catch (e: Throwable) {
            println("Failed to load OpenGL library: $e")
            null
        }
    } else null
}

private object MPV {
    private val platform: DesktopNativePlatform by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        currentDesktopNativePlatform()
    }

    private fun probe(libKey: String) {
        try {
            val raw = NativeLibrary.getInstance(libKey)
            val createAddr = raw.getFunction("mpv_create").hashCode()
            val verFn = raw.getFunction("mpv_client_api_version")
            val ver = verFn.invokeLong(emptyArray())
            println("NativeTrace[mpv.probe] key=$libKey mpv_create=0x${createAddr.toString(16)} api=$ver")
        } catch (e: Throwable) {
            println("NativeTrace[mpv.probe.fail] key=$libKey err=$e")
        }
    }

    val lib: MPVLibrary by lazy {
        nativeTrace("mpv.lib.lazy.begin")
        val failures = mutableListOf<Throwable>()
        val candidate = configuredNativeLibrary(platform, failures)

        if (candidate != null) {
            try {
                return@lazy loadResolvedLibrary(candidate)
            } catch (e: Throwable) {
                println("NativeTrace[mpv.lib.load.${candidate.source}.fail] $e")
                failures += e
            }
        }

        try {
            nativeTrace("mpv.lib.load.system.try")
            val loaded = Native.load("mpv", MPVLibrary::class.java)
            nativeTrace("mpv.lib.load.system.success")
            probe("mpv")
            loaded
        } catch (systemError: Throwable) {
            failures.forEach { systemError.addSuppressed(it) }
            throw systemError
        }
    }

    private fun loadResolvedLibrary(resolved: ResolvedMpvLibrary): MPVLibrary {
        println(
            "NativeTrace[mpv.lib.load.${resolved.source}] " + "platform=${resolved.platform.id} path=${resolved.mainLibraryPath} dir=${resolved.directory.absolutePath}"
        )
        configureWindowsDllSearchPath(resolved)
        loadMacosShimForBundledMpv(resolved)
        preloadBundledDependencies(resolved)
        verifyBundledMpvClientApi(resolved)
        val loaded = Native.load(resolved.mainLibraryPath, MPVLibrary::class.java)
        nativeTrace("mpv.lib.load.${resolved.source}.success")
        probe(resolved.mainLibraryPath)
        return loaded
    }

    private fun configureWindowsDllSearchPath(resolved: ResolvedMpvLibrary) {
        if (resolved.platform.os != "windows") return
        val path = resolved.directory.absolutePath
        NativeLibrary.addSearchPath("mpv", path)
        NativeLibrary.addSearchPath("libmpv", path)
        try {
            val kernel32 = Native.load("kernel32", WindowsKernel32Library::class.java)
            if (kernel32.SetDllDirectoryW(WString(path))) {
                println("NativeTrace[mpv.lib.windows.dll_dir] path=$path")
            } else {
                println("NativeTrace[mpv.lib.windows.dll_dir.fail] path=$path")
            }
        } catch (e: Throwable) {
            println("NativeTrace[mpv.lib.windows.dll_dir.error] path=$path err=$e")
        }
    }

    private fun preloadBundledDependencies(resolved: ResolvedMpvLibrary) {
        if (resolved.platform.os != "darwin") return
        val options = mapOf(Library.OPTION_OPEN_FLAGS to (0x2 or 0x8)) // RTLD_NOW | RTLD_GLOBAL
        for (path in resolved.dependencyLibraryPaths) {
            NativeLibrary.getInstance(path, options)
            println("NativeTrace[mpv.lib.dep.loaded] path=$path")
        }
    }

    private fun verifyBundledMpvClientApi(resolved: ResolvedMpvLibrary) {
        val options = if (resolved.platform.os == "darwin") {
            mapOf(Library.OPTION_OPEN_FLAGS to (0x2 or 0x8)) // RTLD_NOW | RTLD_GLOBAL
        } else {
            emptyMap<String, Any>()
        }
        val raw = NativeLibrary.getInstance(resolved.mainLibraryPath, options)
        raw.getFunction("mpv_create")
        raw.getFunction("mpv_client_api_version")
    }

    private fun loadMacosShimForBundledMpv(resolved: ResolvedMpvLibrary) {
        if (resolved.platform.os != "darwin") return
        val shim = File(resolved.mainLibraryPath).resolveSibling("libmpv_kmp_macos_shim.dylib")
        if (!shim.isFile) {
            println("NativeTrace[mpv.lib.shim.missing] path=${shim.absolutePath}")
            return
        }
        val options = mapOf(Library.OPTION_OPEN_FLAGS to (0x2 or 0x8)) // RTLD_NOW | RTLD_GLOBAL
        NativeLibrary.getInstance(shim.absolutePath, options)
        println("NativeTrace[mpv.lib.shim.loaded] path=${shim.absolutePath}")
    }
}

internal interface RenderContextSupport {
    fun createRenderContext(): Boolean
    fun freeRenderContext()
    fun render(fbo: Int, width: Int, height: Int) // Keep for backward compat or non-SW
    fun renderSw(width: Int, height: Int, stride: Int, format: String, buffer: Pointer)
    fun setRenderCallback(callback: () -> Unit)
}

private class JvmMpvPlayer : IMpvPlayer, RenderContextSupport {
    private var ctx: Pointer? = null
    private var listener: ((MpvEvent) -> Unit)? = null

    @Volatile
    private var running = false
    private var scope: CoroutineScope? = null
    private var eventJob: Job? = null
    private var renderCtx: Pointer? = null
    private var renderCallbackAdapter: mpv_render_update_fn? = null
    private var initParams: mpv_opengl_init_params? = null
    private var getProcAddrCallback: mpv_opengl_get_proc_address_fn? = null

    override fun initialize(): Boolean {
        if (ctx != null) return true
        val rid = System.nanoTime().toString(16)
        nativeTrace("init.$rid.begin")
        LocaleSetter.setNumericCLocale()
        val initBlock = {
            nativeTrace("init.$rid.block.enter")
            nativeTrace("init.$rid.before.client_api_version")
            val apiVersion = MPV.lib.mpv_client_api_version()
            nativeTrace("init.$rid.after.client_api_version=$apiVersion")
            nativeTrace("init.$rid.before.mpv_create")
            ctx = MPV.lib.mpv_create()
            nativeTrace("init.$rid.after.mpv_create.ctx=${ctx != null}")
            if (ctx == null) {
                false
            } else {
                val c = ctx!!
                nativeTrace("init.$rid.before.set_option.vo")
                val voRet = MPV.lib.mpv_set_option_string(c, "vo", "libmpv")
                nativeTrace("init.$rid.after.set_option.vo.ret=$voRet")
                if (voRet != 0) {
                    val err = MPV.lib.mpv_error_string(voRet) ?: "unknown"
                    println("JvmMpvPlayer: failed to set option vo=libmpv: $voRet ($err)")
                    MPV.lib.mpv_terminate_destroy(c)
                    ctx = null
                    false
                } else {
                    nativeTrace("init.$rid.before.mpv_initialize")
                    val r = MPV.lib.mpv_initialize(c)
                    nativeTrace("init.$rid.after.mpv_initialize.ret=$r")
                    if (r != 0) {
                        val err = MPV.lib.mpv_error_string(r) ?: "unknown"
                        println("JvmMpvPlayer: mpv_initialize failed: $r ($err)")
                        MPV.lib.mpv_terminate_destroy(c)
                        ctx = null
                        false
                    } else {
                        nativeTrace("init.$rid.before.request_log")
                        MPV.lib.mpv_request_log_messages(c, "v")
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
            println("JvmMpvPlayer: initialize on EDT failed: $e")
            return false
        }
    }

    override fun attach(view: Any) {
        val c = ctx ?: return
        if (view is Long) {
            val wid = view
            val mem = com.sun.jna.Memory(8)
            mem.setLong(0, wid)
            MPV.lib.mpv_set_property(c, "wid", 4, mem)
        }
    }

    override fun detach() {
        val c = ctx ?: return
        val mem = com.sun.jna.Memory(8)
        mem.setLong(0, 0L)
        MPV.lib.mpv_set_property(c, "wid", 4, mem)
    }

    override fun commandString(cmd: String): Int {
        val c = ctx ?: return -1
        val ret = MPV.lib.mpv_command_string(c, cmd)
        if (ret < 0) {
            val err = MPV.lib.mpv_error_string(ret) ?: "unknown"
            println("JvmMpvPlayer: commandString failed: $ret ($err), cmd=$cmd")
        }
        return ret
    }

    override fun load(uri: String): Int {
        return command("loadfile", uri)
    }

    override fun addToPlaylist(uri: String): Int {
        return command("loadfile", uri, "append")
    }

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
        val c = ctx ?: return
        // format 1 = MPV_FORMAT_STRING
        MPV.lib.mpv_observe_property(c, 0, name, 1)
        startEventLoop()
    }

    override fun removePropertyObservation(name: String) {
        // Not easily supported without tracking reply_userdata, but passing 0 unobserves all with 0?
        // Actually mpv_unobserve_property removes by reply_userdata.
        // If we use 0 for all, we can't unobserve specific property easily without clearing all.
        // For simplicity, we just ignore unobserve or we should map names to IDs.
        // Given the requirement, we'll skip complex ID mapping for now or implement if strictly needed.
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

        val ret = MPV.lib.mpv_command(c, argv)
        if (ret < 0) {
            val err = MPV.lib.mpv_error_string(ret) ?: "unknown"
            println("JvmMpvPlayer: command failed: $ret ($err), args=${args.joinToString(" ")}")
        }
        return ret
    }

    override fun setProperty(name: String, value: String): Int {
        val c = ctx ?: return -1
        return MPV.lib.mpv_set_property_string(c, name, value)
    }

    override fun getProperty(name: String): String? {
        val c = ctx ?: return null
        val ref = PointerByReference()
        val r = MPV.lib.mpv_get_property(c, name, 1, ref)
        if (r < 0) return null
        val p = ref.value ?: return null
        val s = p.getString(0)
        MPV.lib.mpv_free(p)
        return s
    }

    override fun terminate() {
        val c = ctx ?: return
        running = false
        eventJob?.cancel()
        MPV.lib.mpv_wakeup(c) // Wake up wait_event
        renderCtx?.let {
            MPV.lib.mpv_render_context_set_update_callback(it, null, null)
            MPV.lib.mpv_render_context_free(it)
            renderCtx = null
        }
        MPV.lib.mpv_terminate_destroy(c)
        ctx = null
        eventJob = null
        renderCallbackAdapter = null
    }

    private fun startEventLoop() {
        if (running) return
        if (scope == null) return
        running = true
        if (eventJob?.isActive == true) return
        eventJob = scope!!.launch(Dispatchers.IO) {
            val c = ctx ?: return@launch
            while (running && isActive) {
                // Wait 1.0s. If -1 is used, we rely on mpv_wakeup.
                val eventPtr = MPV.lib.mpv_wait_event(c, 1.0)
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

        listener?.invoke(MpvEvent(type, name, value, event.error))
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

    override fun createRenderContext(): Boolean {
        val c = ctx ?: return false
        if (renderCtx != null) return true

        val api = com.sun.jna.Memory(8)
        api.setString(0, "sw") // Force SW rendering on macOS/Metal environment

        val base = mpv_render_param()
        val arr = base.toArray(2) as Array<mpv_render_param>

        arr[0].type = 1 // MPV_RENDER_PARAM_API_TYPE
        arr[0].data = api
        arr[0].write()

        arr[1].type = 0
        arr[1].data = null
        arr[1].write()

        val out = PointerByReference()
        println("JvmMpvPlayer: Calling mpv_render_context_create with sw...")
        val r = MPV.lib.mpv_render_context_create(out, c, arr[0].pointer)
        if (r == 0) {
            renderCtx = out.value
            println("JvmMpvPlayer: mpv_render_context_create success. ctx => $renderCtx")
            return true
        }
        val err = MPV.lib.mpv_error_string(r) ?: "unknown"
        val vo = getProperty("vo")
        println("JvmMpvPlayer: mpv_render_context_create failed: $r ($err), vo=$vo")
        return false
    }

    override fun freeRenderContext() {
        println("JvmMpvPlayer: Freeing RenderContext.")
        renderCtx?.let { MPV.lib.mpv_render_context_free(it); renderCtx = null }
    }

    override fun render(fbo: Int, width: Int, height: Int) {
        // No-op for SW mode
    }

    override fun renderSw(width: Int, height: Int, stride: Int, format: String, buffer: Pointer) {
        val ctx = renderCtx ?: return
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
        // println("JvmMpvPlayer: renderSw: $width x $height, stride=$stride, format=$format, buf=$buffer")

        val err = MPV.lib.mpv_render_context_render(ctx, arr[0].pointer)
        if (err != 0) {
            println("JvmMpvPlayer: mpv_render_context_render failed: $err")
            return
        }

        MPV.lib.mpv_render_context_report_swap(ctx)
    }

    override fun setRenderCallback(callback: () -> Unit) {
        val ctx = renderCtx ?: return
        val adapter = object : mpv_render_update_fn {
            override fun invoke(ctx: Pointer?) {
                if (!running) return
                callback()
            }
        }
        this.renderCallbackAdapter = adapter
        MPV.lib.mpv_render_context_set_update_callback(ctx, adapter, null)
    }
}

actual fun createMpvPlayer(): IMpvPlayer = JvmMpvPlayer()
