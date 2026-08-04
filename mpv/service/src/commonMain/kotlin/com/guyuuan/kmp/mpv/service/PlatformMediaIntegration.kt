package com.guyuuan.kmp.mpv.service

fun interface MediaCommandHandler {
    fun handle(command: MediaCommand)
}

/**
 * A platform media surface such as Android Media3, Apple Now Playing, SMTC, or MPRIS.
 *
 * Implementations publish player state outward and route system commands back through the
 * supplied [MediaCommandHandler]. They must release every callback and native resource from
 * [deactivate].
 *
 * [updateMetadata] and [updatePlaybackState] are independent channels. Implementations must keep
 * the latest explicitly published metadata when a later snapshot still contains the coordinator's
 * original metadata, for example after URI artwork has been resolved to bytes.
 */
interface PlatformMediaIntegration {
    fun activate(commandHandler: MediaCommandHandler)

    fun updateMetadata(metadata: PlaybackMetadata?)

    fun updatePlaybackState(state: PlaybackSnapshot)

    fun deactivate()
}

object NoopPlatformMediaIntegration : PlatformMediaIntegration {
    override fun activate(commandHandler: MediaCommandHandler) = Unit

    override fun updateMetadata(metadata: PlaybackMetadata?) = Unit

    override fun updatePlaybackState(state: PlaybackSnapshot) = Unit

    override fun deactivate() = Unit
}
