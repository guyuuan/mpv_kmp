package com.guyuuan.mpv_kmp.pip

import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.pip.VideoPlaybackPictureInPicture
import androidx.core.util.Consumer
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

    private val delegate = activity
        ?.takeIf { availability.value == PictureInPictureAvailability.Available }
        ?.let {
            VideoPlaybackPictureInPicture(
                it,
                ContextCompat.getMainExecutor(it)
            )
        }
    private var eligible = false
    private var closed = false

    private val modeChangedListener = Consumer<PictureInPictureModeChangedInfo> { info ->
        mutableState.value = if (info.isInPictureInPictureMode) {
            PictureInPictureState.Active
        } else {
            PictureInPictureState.Inactive
        }
    }

    init {
        activity?.addOnPictureInPictureModeChangedListener(modeChangedListener)
    }

    internal fun trackPlayerView(view: MpvSurfaceView?) {
        if (closed) return
        delegate?.setPlayerView(view)?.commit()
    }

    override fun setEligible(eligible: Boolean) {
        if (closed) return
        this.eligible = eligible
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
        if (closed || !eligible ||
            availability.value != PictureInPictureAvailability.Available ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        ) {
            return false
        }
        return runCatching {
            host.enterPictureInPictureMode()
            true
        }.getOrDefault(false)
    }

    override fun requestStop(): Boolean {
        // Android doesn't provide a direct "exit PiP" API. The host Activity returns to full-screen
        // through normal task navigation or finishes when playback policy asks it to close.
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        activity?.removeOnPictureInPictureModeChangedListener(modeChangedListener)
        delegate?.close()
        mutableState.value = PictureInPictureState.Inactive
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
