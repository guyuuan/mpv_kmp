package com.guyuuan.mpv_kmp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Handles Android audio focus and pauses playback when an audio route becomes noisy. */
class AndroidPlaybackInterruptionManager(
    context: Context,
    private val coordinator: PlaybackCoordinator,
    private val scope: CoroutineScope
) {
    private val applicationContext = context.applicationContext
    private val audioManager =
        applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var started = false
    private var resumeAfterTransientLoss = false
    private var focusHeld = false
    private var observationJob: Job? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (coordinator.isClosed) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusHeld = true
                if (resumeAfterTransientLoss) {
                    resumeAfterTransientLoss = false
                    coordinator.handle(MediaCommand.Play)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeAfterTransientLoss = coordinator.snapshot.value.status == PlaybackStatus.Playing
                coordinator.handle(MediaCommand.Pause)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld = false
                resumeAfterTransientLoss = false
                coordinator.handle(MediaCommand.Pause)
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!coordinator.isClosed &&
                intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                coordinator.snapshot.value.status == PlaybackStatus.Playing
            ) {
                coordinator.handle(MediaCommand.Pause)
            }
        }
    }

    private val focusRequest: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
    } else {
        null
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(
            applicationContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        observationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.snapshot
                .map { it.status }
                .distinctUntilChanged()
                .collect { status ->
                    when (status) {
                        PlaybackStatus.Playing -> requestFocusOrPause()
                        PlaybackStatus.Stopped,
                        PlaybackStatus.Ended,
                        PlaybackStatus.Error,
                        PlaybackStatus.Disposed -> abandonFocus()
                        else -> Unit
                    }
                }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        observationJob?.cancel()
        observationJob = null
        runCatching { applicationContext.unregisterReceiver(noisyReceiver) }
        abandonFocus()
    }

    @Suppress("DEPRECATION")
    private fun requestFocusOrPause() {
        if (focusHeld) return
        val result = if (Build.VERSION.SDK_INT >= 26) {
            audioManager.requestAudioFocus(requireNotNull(focusRequest))
        } else {
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) coordinator.handle(MediaCommand.Pause)
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (!focusHeld) return
        if (Build.VERSION.SDK_INT >= 26) {
            audioManager.abandonAudioFocusRequest(requireNotNull(focusRequest))
        } else {
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        focusHeld = false
        resumeAfterTransientLoss = false
    }
}
