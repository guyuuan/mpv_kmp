package com.guyuuan.kmp.mpv.pip

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.pip.PictureInPictureDelegate
import androidx.core.pip.VideoPlaybackPictureInPicture
import com.guyuuan.kmp.mpv.MpvSurfaceView
import com.guyuuan.kmp.mpv.service.MediaCommand
import com.guyuuan.kmp.mpv.service.PlaybackSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Replaces the default Android PiP playback actions without requiring callers to cast the public
 * [PictureInPictureController] handle to its Android implementation.
 *
 * @return `true` when this controller supports Android [RemoteAction]s.
 */
@RequiresApi(Build.VERSION_CODES.O)
fun PictureInPictureController.setRemoteActions(actions: List<RemoteAction>): Boolean {
    val controller = this as? AndroidPictureInPictureController ?: return false
    controller.setRemoteActions(actions)
    return true
}

/**
 * Restores the Android default previous, play/pause, and next actions.
 *
 * @return `true` when this controller supports Android [RemoteAction]s.
 */
fun PictureInPictureController.useDefaultRemoteActions(): Boolean {
    val controller = this as? AndroidPictureInPictureController ?: return false
    controller.useDefaultRemoteActions()
    return true
}

class AndroidPictureInPictureController internal constructor(
    private val activity: ComponentActivity?,
    initialPlaybackSnapshot: PlaybackSnapshot = PlaybackSnapshot(),
    private val onPlaybackCommand: (MediaCommand) -> Unit = {}
) : PictureInPictureController {
    private val mutableAvailability = MutableStateFlow(resolveAvailability(activity))
    override val availability: StateFlow<PictureInPictureAvailability> =
        mutableAvailability.asStateFlow()

    private val mutableState = MutableStateFlow(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            activity?.isInPictureInPictureMode == true
        ) {
            PictureInPictureState.Active
        } else {
            PictureInPictureState.Inactive
        }
    )
    override val state: StateFlow<PictureInPictureState> = mutableState.asStateFlow()

    private val mainExecutor = activity?.let(ContextCompat::getMainExecutor)
    private val delegate = activity
        ?.takeIf { availability.value == PictureInPictureAvailability.Available }
        ?.let {
            VideoPlaybackPictureInPicture(
                it,
                checkNotNull(mainExecutor)
            )
        }
    private var eligible = false
    private var closed = false
    private var playerView: MpvSurfaceView? = null
    private var pendingEnterView: MpvSurfaceView? = null
    private var pendingEnterRunnable: Runnable? = null
    private var playbackSnapshot = initialPlaybackSnapshot
    private var usesDefaultRemoteActions = true
    private var remoteActionReceiverRegistered = false

    private val remoteActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = when (intent?.action) {
                ACTION_PLAY -> MediaCommand.Play
                ACTION_PAUSE -> MediaCommand.Pause
                ACTION_PREVIOUS -> MediaCommand.Previous
                ACTION_NEXT -> MediaCommand.Next
                else -> return
            }
            onPlaybackCommand(command)
        }
    }

    private val eventListener =
        object : PictureInPictureDelegate.OnPictureInPictureEventListener {
            override fun onPictureInPictureEvent(
                event: PictureInPictureDelegate.Event,
                config: android.content.res.Configuration?
            ) {
                when (event) {
                    PictureInPictureDelegate.Event.ENTER_ANIMATION_START ->
                        mutableState.value = PictureInPictureState.Entering

                    PictureInPictureDelegate.Event.ENTERED -> {
                        cancelPendingEnter()
                        mutableState.value = PictureInPictureState.Active
                    }

                    PictureInPictureDelegate.Event.ENTER_ANIMATION_END -> {
                        cancelPendingEnter()
                        mutableState.value =
                            if (activity?.isInPictureInPictureMode == true) {
                                PictureInPictureState.Active
                            } else {
                                PictureInPictureState.Inactive
                            }
                    }

                    PictureInPictureDelegate.Event.EXITED -> {
                        cancelPendingEnter()
                        mutableState.value = PictureInPictureState.Inactive
                    }

                    else -> Unit
                }
            }
        }

    init {
        mainExecutor?.let { executor ->
            delegate?.addOnPictureInPictureEventListener(executor, eventListener)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            ContextCompat.registerReceiver(
                activity,
                remoteActionReceiver,
                IntentFilter().apply {
                    addAction(ACTION_PLAY)
                    addAction(ACTION_PAUSE)
                    addAction(ACTION_PREVIOUS)
                    addAction(ACTION_NEXT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            remoteActionReceiverRegistered = true
            applyDefaultRemoteActions()
        }
    }

    /**
     * Replaces the default playback actions with [actions]. The actions are applied immediately to
     * the current PiP session and reused by the next entry.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun setRemoteActions(actions: List<RemoteAction>) {
        if (closed) return
        usesDefaultRemoteActions = false
        applyRemoteActions(actions)
    }

    /** Restores the default previous, play/pause, and next actions. */
    fun useDefaultRemoteActions() {
        if (closed) return
        usesDefaultRemoteActions = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applyDefaultRemoteActions()
        }
    }

    internal fun updatePlaybackSnapshot(snapshot: PlaybackSnapshot) {
        if (closed || playbackSnapshot == snapshot) return
        val wasPlaying = playbackSnapshot.isPlaying
        playbackSnapshot = snapshot
        if (usesDefaultRemoteActions && wasPlaying != snapshot.isPlaying &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            applyDefaultRemoteActions()
        }
    }

    internal fun trackPlayerView(view: MpvSurfaceView?) {
        if (closed) return
        if (view == null && playerView != null) {
            cancelPendingEnter(resetState = true)
        }
        playerView = view
        delegate?.setPlayerView(view)?.commit()
    }

    override fun setEligible(eligible: Boolean) {
        if (closed) return
        this.eligible = eligible
        if (eligible) {
            playerView?.let { delegate?.setPlayerView(it) }
        } else {
            cancelPendingEnter(resetState = true)
        }
        delegate?.setEnabled(eligible)?.commit()
    }

    override fun setAspectRatio(width: Int, height: Int) {
        if (closed || width <= 0 || height <= 0) return
        runCatching {
            delegate?.setAspectRatio(Rational(width, height))?.commit()
        }
    }

    override fun requestStart(): Boolean {
        val host = activity ?: return false
        val view = playerView ?: return false
        if (closed || !eligible ||
            availability.value != PictureInPictureAvailability.Available ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            mutableState.value != PictureInPictureState.Inactive ||
            !view.isAttachedToWindow
        ) {
            return false
        }

        mutableState.value = PictureInPictureState.Entering
        scheduleEnterAfterPlayerViewUpdate(host, view)
        return true
    }

    override fun requestStop(): Boolean {
        // Android doesn't provide a direct "exit PiP" API. The host Activity returns to full-screen
        // through normal task navigation or finishes when playback policy asks it to close.
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPendingEnter()
        if (remoteActionReceiverRegistered) {
            activity?.unregisterReceiver(remoteActionReceiver)
            remoteActionReceiverRegistered = false
        }
        delegate?.removeOnPictureInPictureEventListener(eventListener)
        delegate?.setPlayerView(null)?.setEnabled(false)?.commit()
        delegate?.close()
        playerView = null
        mutableState.value = PictureInPictureState.Inactive
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun applyDefaultRemoteActions() {
        val host = activity ?: return
        val playOrPause = if (playbackSnapshot.isPlaying) {
            createRemoteAction(
                host = host,
                iconResource = android.R.drawable.ic_media_pause,
                title = "Pause",
                action = ACTION_PAUSE,
                requestCode = REQUEST_CODE_PAUSE
            )
        } else {
            createRemoteAction(
                host = host,
                iconResource = android.R.drawable.ic_media_play,
                title = "Play",
                action = ACTION_PLAY,
                requestCode = REQUEST_CODE_PLAY
            )
        }
        applyRemoteActions(
            listOf(
                createRemoteAction(
                    host = host,
                    iconResource = android.R.drawable.ic_media_previous,
                    title = "Previous",
                    action = ACTION_PREVIOUS,
                    requestCode = REQUEST_CODE_PREVIOUS
                ),
                playOrPause,
                createRemoteAction(
                    host = host,
                    iconResource = android.R.drawable.ic_media_next,
                    title = "Next",
                    action = ACTION_NEXT,
                    requestCode = REQUEST_CODE_NEXT
                )
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun applyRemoteActions(actions: List<RemoteAction>) {
        delegate?.setActions(actions)?.commit()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createRemoteAction(
        host: ComponentActivity,
        iconResource: Int,
        title: String,
        action: String,
        requestCode: Int
    ): RemoteAction {
        val intent = Intent(action).setPackage(host.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            host,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            Icon.createWithResource(host, iconResource),
            title,
            title,
            pendingIntent
        )
    }

    private fun scheduleEnterAfterPlayerViewUpdate(
        host: ComponentActivity,
        view: MpvSurfaceView
    ) {
        val refreshBounds = Runnable {
            if (!canEnter(host, view)) {
                finishRejectedEnter(host)
            } else {
                delegate?.setPlayerView(view)?.commit()
                val enter = Runnable {
                    pendingEnterRunnable = null
                    pendingEnterView = null
                    if (!canEnter(host, view)) {
                        finishRejectedEnter(host)
                    } else {
                        val entered = runCatching {
                            @Suppress("DEPRECATION")
                            host.enterPictureInPictureMode()
                            true
                        }.getOrDefault(false)
                        if (!entered) {
                            finishRejectedEnter(host)
                        }
                    }
                }
                pendingEnterRunnable = enter
                view.postOnAnimation(enter)
            }
        }
        pendingEnterView = view
        pendingEnterRunnable = refreshBounds
        view.postOnAnimation(refreshBounds)
    }

    private fun canEnter(host: ComponentActivity, view: MpvSurfaceView): Boolean =
        !closed &&
            eligible &&
            playerView === view &&
            view.isAttachedToWindow &&
            view.isLaidOut &&
            view.width > 0 &&
            view.height > 0 &&
            !host.isFinishing &&
            !host.isDestroyed

    private fun finishRejectedEnter(host: ComponentActivity) {
        pendingEnterRunnable = null
        pendingEnterView = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            !host.isInPictureInPictureMode
        ) {
            mutableState.value = PictureInPictureState.Inactive
        }
    }

    private fun cancelPendingEnter(resetState: Boolean = false) {
        val view = pendingEnterView
        val runnable = pendingEnterRunnable
        if (view != null && runnable != null) {
            view.removeCallbacks(runnable)
        }
        pendingEnterView = null
        pendingEnterRunnable = null
        if (resetState && mutableState.value == PictureInPictureState.Entering) {
            mutableState.value = PictureInPictureState.Inactive
        }
    }

    private companion object {
        const val ACTION_PLAY = "com.guyuuan.kmp.mpv.pip.action.PLAY"
        const val ACTION_PAUSE = "com.guyuuan.kmp.mpv.pip.action.PAUSE"
        const val ACTION_PREVIOUS = "com.guyuuan.kmp.mpv.pip.action.PREVIOUS"
        const val ACTION_NEXT = "com.guyuuan.kmp.mpv.pip.action.NEXT"

        const val REQUEST_CODE_PLAY = 1
        const val REQUEST_CODE_PAUSE = 2
        const val REQUEST_CODE_PREVIOUS = 3
        const val REQUEST_CODE_NEXT = 4

        fun resolveAvailability(activity: ComponentActivity?): PictureInPictureAvailability =
            when {
                activity == null -> PictureInPictureAvailability.MissingHostCapability
                Build.VERSION.SDK_INT < Build.VERSION_CODES.N ->
                    PictureInPictureAvailability.MissingSystemFeature
                !activity.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_PICTURE_IN_PICTURE
                ) -> PictureInPictureAvailability.MissingSystemFeature
                else -> PictureInPictureAvailability.Available
            }
    }
}
