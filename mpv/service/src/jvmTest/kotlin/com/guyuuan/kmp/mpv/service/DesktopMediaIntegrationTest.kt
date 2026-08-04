package com.guyuuan.kmp.mpv.service

import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.Variant

class DesktopMediaIntegrationTest {
    @Test
    fun operatingSystemSelectionUsesJvmOsName() {
        assertEquals(DesktopOperatingSystem.Macos, currentDesktopOperatingSystem("Mac OS X"))
        assertEquals(DesktopOperatingSystem.Windows, currentDesktopOperatingSystem("Windows 11"))
        assertEquals(DesktopOperatingSystem.Linux, currentDesktopOperatingSystem("Linux"))
        assertEquals(DesktopOperatingSystem.Unsupported, currentDesktopOperatingSystem("FreeBSD"))
    }

    @Test
    fun mprisMaterializesByteArtworkAsAFileUri() {
        val mpris = MprisObject(
            config = DesktopMediaIntegrationConfig(
                applicationId = "artwork_test",
                identity = "Artwork Test"
            ),
            dispatchCommand = {}
        )
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a
        )
        val resolvedMetadata = PlaybackMetadata(
            mediaId = "track-with-bytes",
            uri = "file:///music/track.flac",
            title = "Track",
            artwork = PlaybackArtwork.Bytes(pngBytes)
        )
        mpris.updateMetadata(resolvedMetadata)
        mpris.updateSnapshot(
            PlaybackSnapshot(
                metadata = resolvedMetadata.copy(
                    artwork = PlaybackArtwork.Uri("https://example.test/cover.png")
                ),
                status = PlaybackStatus.Playing
            )
        )

        @Suppress("UNCHECKED_CAST")
        val metadata = mpris.GetAll("org.mpris.MediaPlayer2.Player")
            .unwrapped("Metadata") as Map<String, Variant<*>>
        val artworkUri = metadata.getValue("mpris:artUrl").value as String
        val artworkFile = java.nio.file.Path.of(URI(artworkUri))
        assertTrue(Files.exists(artworkFile))
        assertContentEquals(pngBytes, Files.readAllBytes(artworkFile))

        mpris.detach()
        assertFalse(Files.exists(artworkFile))
    }

    @Test
    fun mprisObjectPublishesPropertiesAndRoutesCommands() {
        val commands = mutableListOf<MediaCommand>()
        val controller = RecordingApplicationController()
        val mpris = MprisObject(
            config = DesktopMediaIntegrationConfig(
                applicationId = "test_player",
                identity = "Test Player",
                desktopEntry = "test-player",
                supportedUriSchemes = listOf("file", "https"),
                applicationController = controller
            ),
            dispatchCommand = commands::add
        )
        val metadata = PlaybackMetadata(
            mediaId = "track/1",
            uri = "file:///music/track.flac",
            title = "Track",
            artist = "Artist",
            albumTitle = "Album",
            artwork = PlaybackArtwork.Uri("file:///music/cover.jpg"),
            mediaType = PlaybackMediaType.Audio
        )
        mpris.updateMetadata(metadata)
        mpris.updateSnapshot(
            PlaybackSnapshot(
                metadata = metadata,
                status = PlaybackStatus.Playing,
                positionMillis = 12_000,
                durationMillis = 180_000,
                speed = 1.25f,
                volume = 40f,
                repeatMode = PlaybackRepeatMode.All,
                shuffleEnabled = true
            )
        )

        val root = mpris.GetAll("org.mpris.MediaPlayer2")
        val player = mpris.GetAll("org.mpris.MediaPlayer2.Player")
        assertEquals("Test Player", root.unwrapped("Identity"))
        assertEquals("Playing", player.unwrapped("PlaybackStatus"))
        assertEquals(12_000_000L, player.unwrapped("Position"))
        assertEquals(0.4, player.unwrapped("Volume") as Double, absoluteTolerance = 0.000_001)
        assertEquals("Playlist", player.unwrapped("LoopStatus"))
        assertEquals(true, player.unwrapped("Shuffle"))

        @Suppress("UNCHECKED_CAST")
        val publishedMetadata = player.unwrapped("Metadata") as Map<String, Variant<*>>
        val trackId = assertIs<DBusPath>(publishedMetadata.getValue("mpris:trackid").value)
        assertEquals("Track", publishedMetadata.getValue("xesam:title").value)

        mpris.Pause()
        mpris.Seek(5_000_000)
        mpris.SetPosition(trackId, 30_000_000)
        mpris.Set("org.mpris.MediaPlayer2.Player", "Rate", 1.5)
        mpris.Set("org.mpris.MediaPlayer2.Player", "Volume", 0.75)
        mpris.Set("org.mpris.MediaPlayer2.Player", "LoopStatus", "Track")
        mpris.Set("org.mpris.MediaPlayer2.Player", "Shuffle", false)
        mpris.OpenUri("https://example.test/stream")

        assertEquals(
            listOf(
                MediaCommand.Pause,
                MediaCommand.SeekBy(5_000),
                MediaCommand.SeekTo(30_000),
                MediaCommand.SetSpeed(1.5f),
                MediaCommand.SetVolume(75f),
                MediaCommand.SetRepeatMode(PlaybackRepeatMode.One),
                MediaCommand.SetShuffle(false)
            ),
            commands
        )
        assertEquals(listOf("https://example.test/stream"), controller.openedUris)
    }

    @Test
    fun macosNativeBridgeCanBeCreatedAndReleasedRepeatedly() {
        if (currentDesktopOperatingSystem() != DesktopOperatingSystem.Macos) return
        repeat(2) {
            val integration = MacosNowPlayingMediaIntegration()
            integration.activate { }
            integration.updateMetadata(
                PlaybackMetadata(
                    mediaId = "native-test",
                    uri = "file:///tmp/native-test.mp3",
                    title = "Native Test",
                    mediaType = PlaybackMediaType.Audio
                )
            )
            integration.updatePlaybackState(
                PlaybackSnapshot(
                    status = PlaybackStatus.Paused,
                    positionMillis = 1_000,
                    durationMillis = 10_000
                )
            )
            integration.deactivate()
        }
        assertTrue(true)
    }

    @Test
    fun desktopStateStoreRoundTripsWithoutPreferencesSizeLimits() {
        val directory = Files.createTempDirectory("mpv-kmp-service-test-")
        try {
            val store = DesktopPlaybackStateStore(
                applicationId = "test_player",
                storageFile = directory.resolve("playback-state")
            )
            val state = RestorablePlaybackState(
                queue = listOf(
                    PlaybackMetadata(
                        mediaId = "large-artwork",
                        uri = "file:///track.flac",
                        title = "Track",
                        artwork = PlaybackArtwork.Bytes(ByteArray(10_000) { (it % 251).toByte() })
                    )
                ),
                currentIndex = 0,
                positionMillis = 5_000,
                speed = 1f,
                repeatMode = PlaybackRepeatMode.None,
                shuffleEnabled = false,
                paused = true
            )

            store.save(state)
            assertEquals(state, store.load())
            store.clear()
            assertEquals(null, store.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Variant<*>>.unwrapped(name: String): Any =
        checkNotNull(this[name]).value as Any

    private class RecordingApplicationController : DesktopApplicationController {
        override val canRaise = true
        override val canQuit = true
        override val canOpenUri = true
        val openedUris = mutableListOf<String>()

        override fun raise() = Unit

        override fun quit() = Unit

        override fun openUri(uri: String) {
            openedUris += uri
        }
    }
}
