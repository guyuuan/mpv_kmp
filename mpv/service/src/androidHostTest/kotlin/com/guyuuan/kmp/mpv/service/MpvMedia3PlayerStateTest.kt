package com.guyuuan.kmp.mpv.service

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals

class MpvMedia3PlayerStateTest {
    @Test
    fun emptyPublishedPlaylistForcesNonTerminalPlaybackIntoIdle() {
        assertEquals(
            Player.STATE_IDLE,
            PlaybackStatus.Loading.toMedia3PlaybackState(hasPublishedMedia = false)
        )
        assertEquals(
            Player.STATE_IDLE,
            PlaybackStatus.Playing.toMedia3PlaybackState(hasPublishedMedia = false)
        )
        assertEquals(
            Player.STATE_IDLE,
            PlaybackStatus.Paused.toMedia3PlaybackState(hasPublishedMedia = false)
        )
    }

    @Test
    fun publishedPlaylistPreservesActivePlaybackState() {
        assertEquals(
            Player.STATE_BUFFERING,
            PlaybackStatus.Loading.toMedia3PlaybackState(hasPublishedMedia = true)
        )
        assertEquals(
            Player.STATE_READY,
            PlaybackStatus.Playing.toMedia3PlaybackState(hasPublishedMedia = true)
        )
    }
}
