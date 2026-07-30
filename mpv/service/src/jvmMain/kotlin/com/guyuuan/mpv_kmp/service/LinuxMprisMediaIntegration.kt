package com.guyuuan.mpv_kmp.service

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

class LinuxMprisMediaIntegration(
    private val config: DesktopMediaIntegrationConfig
) : PlatformMediaIntegration {
    @Volatile
    private var commandHandler: MediaCommandHandler? = null
    @Volatile
    private var connection: DBusConnection? = null
    private val busName = "$MPRIS_BUS_PREFIX.${config.applicationId}"
    private val exportedObject = MprisObject(config) { command ->
        commandHandler?.handle(command)
    }

    @Synchronized
    override fun activate(commandHandler: MediaCommandHandler) {
        if (connection != null) {
            this.commandHandler = commandHandler
            return
        }
        check(currentDesktopOperatingSystem() == DesktopOperatingSystem.Linux) {
            "MPRIS is only available on Linux"
        }

        val newConnection = DBusConnectionBuilder.forSessionBus()
            .withShared(false)
            .build()
        try {
            newConnection.requestBusName(busName)
            newConnection.exportObject(MPRIS_OBJECT_PATH, exportedObject)
        } catch (error: Throwable) {
            runCatching { newConnection.releaseBusName(busName) }
            runCatching { newConnection.close() }
            throw error
        }
        this.commandHandler = commandHandler
        connection = newConnection
        exportedObject.attach(newConnection)
    }

    override fun updateMetadata(metadata: PlaybackMetadata?) {
        exportedObject.updateMetadata(metadata)
    }

    override fun updatePlaybackState(state: PlaybackSnapshot) {
        exportedObject.updateSnapshot(state)
    }

    @Synchronized
    override fun deactivate() {
        val currentConnection = connection ?: return
        connection = null
        commandHandler = null
        exportedObject.detach()
        runCatching { currentConnection.unExportObject(MPRIS_OBJECT_PATH) }
        runCatching { currentConnection.releaseBusName(busName) }
        runCatching { currentConnection.close() }
    }
}

@DBusInterfaceName(MPRIS_ROOT_INTERFACE)
@DBusProperty(name = "CanQuit", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanRaise", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "HasTrackList", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Identity", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "DesktopEntry", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "SupportedUriSchemes", type = List::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "SupportedMimeTypes", type = List::class, access = DBusProperty.Access.READ)
interface MprisRoot : DBusInterface {
    fun Raise()

    fun Quit()
}

@DBusInterfaceName(MPRIS_PLAYER_INTERFACE)
@DBusProperty(name = "PlaybackStatus", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "LoopStatus", type = String::class)
@DBusProperty(name = "Rate", type = Double::class)
@DBusProperty(name = "Shuffle", type = Boolean::class)
@DBusProperty(name = "Metadata", type = Map::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Volume", type = Double::class)
@DBusProperty(name = "Position", type = Long::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "MinimumRate", type = Double::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "MaximumRate", type = Double::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanGoNext", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanPlay", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanPause", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanSeek", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanControl", type = Boolean::class, access = DBusProperty.Access.READ)
interface MprisPlayer : DBusInterface {
    fun Next()

    fun Previous()

    fun Pause()

    fun PlayPause()

    fun Stop()

    fun Play()

    fun Seek(offset: Long)

    fun SetPosition(trackId: DBusPath, position: Long)

    fun OpenUri(uri: String)

    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

internal class MprisObject(
    private val config: DesktopMediaIntegrationConfig,
    private val dispatchCommand: (MediaCommand) -> Unit
) : MprisRoot, MprisPlayer, Properties {
    @Volatile
    private var connection: DBusConnection? = null
    @Volatile
    private var metadata: PlaybackMetadata? = null
    @Volatile
    private var snapshot = PlaybackSnapshot()
    @Volatile
    private var artworkFile: Path? = null

    fun attach(connection: DBusConnection) {
        this.connection = connection
        emitPlayerProperties(playerProperties().keys)
    }

    @Synchronized
    fun detach() {
        connection = null
        clearArtworkFile()
    }

    @Synchronized
    fun updateMetadata(metadata: PlaybackMetadata?) {
        if (this.metadata == metadata) return
        updateArtworkFile(metadata)
        this.metadata = metadata
        emitPlayerProperties(setOf("Metadata"))
    }

    @Synchronized
    fun updateSnapshot(state: PlaybackSnapshot) {
        val previous = snapshot
        snapshot = state

        val changed = buildSet {
            if (previous.status != state.status) add("PlaybackStatus")
            if (previous.durationMillis != state.durationMillis) {
                add("Metadata")
            }
            if (previous.speed != state.speed) add("Rate")
            if (previous.volume != state.volume) add("Volume")
            if (previous.repeatMode != state.repeatMode) add("LoopStatus")
            if (previous.shuffleEnabled != state.shuffleEnabled) add("Shuffle")
            if (previous.availableCommands != state.availableCommands) {
                add("CanGoNext")
                add("CanGoPrevious")
                add("CanPlay")
                add("CanPause")
                add("CanSeek")
                add("CanControl")
            }
        }
        emitPlayerProperties(changed)

        val positionDelta = state.positionMillis - previous.positionMillis
        if (previous.metadata?.mediaId == state.metadata?.mediaId &&
            (positionDelta < 0 || positionDelta > SEEK_DISCONTINUITY_THRESHOLD_MS)
        ) {
            sendSignal(MprisPlayer.Seeked(MPRIS_OBJECT_PATH, state.positionMillis.toMicros()))
        }
    }

    override fun Raise() {
        if (config.applicationController.canRaise) config.applicationController.raise()
    }

    override fun Quit() {
        if (config.applicationController.canQuit) config.applicationController.quit()
    }

    override fun Next() = dispatchIfAvailable(MediaCommand.Next)

    override fun Previous() = dispatchIfAvailable(MediaCommand.Previous)

    override fun Pause() = dispatchIfAvailable(MediaCommand.Pause)

    override fun PlayPause() = dispatchIfAvailable(MediaCommand.TogglePlayPause)

    override fun Stop() = dispatchIfAvailable(MediaCommand.Stop)

    override fun Play() = dispatchIfAvailable(MediaCommand.Play)

    override fun Seek(offset: Long) =
        dispatchIfAvailable(MediaCommand.SeekBy(offset.fromMicros()))

    override fun SetPosition(trackId: DBusPath, position: Long) {
        if (trackId == currentTrackId()) {
            dispatchIfAvailable(MediaCommand.SeekTo(position.coerceAtLeast(0).fromMicros()))
        }
    }

    override fun OpenUri(uri: String) {
        if (config.applicationController.canOpenUri) config.applicationController.openUri(uri)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        property(interfaceName, propertyName) as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        require(interfaceName == MPRIS_PLAYER_INTERFACE) {
            "Properties on $interfaceName are read-only"
        }
        when (propertyName) {
            "Rate" -> {
                val speed = (value as? Number)?.toFloat()
                    ?: error("Rate must be numeric")
                dispatchIfAvailable(MediaCommand.SetSpeed(speed.coerceIn(0.01f, 100f)))
            }
            "Volume" -> {
                val volume = (value as? Number)?.toDouble()
                    ?: error("Volume must be numeric")
                dispatchIfAvailable(
                    MediaCommand.SetVolume((volume * 100.0).toFloat().coerceIn(0f, 100f))
                )
            }
            "LoopStatus" -> {
                val repeatMode = when (value as? String) {
                    "None" -> PlaybackRepeatMode.None
                    "Track" -> PlaybackRepeatMode.One
                    "Playlist" -> PlaybackRepeatMode.All
                    else -> error("LoopStatus must be None, Track, or Playlist")
                }
                dispatchIfAvailable(MediaCommand.SetRepeatMode(repeatMode))
            }
            "Shuffle" -> dispatchIfAvailable(
                MediaCommand.SetShuffle(value as? Boolean ?: error("Shuffle must be boolean"))
            )
            else -> error("Property $propertyName is read-only or unknown")
        }
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        MPRIS_ROOT_INTERFACE -> rootProperties()
        MPRIS_PLAYER_INTERFACE -> playerProperties()
        else -> emptyMap()
    }.mapValues { (name, value) -> value.toVariant(name) }

    override fun getObjectPath(): String = MPRIS_OBJECT_PATH

    override fun isRemote(): Boolean = false

    private fun property(interfaceName: String, propertyName: String): Any = when (interfaceName) {
        MPRIS_ROOT_INTERFACE -> rootProperties()[propertyName]
        MPRIS_PLAYER_INTERFACE -> playerProperties()[propertyName]
        else -> null
    } ?: error("Unknown D-Bus property $interfaceName.$propertyName")

    private fun rootProperties(): Map<String, Any> = mapOf(
        "CanQuit" to config.applicationController.canQuit,
        "CanRaise" to config.applicationController.canRaise,
        "HasTrackList" to false,
        "Identity" to config.identity,
        "DesktopEntry" to config.desktopEntry.orEmpty(),
        "SupportedUriSchemes" to config.supportedUriSchemes,
        "SupportedMimeTypes" to config.supportedMimeTypes
    )

    private fun playerProperties(): Map<String, Any> {
        val commands = snapshot.availableCommands
        return mapOf(
            "PlaybackStatus" to snapshot.status.toMprisPlaybackStatus(),
            "LoopStatus" to snapshot.repeatMode.toMprisLoopStatus(),
            "Rate" to snapshot.speed.toDouble(),
            "Shuffle" to snapshot.shuffleEnabled,
            "Metadata" to mprisMetadata(),
            "Volume" to (snapshot.volume / 100f).toDouble(),
            "Position" to snapshot.positionMillis.toMicros(),
            "MinimumRate" to 0.01,
            "MaximumRate" to 100.0,
            "CanGoNext" to (MediaCommandType.Next in commands),
            "CanGoPrevious" to (MediaCommandType.Previous in commands),
            "CanPlay" to (MediaCommandType.Play in commands),
            "CanPause" to (MediaCommandType.Pause in commands),
            "CanSeek" to (
                MediaCommandType.SeekTo in commands || MediaCommandType.SeekBy in commands
            ),
            "CanControl" to commands.isNotEmpty()
        )
    }

    private fun mprisMetadata(): Map<String, Variant<*>> {
        val value = metadata ?: return mapOf(
            "mpris:trackid" to Variant(NO_TRACK_PATH, "o")
        )
        return buildMap {
            put("mpris:trackid", Variant(currentTrackId(), "o"))
            if (snapshot.durationMillis > 0) {
                put("mpris:length", Variant(snapshot.durationMillis.toMicros()))
            }
            put("xesam:url", Variant(value.uri))
            put("xesam:title", Variant(value.title))
            value.artist?.let { put("xesam:artist", Variant(listOf(it), "as")) }
            value.albumTitle?.let { put("xesam:album", Variant(it)) }
            when (val artwork = value.artwork) {
                is PlaybackArtwork.Uri -> put("mpris:artUrl", Variant(artwork.value))
                is PlaybackArtwork.Bytes -> artworkFile?.let {
                    put("mpris:artUrl", Variant(it.toUri().toString()))
                }
                null -> Unit
            }
        }
    }

    private fun updateArtworkFile(metadata: PlaybackMetadata?) {
        clearArtworkFile()
        val bytes = (metadata?.artwork as? PlaybackArtwork.Bytes)?.toByteArray() ?: return
        val file = runCatching {
            Files.createTempFile(MPRIS_ARTWORK_PREFIX, bytes.imageFileSuffix())
        }.getOrNull() ?: return
        artworkFile = runCatching {
            Files.write(file, bytes)
            file.toFile().deleteOnExit()
            file
        }.onFailure {
            runCatching { Files.deleteIfExists(file) }
        }.getOrNull()
    }

    private fun clearArtworkFile() {
        artworkFile?.let { file -> runCatching { Files.deleteIfExists(file) } }
        artworkFile = null
    }

    private fun currentTrackId(): DBusPath {
        val mediaId = metadata?.mediaId ?: return NO_TRACK_PATH
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(mediaId.encodeToByteArray())
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return DBusPath("$TRACK_OBJECT_PREFIX/$digest")
    }

    private fun dispatchIfAvailable(command: MediaCommand) {
        if (command.type in snapshot.availableCommands) dispatchCommand(command)
    }

    private fun emitPlayerProperties(names: Set<String>) {
        if (names.isEmpty()) return
        val properties = playerProperties()
        val changed = names.mapNotNull { name ->
            properties[name]?.let { name to it.toVariant(name) }
        }.toMap()
        if (changed.isEmpty()) return
        sendSignal(
            Properties.PropertiesChanged(
                MPRIS_OBJECT_PATH,
                MPRIS_PLAYER_INTERFACE,
                changed,
                emptyList()
            )
        )
    }

    private fun sendSignal(signal: DBusSignal) {
        connection?.let { currentConnection ->
            runCatching { currentConnection.sendMessage(signal) }
        }
    }
}

private fun Any.toVariant(propertyName: String): Variant<*> = when (propertyName) {
    "Metadata" -> Variant(this, "a{sv}")
    "SupportedUriSchemes",
    "SupportedMimeTypes" -> Variant(this, "as")
    else -> Variant(this)
}

private fun PlaybackStatus.toMprisPlaybackStatus(): String = when (this) {
    PlaybackStatus.Playing -> "Playing"
    PlaybackStatus.Paused,
    PlaybackStatus.Loading -> "Paused"
    else -> "Stopped"
}

private fun PlaybackRepeatMode.toMprisLoopStatus(): String = when (this) {
    PlaybackRepeatMode.None -> "None"
    PlaybackRepeatMode.One -> "Track"
    PlaybackRepeatMode.All -> "Playlist"
}

private fun Long.toMicros(): Long = this * 1_000L

private fun Long.fromMicros(): Long = this / 1_000L

private fun ByteArray.imageFileSuffix(): String = when {
    size >= 4 &&
        this[0] == 0x89.toByte() &&
        this[1] == 0x50.toByte() &&
        this[2] == 0x4e.toByte() &&
        this[3] == 0x47.toByte() -> ".png"

    size >= 3 &&
        this[0] == 0xff.toByte() &&
        this[1] == 0xd8.toByte() &&
        this[2] == 0xff.toByte() -> ".jpg"

    size >= 4 &&
        this[0] == 'G'.code.toByte() &&
        this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() &&
        this[3] == '8'.code.toByte() -> ".gif"

    size >= 12 &&
        decodeToString(0, 4) == "RIFF" &&
        decodeToString(8, 12) == "WEBP" -> ".webp"

    else -> ".img"
}

private const val MPRIS_BUS_PREFIX = "org.mpris.MediaPlayer2"
private const val MPRIS_OBJECT_PATH = "/org/mpris/MediaPlayer2"
private const val MPRIS_ARTWORK_PREFIX = "mpv-kmp-mpris-artwork-"
private const val MPRIS_ROOT_INTERFACE = "org.mpris.MediaPlayer2"
private const val MPRIS_PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
private const val TRACK_OBJECT_PREFIX = "/org/mpris/MediaPlayer2/track"
private val NO_TRACK_PATH = DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack")
private const val SEEK_DISCONTINUITY_THRESHOLD_MS = 2_000L
