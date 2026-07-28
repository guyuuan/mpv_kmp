package com.guyuuan.mpv_kmp.pip

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.guyuuan.mpv_kmp.AndroidMpvVideoOutput
import com.guyuuan.mpv_kmp.MpvPlayer
import com.guyuuan.mpv_kmp.MpvPlayerCapability
import com.guyuuan.mpv_kmp.MpvPlayerSnapshot
import com.guyuuan.mpv_kmp.MpvPlayerState
import com.guyuuan.mpv_kmp.MpvSurfaceView
import com.guyuuan.mpv_kmp.MpvVideoOutput
import com.guyuuan.mpv_kmp.data.MpvDecoderInfo
import com.guyuuan.mpv_kmp.service.MpvMediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * UI-side player facade. All commands and video surfaces cross Media3; it never creates or exposes
 * an Mpv instance. The MediaSessionService's PlaybackCoordinator remains the sole libmpv owner.
 */
@UnstableApi
class AndroidMediaSessionMpvPlayer internal constructor(
    context: Context,
    private val pictureInPictureController: AndroidPictureInPictureController,
    serviceComponent: ComponentName = ComponentName(context, MpvMediaSessionService::class.java)
) : MpvPlayer {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSnapshot = MutableStateFlow(MpvPlayerSnapshot())
    override val snapshot: StateFlow<MpvPlayerSnapshot> = mutableSnapshot.asStateFlow()
    override val capabilities: Set<MpvPlayerCapability> = emptySet()
    override val decoderInfoFlow: Flow<MpvDecoderInfo> = emptyFlow()

    private val remoteVideoOutput = MediaControllerVideoOutput(pictureInPictureController)
    override val videoOutput: MpvVideoOutput = remoteVideoOutput

    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        applicationContext,
        SessionToken(applicationContext, serviceComponent)
    ).buildAsync()
    private val commandLock = Any()
    private val pendingCommands = ArrayDeque<PendingCommand>()
    private var controller: MediaController? = null
    private var lastPictureInPictureVideoSize: Pair<Int, Int>? = null
    private var closed = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }
    }

    init {
        controllerFuture.addListener(
            {
                val connected = runCatching { controllerFuture.get() }.getOrElse {
                    mutableSnapshot.value =
                        mutableSnapshot.value.copy(state = MpvPlayerState.Error)
                    return@addListener
                }
                val commands = synchronized(commandLock) {
                    if (closed) {
                        null
                    } else {
                        controller = connected
                        pendingCommands.toList().also {
                            pendingCommands.clear()
                        }
                    }
                }
                if (commands == null) {
                    connected.release()
                    return@addListener
                }
                connected.addListener(playerListener)
                remoteVideoOutput.connect(connected)
                publish(connected)
                commands.forEach { command ->
                    executeConnected(connected, command.command, command.block)
                }
                scope.launch {
                    while (isActive && !closed) {
                        publish(connected)
                        delay(POSITION_POLL_INTERVAL_MILLIS)
                    }
                }
            },
            ContextCompat.getMainExecutor(applicationContext)
        )
    }

    override fun load(uri: String): Int = execute(Player.COMMAND_SET_MEDIA_ITEM) { player ->
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    override fun play(): Int = execute(Player.COMMAND_PLAY_PAUSE, Player::play)

    override fun pause(): Int = execute(Player.COMMAND_PLAY_PAUSE, Player::pause)

    override fun stop(): Int = execute(Player.COMMAND_STOP, Player::stop)

    override fun seek(positionSeconds: Double): Int =
        execute(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) { player ->
            player.seekTo((positionSeconds.coerceAtLeast(0.0) * MILLIS_PER_SECOND).toLong())
        }

    override fun setVolume(volume: Double): Int = execute(Player.COMMAND_SET_VOLUME) { player ->
        player.volume = (volume / MAX_MPV_VOLUME).toFloat().coerceIn(0f, 1f)
    }

    override fun setSpeed(speed: Float): Int =
        execute(Player.COMMAND_SET_SPEED_AND_PITCH) { player ->
            player.setPlaybackSpeed(speed)
        }

    fun close() {
        if (closed) return
        closed = true
        val connected = synchronized(commandLock) {
            pendingCommands.clear()
            controller.also { controller = null }
        }
        remoteVideoOutput.disconnect()
        connected?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
        scope.cancel()
        mutableSnapshot.value =
            mutableSnapshot.value.copy(state = MpvPlayerState.Disposed)
    }

    private fun execute(command: Int, block: (MediaController) -> Unit): Int {
        val player = synchronized(commandLock) {
            if (closed) return UNSUPPORTED_COMMAND
            controller ?: run {
                pendingCommands.addLast(PendingCommand(command, block))
                return 0
            }
        }
        return executeConnected(player, command, block)
    }

    private fun executeConnected(
        player: MediaController,
        command: Int,
        block: (MediaController) -> Unit
    ): Int {
        if (!player.isCommandAvailable(command)) return UNSUPPORTED_COMMAND
        return runCatching {
            block(player)
            publish(player)
            0
        }.getOrElse {
            mutableSnapshot.value = mutableSnapshot.value.copy(state = MpvPlayerState.Error)
            UNSUPPORTED_COMMAND
        }
    }

    private fun publish(player: Player) {
        if (closed) return
        val videoSize = player.videoSize
        if (videoSize.width > 0 && videoSize.height > 0) {
            val size = videoSize.width to videoSize.height
            if (size != lastPictureInPictureVideoSize) {
                lastPictureInPictureVideoSize = size
                pictureInPictureController.setAspectRatio(
                    width = videoSize.width,
                    height = videoSize.height
                )
            }
        }
        val state = when {
            player.playerError != null -> MpvPlayerState.Error
            player.playbackState == Player.STATE_BUFFERING -> MpvPlayerState.Loading
            player.playbackState == Player.STATE_ENDED -> MpvPlayerState.Ended
            player.playbackState == Player.STATE_READY && player.playWhenReady ->
                MpvPlayerState.Playing
            player.playbackState == Player.STATE_READY -> MpvPlayerState.Paused
            else -> MpvPlayerState.Idle
        }
        val duration = player.duration.takeUnless { it == C.TIME_UNSET } ?: 0L
        mutableSnapshot.value = MpvPlayerSnapshot(
            state = state,
            positionSeconds = player.currentPosition.coerceAtLeast(0L) / MILLIS_PER_SECOND,
            durationSeconds = duration.coerceAtLeast(0L) / MILLIS_PER_SECOND,
            volume = (player.volume * MAX_MPV_VOLUME).toFloat(),
            speed = player.playbackParameters.speed
        )
    }

    private class MediaControllerVideoOutput(
        private val pictureInPictureController: AndroidPictureInPictureController
    ) : AndroidMpvVideoOutput {
        private var controller: MediaController? = null
        private var playerView: MpvSurfaceView? = null

        fun connect(controller: MediaController) {
            this.controller = controller
            playerView?.let(controller::setVideoSurfaceView)
        }

        fun disconnect() {
            val view = playerView
            if (view != null) {
                controller?.clearVideoSurfaceView(view)
            }
            pictureInPictureController.trackPlayerView(null)
            controller = null
            playerView = null
        }

        override fun onPlayerViewAttached(view: MpvSurfaceView) {
            val previous = playerView
            if (previous != null && previous !== view) {
                controller?.clearVideoSurfaceView(previous)
            }
            playerView = view
            controller?.setVideoSurfaceView(view)
            pictureInPictureController.trackPlayerView(view)
        }

        override fun onPlayerViewDetached(view: MpvSurfaceView) {
            if (playerView !== view) return
            controller?.clearVideoSurfaceView(view)
            playerView = null
            pictureInPictureController.trackPlayerView(null)
        }

        override fun detach() {
            playerView?.let(::onPlayerViewDetached)
        }
    }

    private data class PendingCommand(
        val command: Int,
        val block: (MediaController) -> Unit
    )

    private companion object {
        const val UNSUPPORTED_COMMAND = -1
        const val MAX_MPV_VOLUME = 100.0
        const val MILLIS_PER_SECOND = 1000.0
        const val POSITION_POLL_INTERVAL_MILLIS = 250L
    }
}
