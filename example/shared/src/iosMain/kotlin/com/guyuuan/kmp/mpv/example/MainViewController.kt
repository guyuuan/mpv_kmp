package com.guyuuan.kmp.mpv.example

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.guyuuan.kmp.mpv.loader.coil.CoilPlaybackArtworkLoaderFactory
import com.guyuuan.kmp.mpv.pip.configurePipPlayback
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    configurePipPlayback {
        artworkLoaderFactory = CoilPlaybackArtworkLoaderFactory(
            context = PlatformContext.INSTANCE,
        )
    }
    App()
}
