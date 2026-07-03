package com.guyuuan.mpv_kmp.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.guyuuan.mpv_kmp.MpvComposeView
import com.guyuuan.mpv_kmp.rememberMpvPlayer
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        val playerState = rememberMpvPlayer()
        val videoUrl =
            "https://emby.guyuuan.com:23231/emby/Items/39635/Download?api_key=373c1a911e9449f1972dc4e431390745&mediaSourceId=mediasource_39635"

        fun playVideo() {
            println("start load video: $videoUrl")
            val load = playerState.load(videoUrl)
            println("load result: $load")
            val play = playerState.play()
            println("play result: $play")
        }

        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            MpvComposeView(
                modifier = Modifier.fillMaxSize(), state = playerState
            )
            BottomBar(
                modifier = Modifier.align(alignment = Alignment.BottomCenter).windowInsetsPadding(insets = WindowInsets.safeContent),
                playerState = playerState
            )
        }

        LaunchedEffect(Unit) {
            delay(2000.milliseconds)
            playVideo()
        }
    }
}
