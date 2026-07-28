package com.guyuuan.mpv_kmp.service

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
