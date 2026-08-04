package com.guyuuan.kmp.mpv.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.guyuuan.kmp.mpv.loader.coil.CoilPlaybackArtworkLoaderFactory
import com.guyuuan.kmp.mpv.pip.configurePipPlayback

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configurePipPlayback {
            artworkLoaderFactory = CoilPlaybackArtworkLoaderFactory(context = applicationContext)
        }
        setContent {
            App()
        }
    }
}
