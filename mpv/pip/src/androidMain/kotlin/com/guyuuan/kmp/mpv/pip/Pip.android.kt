package com.guyuuan.kmp.mpv.pip

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.guyuuan.kmp.mpv.config.MpvConfig
import com.guyuuan.kmp.mpv.service.AndroidPlaybackCoordinatorOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(UnstableApi::class)
@Composable
actual fun rememberPipMpvPlayer(config: MpvConfig): PipMpvPlayer {
    val context = LocalContext.current.applicationContext
    val activity = LocalContext.current.findComponentActivity()
    val coordinator = remember(context, config) {
        AndroidPlaybackCoordinatorOwner.coordinator(context, config)
    }
    val mediaSessionConnection = remember(context, activity) {
        AndroidMediaSessionConnection(context)
    }
    val pipController = remember(activity, coordinator) {
        AndroidPictureInPictureController(
            activity = activity,
            initialPlaybackSnapshot = coordinator.snapshot.value,
            onPlaybackCommand = coordinator::execute
        )
    }
    val videoOutput = remember(coordinator, pipController) {
        AndroidPlaybackCoordinatorVideoOutput(
            coordinator = coordinator,
            pictureInPictureController = pipController
        )
    }
    val mediaPlayer = remember(coordinator, videoOutput) {
        PlaybackCoordinatorMpvPlayer(
            coordinator = coordinator,
            videoOutput = videoOutput,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            onSnapshot = { snapshot ->
                videoOutput.updatePlaybackSnapshot(snapshot)
                pipController.updatePlaybackSnapshot(snapshot)
            }
        )
    }
    val player = remember(mediaPlayer, videoOutput, mediaSessionConnection, pipController) {
        PipMpvPlayer(
            delegate = mediaPlayer,
            pictureInPicture = pipController,
            videoOutput = videoOutput,
            release = {
                mediaPlayer.close()
                videoOutput.close()
                mediaSessionConnection.close()
                pipController.close()
                AndroidPlaybackCoordinatorOwner.close(context)
            }
        )
    }
    LifecycleStartEffect(
        key1 = pipController,
        lifecycleOwner = activity ?: LocalLifecycleOwner.current
    ) {
        onStopOrDispose {
            val pip = pipController.state.value == PictureInPictureState.Active
            if (pip) {
                player.pause()
            }
        }
    }
    DisposableEffect(player) {
        onDispose(player::close)
    }
    return player
}

@OptIn(UnstableApi::class)
internal actual fun installPlatformPipPlaybackConfiguration(
    configuration: PipPlaybackConfiguration
) {
    AndroidPlaybackCoordinatorOwner.configure { _, mediaIntegration ->
        configuration.createCoordinator(
            mediaIntegration = mediaIntegration
        )
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
