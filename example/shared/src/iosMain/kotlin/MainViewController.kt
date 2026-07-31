package com.guyuuan.mpv_kmp.example

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.guyuuan.mpv_kmp.loader.coil.CoilPlaybackArtworkLoaderFactory
import com.guyuuan.mpv_kmp.pip.configurePipPlayback
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    configurePipPlayback {
        artworkLoaderFactory = CoilPlaybackArtworkLoaderFactory(
            context = PlatformContext.INSTANCE,
        )
    }
    App()
}
