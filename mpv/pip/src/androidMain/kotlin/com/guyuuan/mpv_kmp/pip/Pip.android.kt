package com.guyuuan.mpv_kmp.pip

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.guyuuan.mpv_kmp.service.AndroidPlaybackCoordinatorOwner
import com.guyuuan.mpv_kmp.service.AndroidPlaybackStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(UnstableApi::class)
@Composable
actual fun rememberPipMpvPlayer(): PipMpvPlayer {
    val context = LocalContext.current.applicationContext
    val activity = LocalContext.current.findComponentActivity()
    val coordinator = remember(context) {
        AndroidPlaybackCoordinatorOwner.coordinator(context)
    }
    val mediaSessionConnection = remember(context, activity) {
        AndroidMediaSessionConnection(context)
    }
    val pipController = remember(activity) {
        AndroidPictureInPictureController(activity)
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
            onSnapshot = videoOutput::updatePlaybackSnapshot
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
            }
        )
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
    AndroidPlaybackCoordinatorOwner.configure { context, mediaIntegration ->
        configuration.createCoordinator(
            mediaIntegration = mediaIntegration,
            defaultStateStore = AndroidPlaybackStateStore(context)
        )
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
