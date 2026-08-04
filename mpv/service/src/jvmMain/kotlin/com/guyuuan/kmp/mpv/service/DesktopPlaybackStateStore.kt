package com.guyuuan.kmp.mpv.service

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Persists desktop playback state in an application-specific user data file. */
class DesktopPlaybackStateStore(
    applicationId: String = "mpv_kmp",
    private val storageFile: Path = defaultStorageFile(applicationId)
) : PlaybackStateStore {
    init {
        require(applicationId.matches(Regex("[A-Za-z0-9_]+"))) {
            "Desktop application ID may only contain letters, digits, and underscores"
        }
    }

    override fun load(): RestorablePlaybackState? {
        if (!Files.isRegularFile(storageFile)) return null
        return PlaybackStateCodec.decode(Files.readString(storageFile))
    }

    override fun save(state: RestorablePlaybackState) {
        val parent = storageFile.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporaryFile = parent.resolve("${storageFile.fileName}.tmp")
        Files.writeString(temporaryFile, PlaybackStateCodec.encode(state))
        try {
            Files.move(
                temporaryFile,
                storageFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryFile, storageFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun clear() {
        Files.deleteIfExists(storageFile)
    }
}

private fun defaultStorageFile(applicationId: String): Path {
    val userHome = Path.of(System.getProperty("user.home", "."))
    val dataRoot = when (currentDesktopOperatingSystem()) {
        DesktopOperatingSystem.Macos -> userHome.resolve("Library/Application Support")
        DesktopOperatingSystem.Windows -> System.getenv("APPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: userHome.resolve("AppData/Roaming")
        DesktopOperatingSystem.Linux -> System.getenv("XDG_STATE_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: userHome.resolve(".local/state")
        DesktopOperatingSystem.Unsupported -> userHome.resolve(".mpv-kmp")
    }
    return dataRoot.resolve(applicationId).resolve("playback-state")
}
