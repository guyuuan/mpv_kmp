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

@OptIn(UnstableApi::class)
@Composable
actual fun rememberPipMpvPlayer(): PipMpvPlayer {
    val context = LocalContext.current.applicationContext
    val activity = LocalContext.current.findComponentActivity()
    val pipController = remember(activity) {
        AndroidPictureInPictureController(activity)
    }
    val mediaPlayer = remember(context, pipController) {
        AndroidMediaSessionMpvPlayer(
            context = context,
            pictureInPictureController = pipController
        )
    }
    val player = remember(mediaPlayer, pipController) {
        PipMpvPlayer(
            delegate = mediaPlayer,
            pictureInPicture = pipController,
            release = {
                mediaPlayer.close()
                pipController.close()
            }
        )
    }

    DisposableEffect(player) {
        onDispose(player::close)
    }
    return player
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
