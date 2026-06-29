package com.guyuuan.mpv_kmp.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guyuuan.mpv_kmp.MpvComposeView
import com.guyuuan.mpv_kmp.rememberMpvPlayer
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

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
        Column(modifier = Modifier.fillMaxSize()) {
            MpvComposeView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = playerState
            )
            
            Column(modifier = Modifier.padding(16.dp)) {
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
