package com.guyuuan.mpv_kmp.service

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class MacosNowPlayingMediaIntegration : NativeDesktopMediaIntegration(
    nativePlatform = "darwin",
    nativeWindowHandle = 0
)

class WindowsSmtcMediaIntegration(
    nativeWindowHandle: Long?
) : NativeDesktopMediaIntegration(
    nativePlatform = "windows",
    nativeWindowHandle = nativeWindowHandle ?: 0
)

abstract class NativeDesktopMediaIntegration internal constructor(
    private val nativePlatform: String,
    private val nativeWindowHandle: Long
) : PlatformMediaIntegration {
    private var commandHandler: MediaCommandHandler? = null
    private var library: NativeMediaLibrary? = null
    private var nativeContext: Pointer? = null
    private var metadata: PlaybackMetadata? = null
    private var snapshot = PlaybackSnapshot()

    private val callback = NativeCommandCallback { command, value ->
        val mediaCommand = command.toMediaCommand(value) ?: return@NativeCommandCallback
        commandHandler?.handle(mediaCommand)
    }

    @Synchronized
    override fun activate(commandHandler: MediaCommandHandler) {
        if (nativeContext != null) {
            this.commandHandler = commandHandler
            return
        }
        val loadedLibrary = NativeMediaLibraryLoader.load(nativePlatform)
        val context = checkNotNull(
            loadedLibrary.mpv_kmp_media_create(callback, nativeWindowHandle)
        ) {
            "Unable to initialize the $nativePlatform system media bridge"
        }
        this.commandHandler = commandHandler
        library = loadedLibrary
        nativeContext = context
        publishMetadata()
        publishSnapshot()
    }

    @Synchronized
    override fun updateMetadata(metadata: PlaybackMetadata?) {
        this.metadata = metadata
        publishMetadata()
    }

    @Synchronized
    override fun updatePlaybackState(state: PlaybackSnapshot) {
        snapshot = state
        publishSnapshot()
    }

    @Synchronized
    override fun deactivate() {
        val context = nativeContext ?: return
        nativeContext = null
        commandHandler = null
        library?.mpv_kmp_media_destroy(context)
        library = null
    }

    private fun publishMetadata() {
        val context = nativeContext ?: return
        val value = metadata
        val artworkBytes = (value?.artwork as? PlaybackArtwork.Bytes)?.toByteArray()
        library?.mpv_kmp_media_update_metadata(
            context = context,
            mediaId = value?.mediaId,
            title = value?.title,
            artist = value?.artist,
            album = value?.albumTitle,
            mediaType = value?.mediaType?.ordinal ?: PlaybackMediaType.Unknown.ordinal,
            artworkUri = (value?.artwork as? PlaybackArtwork.Uri)?.value,
            artworkBytes = artworkBytes,
            artworkLength = artworkBytes?.size ?: 0
        )
    }

    private fun publishSnapshot() {
        val context = nativeContext ?: return
        library?.mpv_kmp_media_update_state(
            context = context,
            status = snapshot.status.toNativeStatus(),
            positionMillis = snapshot.positionMillis,
            durationMillis = snapshot.durationMillis,
            speed = snapshot.speed.toDouble(),
            queueIndex = snapshot.queueIndex ?: -1,
            queueSize = snapshot.queueSize,
            commandMask = snapshot.availableCommands.toNativeMask(),
            repeatMode = snapshot.repeatMode.ordinal,
            shuffleEnabled = if (snapshot.shuffleEnabled) 1 else 0
        )
    }
}

internal fun interface NativeCommandCallback : Callback {
    fun invoke(command: Int, value: Double)
}

internal interface NativeMediaLibrary : Library {
    fun mpv_kmp_media_create(callback: NativeCommandCallback, nativeWindowHandle: Long): Pointer?

    fun mpv_kmp_media_destroy(context: Pointer)

    fun mpv_kmp_media_update_metadata(
        context: Pointer,
        mediaId: String?,
        title: String?,
        artist: String?,
        album: String?,
        mediaType: Int,
        artworkUri: String?,
        artworkBytes: ByteArray?,
        artworkLength: Int
    )

    fun mpv_kmp_media_update_state(
        context: Pointer,
        status: Int,
        positionMillis: Long,
        durationMillis: Long,
        speed: Double,
        queueIndex: Int,
        queueSize: Int,
        commandMask: Long,
        repeatMode: Int,
        shuffleEnabled: Int
    )
}

private object NativeMediaLibraryLoader {
    private val libraries = ConcurrentHashMap<String, NativeMediaLibrary>()

    fun load(platform: String): NativeMediaLibrary = libraries.computeIfAbsent(platform) {
        loadUncached(platform)
    }

    private fun loadUncached(platform: String): NativeMediaLibrary {
        val configuredDirectory = System.getProperty(NATIVE_MEDIA_DIRECTORY_PROPERTY)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val fileName = when (platform) {
            "darwin" -> "libmpv_kmp_service_media.dylib"
            "windows" -> "mpv_kmp_service_media.dll"
            else -> error("No native media bridge is defined for $platform")
        }
        val configuredFile = configuredDirectory?.let { java.io.File(it, fileName) }
        val nativeFile = configuredFile?.takeIf(java.io.File::isFile)
            ?: extractBundledLibrary(platform, fileName)
        return Native.load(nativeFile.absolutePath, NativeMediaLibrary::class.java)
    }

    private fun extractBundledLibrary(platform: String, fileName: String): java.io.File {
        val architecture = when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64" -> "x86-64"
            else -> error("Unsupported desktop architecture: ${System.getProperty("os.arch")}")
        }
        val resourcePath = "/$platform-$architecture/$fileName"
        val stream = NativeMediaLibraryLoader::class.java.getResourceAsStream(resourcePath)
            ?: error(
                "Bundled system media bridge was not found at $resourcePath. " +
                    "Set $NATIVE_MEDIA_DIRECTORY_PROPERTY to a directory containing $fileName."
            )
        val temporaryDirectory = Files.createTempDirectory("mpv-kmp-service-")
        val destination = temporaryDirectory.resolve(fileName)
        stream.use {
            Files.copy(it, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        destination.toFile().deleteOnExit()
        temporaryDirectory.toFile().deleteOnExit()
        return destination.toFile()
    }
}

private fun Int.toMediaCommand(value: Double): MediaCommand? = when (this) {
    NATIVE_COMMAND_PLAY -> MediaCommand.Play
    NATIVE_COMMAND_PAUSE -> MediaCommand.Pause
    NATIVE_COMMAND_TOGGLE -> MediaCommand.TogglePlayPause
    NATIVE_COMMAND_STOP -> MediaCommand.Stop
    NATIVE_COMMAND_SEEK_TO -> MediaCommand.SeekTo(value.toLong().coerceAtLeast(0))
    NATIVE_COMMAND_SEEK_BY -> MediaCommand.SeekBy(value.toLong())
    NATIVE_COMMAND_NEXT -> MediaCommand.Next
    NATIVE_COMMAND_PREVIOUS -> MediaCommand.Previous
    NATIVE_COMMAND_SET_SPEED -> MediaCommand.SetSpeed(value.toFloat().coerceIn(0.01f, 100f))
    NATIVE_COMMAND_SET_REPEAT_MODE -> PlaybackRepeatMode.entries
        .getOrNull(value.toInt())
        ?.let(MediaCommand::SetRepeatMode)
    NATIVE_COMMAND_SET_SHUFFLE -> MediaCommand.SetShuffle(value != 0.0)
    else -> null
}

private fun PlaybackStatus.toNativeStatus(): Int = when (this) {
    PlaybackStatus.Idle -> 0
    PlaybackStatus.Loading -> 1
    PlaybackStatus.Playing -> 2
    PlaybackStatus.Paused -> 3
    PlaybackStatus.Stopped -> 4
    PlaybackStatus.Ended -> 5
    PlaybackStatus.Error -> 6
    PlaybackStatus.Disposed -> 7
}

private fun Set<MediaCommandType>.toNativeMask(): Long = fold(0L) { mask, command ->
    mask or (1L shl command.ordinal)
}

private const val NATIVE_COMMAND_PLAY = 0
private const val NATIVE_COMMAND_PAUSE = 1
private const val NATIVE_COMMAND_TOGGLE = 2
private const val NATIVE_COMMAND_STOP = 3
private const val NATIVE_COMMAND_SEEK_TO = 4
private const val NATIVE_COMMAND_SEEK_BY = 5
private const val NATIVE_COMMAND_NEXT = 6
private const val NATIVE_COMMAND_PREVIOUS = 7
private const val NATIVE_COMMAND_SET_SPEED = 8
private const val NATIVE_COMMAND_SET_REPEAT_MODE = 10
private const val NATIVE_COMMAND_SET_SHUFFLE = 11
private const val NATIVE_MEDIA_DIRECTORY_PROPERTY = "mpv.kmp.service.native.dir"
