package com.guyuuan.mpv_kmp.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guyuuan.mpv_kmp.MpvComposeView
import com.guyuuan.mpv_kmp.rememberMpvPlayer
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        val playerState = rememberMpvPlayer()
        val videoUrl = "https://emby.guyuuan.com:23231/emby/Items/39635/Download?api_key=373c1a911e9449f1972dc4e431390745&mediaSourceId=mediasource_39635"
        fun playVideo(){
            println("start load video: $videoUrl")
            val load = playerState.load(videoUrl)
            println("load result: $load")
            val play = playerState.play()
            println("play result: $play")
        }
        val decoderInfo by playerState.decoderInfoFlow.collectAsStateWithLifecycle(initialValue = null)
        val progress by
            derivedStateOf{ (playerState.timePos / (playerState.duration.takeIf { it > 0 } ?: 1.0)).toFloat() }
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f/3f),
            ) {
                MpvComposeView(
                    modifier = Modifier.fillMaxSize(),
                    state = playerState
                )
                Slider(value = progress, onValueChange = {
                    playerState.seek(playerState.duration*it)
                }, modifier = Modifier.align(alignment = Alignment.BottomCenter).padding(horizontal = 24.dp).fillMaxWidth())

            }
            Column(modifier = Modifier.padding(16.dp).weight(1f).verticalScroll(state = rememberScrollState())) {
                Text("Decoder Info:")
                Text("Video: ${decoderInfo?.video}")
                Text("Audio: ${decoderInfo?.audio}")
                Text(text = "Status: ${playerState.state.name}")
                Text(text = "Time: ${playerState.timePos} / ${playerState.duration}")
                
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::playVideo) {
                        Text("Load & Play")
                    }
                    Button(onClick = { playerState.togglePause() }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(if (playerState.isPaused) "Resume" else "Pause")
                    }
                    Button(onClick = { playerState.stop() }, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Stop")
                    }
                }
            }
        }

        LaunchedEffect(Unit){
            delay(1000.milliseconds)
            playVideo()
        }
    }
}
