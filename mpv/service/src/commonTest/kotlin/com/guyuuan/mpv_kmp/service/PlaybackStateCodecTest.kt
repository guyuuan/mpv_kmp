package com.guyuuan.mpv_kmp.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackStateCodecTest {
    @Test
    fun roundTripsTheCompleteRestorableState() {
        val state = RestorablePlaybackState(
            queue = listOf(
                PlaybackMetadata(
                    mediaId = "track/1",
                    uri = "https://example.test/one.mp3?token=a+b",
                    title = "第一首\nTrack",
                    artist = "Artist",
                    albumTitle = "",
                    artwork = PlaybackArtwork.Uri("https://example.test/cover.jpg"),
                    mediaType = PlaybackMediaType.Audio,
                    extras = mapOf("chapter" to "1", "source" to "测试")
                ),
                PlaybackMetadata(
                    mediaId = "track/2",
                    uri = "file:///two.mp4",
                    title = "Second",
                    artwork = PlaybackArtwork.Bytes(byteArrayOf(0, 1, 2, -1)),
                    mediaType = PlaybackMediaType.Video
                )
            ),
            currentIndex = 1,
            positionMillis = 12_345,
            speed = 1.5f,
            repeatMode = PlaybackRepeatMode.All,
            shuffleEnabled = true,
            paused = false
        )

        assertEquals(state, PlaybackStateCodec.decode(PlaybackStateCodec.encode(state)))
    }

    @Test
    fun rejectsTruncatedOrUnknownState() {
        assertNull(PlaybackStateCodec.decode("not-a-playback-state"))
        assertNull(PlaybackStateCodec.decode("mpv-kmp-playback-state\n99"))
    }
}
