package com.guyuuan.mpv_kmp.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guyuuan.mpv_kmp.MpvComposeView
import com.guyuuan.mpv_kmp.rememberMpvPlayerState

@Composable
fun App() {
    MaterialTheme {
        val playerState = rememberMpvPlayerState()
        val videoUrl = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"

        Column(modifier = Modifier.fillMaxSize()) {
            MpvComposeView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = playerState
            )
            
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Status: ${if (playerState.isLoading) "Loading..." else if (playerState.isPaused) "Paused" else "Playing"}")
                Text(text = "Time: ${playerState.timePos} / ${playerState.duration}")
                
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { playerState.load(videoUrl); playerState.play() }) {
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
    }
}
