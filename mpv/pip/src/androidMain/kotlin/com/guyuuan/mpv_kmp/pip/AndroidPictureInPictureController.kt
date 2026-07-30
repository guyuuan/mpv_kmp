package com.guyuuan.mpv_kmp.pip

import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.pip.PictureInPictureDelegate
import androidx.core.pip.VideoPlaybackPictureInPicture
import com.guyuuan.mpv_kmp.MpvSurfaceView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidPictureInPictureController internal constructor(
    private val activity: ComponentActivity?
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
        delegate?.removeOnPictureInPictureEventListener(eventListener)
        delegate?.setPlayerView(null)?.setEnabled(false)?.commit()
        delegate?.close()
        playerView = null
        mutableState.value = PictureInPictureState.Inactive
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
