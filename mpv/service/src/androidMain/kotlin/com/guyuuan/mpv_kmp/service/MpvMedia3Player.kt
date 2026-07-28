package com.guyuuan.mpv_kmp.service

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Media3 [Player] adapter driven by platform integration state and media command callbacks. */
@UnstableApi
class MpvMedia3Player(
    private val commandHandler: MediaCommandHandler,
    looper: Looper = Looper.getMainLooper()
) : SimpleBasePlayer(looper) {
    private val coordinator = commandHandler as? PlaybackCoordinator
    private val playerDispatcher =
        Handler(looper).asCoroutineDispatcher("MpvMedia3Player").immediate
    private val scope = CoroutineScope(SupervisorJob() + playerDispatcher)
    private var latestSnapshot = coordinator?.snapshot?.value ?: PlaybackSnapshot()

    internal fun updateMetadata(metadata: PlaybackMetadata?) {
        scope.launch {
            if (latestSnapshot.metadata == metadata) return@launch
            latestSnapshot = latestSnapshot.copy(metadata = metadata)
            invalidateState()
        }
    }

    internal fun updatePlaybackState(snapshot: PlaybackSnapshot) {
        scope.launch {
            if (latestSnapshot == snapshot) return@launch
            latestSnapshot = snapshot
            invalidateState()
        }
    }

    internal fun releaseFromIntegration() {
        scope.launch {
            release()
        }
    }

    override fun getState(): State {
        val snapshot = latestSnapshot
        val builder = State.Builder()
            .setAvailableCommands(snapshot.toMedia3Commands())
            .setPlayWhenReady(
                snapshot.playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(snapshot.status.toMedia3PlaybackState())
            .setIsLoading(snapshot.status == PlaybackStatus.Loading)
            .setContentPositionMs(snapshot.positionMillis)
            .setPlaybackParameters(PlaybackParameters(snapshot.speed))
            .setRepeatMode(snapshot.repeatMode.toMedia3RepeatMode())
            .setShuffleModeEnabled(snapshot.shuffleEnabled)
            .setVolume((snapshot.volume / MAX_MPV_VOLUME).coerceIn(0f, 1f))

        if (snapshot.status == PlaybackStatus.Error) {
            builder.setPlayerError(
                PlaybackException(
                    "libmpv playback error",
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED
                )
            )
        }

        val queue = coordinator?.queueItems.orEmpty().ifEmpty {
            snapshot.metadata?.let(::listOf).orEmpty()
        }
        if (queue.isNotEmpty()) {
            val currentIndex = snapshot.queueIndex?.takeIf { it in queue.indices } ?: 0
            builder
                .setPlaylist(
                    queue.mapIndexed { index, metadata ->
                        val mediaItem = metadata.toMediaItem()
                        MediaItemData.Builder(metadata.mediaId)
                            .setMediaItem(mediaItem)
                            .setMediaMetadata(mediaItem.mediaMetadata)
                            .setDurationUs(
                                if (index == currentIndex) {
                                    snapshot.durationMillis.toDurationUs()
                                } else {
                                    C.TIME_UNSET
                                }
                            )
                            .setIsSeekable(MediaCommandType.SeekTo in snapshot.availableCommands)
                            .build()
                    }
                )
                .setCurrentMediaItemIndex(currentIndex)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        execute(if (playWhenReady) MediaCommand.Play else MediaCommand.Pause)

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> = execute(MediaCommand.Stop)

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> execute(MediaCommand.Next)
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> execute(MediaCommand.Previous)
        else -> execute(MediaCommand.SeekTo(positionMs.coerceAtLeast(0)))
    }

    override fun handleSetPlaybackParameters(
        playbackParameters: PlaybackParameters
    ): ListenableFuture<*> = execute(MediaCommand.SetSpeed(playbackParameters.speed))

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> =
        execute(MediaCommand.SetVolume(volume.coerceIn(0f, 1f) * MAX_MPV_VOLUME))

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> =
        execute(MediaCommand.SetRepeatMode(repeatMode.toPlaybackRepeatMode()))

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        execute(MediaCommand.SetShuffle(shuffleModeEnabled))

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        val coordinator = coordinator
            ?: return Futures.immediateFailedFuture<Void>(
                UnsupportedOperationException(
                    "Setting Media3 items requires a PlaybackCoordinator command handler"
                )
            )
        if (mediaItems.isEmpty()) return Futures.immediateVoidFuture()
        val selectedIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        if (selectedIndex !in mediaItems.indices) {
            return Futures.immediateFailedFuture<Void>(
                IllegalArgumentException("Media item index $selectedIndex is out of bounds")
            )
        }
        val metadata = runCatching { mediaItems.map(MediaItem::toPlaybackMetadata) }
            .getOrElse { error -> return Futures.immediateFailedFuture<Void>(error) }

        val loadResult = coordinator.setQueue(
            items = metadata,
            currentIndex = selectedIndex,
            playWhenReady = latestSnapshot.playWhenReady
        )
        if (loadResult < 0) return resultFuture(loadResult)

        if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) {
            return execute(MediaCommand.SeekTo(startPositionMs))
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private fun execute(command: MediaCommand): ListenableFuture<*> {
        val coordinator = coordinator
        if (coordinator != null) return resultFuture(coordinator.execute(command))

        return runCatching { commandHandler.handle(command) }.fold(
            onSuccess = { Futures.immediateVoidFuture() },
            onFailure = { Futures.immediateFailedFuture<Void>(it) }
        )
    }
}

@UnstableApi
private fun PlaybackSnapshot.toMedia3Commands(): Player.Commands {
    val commands = Player.Commands.Builder()
        .add(Player.COMMAND_RELEASE)
        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_GET_TIMELINE)
        .add(Player.COMMAND_GET_METADATA)
        .add(Player.COMMAND_GET_VOLUME)
        .add(Player.COMMAND_SET_MEDIA_ITEM)
        .add(Player.COMMAND_PREPARE)

    if (MediaCommandType.Play in availableCommands || MediaCommandType.Pause in availableCommands) {
        commands.add(Player.COMMAND_PLAY_PAUSE)
    }
    if (MediaCommandType.Stop in availableCommands) commands.add(Player.COMMAND_STOP)
    if (MediaCommandType.SeekTo in availableCommands) {
        commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    }
    if (MediaCommandType.SeekBy in availableCommands) {
        commands.add(Player.COMMAND_SEEK_BACK)
        commands.add(Player.COMMAND_SEEK_FORWARD)
    }
    if (MediaCommandType.Next in availableCommands) {
        commands.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        commands.add(Player.COMMAND_SEEK_TO_NEXT)
    }
    if (MediaCommandType.Previous in availableCommands) {
        commands.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        commands.add(Player.COMMAND_SEEK_TO_PREVIOUS)
    }
    if (MediaCommandType.SetSpeed in availableCommands) {
        commands.add(Player.COMMAND_SET_SPEED_AND_PITCH)
    }
    if (MediaCommandType.SetVolume in availableCommands) commands.add(Player.COMMAND_SET_VOLUME)
    if (MediaCommandType.SetRepeatMode in availableCommands) {
        commands.add(Player.COMMAND_SET_REPEAT_MODE)
    }
    if (MediaCommandType.SetShuffle in availableCommands) {
        commands.add(Player.COMMAND_SET_SHUFFLE_MODE)
    }
    return commands.build()
}

private fun PlaybackRepeatMode.toMedia3RepeatMode(): Int = when (this) {
    PlaybackRepeatMode.None -> Player.REPEAT_MODE_OFF
    PlaybackRepeatMode.One -> Player.REPEAT_MODE_ONE
    PlaybackRepeatMode.All -> Player.REPEAT_MODE_ALL
}

private fun Int.toPlaybackRepeatMode(): PlaybackRepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.One
    Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.All
    else -> PlaybackRepeatMode.None
}

private fun PlaybackStatus.toMedia3PlaybackState(): Int = when (this) {
    PlaybackStatus.Loading -> Player.STATE_BUFFERING
    PlaybackStatus.Playing,
    PlaybackStatus.Paused -> Player.STATE_READY
    PlaybackStatus.Ended -> Player.STATE_ENDED
    PlaybackStatus.Idle,
    PlaybackStatus.Stopped,
    PlaybackStatus.Error,
    PlaybackStatus.Disposed -> Player.STATE_IDLE
}

private fun PlaybackMetadata.toMediaItem(): MediaItem {
    val mediaMetadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(albumTitle)
        .also { builder ->
            when (val value = artwork) {
                is PlaybackArtwork.Uri -> builder.setArtworkUri(Uri.parse(value.value))
                is PlaybackArtwork.Bytes -> builder.setArtworkData(
                    value.toByteArray(),
                    MediaMetadata.PICTURE_TYPE_FRONT_COVER
                )
                null -> Unit
            }
        }
        .build()

    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(mediaMetadata)
        .build()
}

private fun MediaItem.toPlaybackMetadata(): PlaybackMetadata {
    val value = mediaMetadata
    return PlaybackMetadata(
        mediaId = mediaId.ifBlank { localConfiguration?.uri?.toString().orEmpty() },
        uri = localConfiguration?.uri?.toString()
            ?: error("MediaItem must provide a URI"),
        title = value.title?.toString()?.takeIf { it.isNotBlank() }
            ?: mediaId.ifBlank { "Untitled media" },
        artist = value.artist?.toString(),
        albumTitle = value.albumTitle?.toString(),
        artwork = value.artworkUri?.let { PlaybackArtwork.Uri(it.toString()) }
            ?: value.artworkData?.let { PlaybackArtwork.Bytes(it) },
        mediaType = PlaybackMediaType.Unknown
    )
}

private fun Long.toDurationUs(): Long = if (this > 0) this * 1000 else C.TIME_UNSET

private fun resultFuture(result: Int): ListenableFuture<*> =
    if (result >= 0) {
        Futures.immediateVoidFuture()
    } else {
        Futures.immediateFailedFuture<Void>(
            PlaybackException(
                "libmpv command failed with code $result",
                null,
                PlaybackException.ERROR_CODE_UNSPECIFIED
            )
        )
    }

private const val MAX_MPV_VOLUME = 100f
