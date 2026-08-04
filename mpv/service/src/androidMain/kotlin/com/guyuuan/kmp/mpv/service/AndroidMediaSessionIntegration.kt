package com.guyuuan.kmp.mpv.service

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Android [PlatformMediaIntegration] backed by a Media3 [Player].
 *
 * The owning [MpvMediaSessionService] publishes this player through a MediaSession. Coordinator
 * state reaches Media3 exclusively through [updateMetadata] and [updatePlaybackState], while
 * Media3 commands are routed back through the handler supplied to [activate].
 */
@UnstableApi
class AndroidMediaSessionIntegration(
    private val looper: Looper = Looper.getMainLooper()
) : PlatformMediaIntegration {
    private var media3Player: MpvMedia3Player? = null

    val player: Player
        get() = checkNotNull(media3Player) {
            "AndroidMediaSessionIntegration must be activated before accessing its player"
        }

    override fun activate(commandHandler: MediaCommandHandler) {
        if (media3Player != null) return
        media3Player = MpvMedia3Player(commandHandler, looper)
    }

    override fun updateMetadata(metadata: PlaybackMetadata?) {
        media3Player?.updateMetadata(metadata)
    }

    override fun updatePlaybackState(state: PlaybackSnapshot) {
        media3Player?.updatePlaybackState(state)
    }

    override fun deactivate() {
        val player = media3Player ?: return
        media3Player = null
        player.releaseFromIntegration()
    }
}
