package com.guyuuan.mpv_kmp.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.guyuuan.mpv_kmp.loader.coil.CoilPlaybackArtworkLoaderFactory
import com.guyuuan.mpv_kmp.pip.configurePipPlayback

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
