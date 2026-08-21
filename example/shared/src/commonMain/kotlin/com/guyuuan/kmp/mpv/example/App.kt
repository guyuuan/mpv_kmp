package com.guyuuan.kmp.mpv.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guyuuan.kmp.mpv.MpvComposeView
import com.guyuuan.kmp.mpv.pip.PictureInPictureState
import com.guyuuan.kmp.mpv.pip.rememberPipMpvPlayer
import com.guyuuan.kmp.mpv.service.PlaybackArtwork
import com.guyuuan.kmp.mpv.service.PlaybackMediaType
import com.guyuuan.kmp.mpv.service.PlaybackMetadata
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun App(overlay: @Composable BoxScope.() -> Unit = {}) {
    MaterialTheme {
        val player = rememberPipMpvPlayer()
        val playerSnapshot by player.snapshot.collectAsState()
        val pictureInPictureState by player.pictureInPicture.state.collectAsState()
        val videoUrl =
            "https://emby.guyuuan.com:23231/emby/Items/39635/Download?api_key=373c1a911e9449f1972dc4e431390745&mediaSourceId=mediasource_39635"
//        val videoUrl =
//            "https://emby.guyuuan.com:23231/emby/Items/38275/Download?api_key=8f8fafb4ddeb4a978385d1edc5b723ea&mediaSourceId=mediasource_38275"

        fun playVideo() {
            Logger.i(tag = "Example") { "start load video" }
//            player.load(PlaybackMetadata(
//                mediaId = videoUrl,
//                uri = videoUrl,
//                title = "我的阿勒泰",
//                artist = "artist",
//                albumTitle = "album",
//                artwork = PlaybackArtwork.Uri("https://emby.guyuuan.com:23231/emby/Items/39632/Images/Primary?maxHeight=940&maxWidth=626&tag=051df6d68720835e1d4b8599e812f200&keepAnimation=true&quality=90"),
//                mediaType = PlaybackMediaType.Video
//            ))
            player.addToPlayList(listOf(PlaybackMetadata(
                mediaId = videoUrl,
                uri = videoUrl,
                title = "我的阿勒泰",
                artist = "S01E05",
                albumTitle = "album",
                artwork = PlaybackArtwork.Uri("https://emby.guyuuan.com:23231/emby/Items/39632/Images/Primary?maxHeight=940&maxWidth=626&tag=051df6d68720835e1d4b8599e812f200&keepAnimation=true&quality=90"),
                mediaType = PlaybackMediaType.Video
            ),
                PlaybackMetadata(
                    mediaId = "https://emby.guyuuan.com:23231/emby/Items/38275/Download?api_key=8f8fafb4ddeb4a978385d1edc5b723ea&mediaSourceId=mediasource_38275",
                    uri = "https://emby.guyuuan.com:23231/emby/Items/38275/Download?api_key=8f8fafb4ddeb4a978385d1edc5b723ea&mediaSourceId=mediasource_38275",
                    title = "得闲谨制",
                    artist = "artist",
                    albumTitle = "album",
                    artwork = PlaybackArtwork.Uri("https://emby.guyuuan.com:23231/emby/Items/39632/Images/Primary?maxHeight=940&maxWidth=626&tag=051df6d68720835e1d4b8599e812f200&keepAnimation=true&quality=90"),
                    mediaType = PlaybackMediaType.Video
                ),
                PlaybackMetadata(
                    mediaId = "https://emby.guyuuan.com:23231/emby/Items/40777/Download?api_key=373c1a911e9449f1972dc4e431390745&mediaSourceId=mediasource_40777",
                    uri = "https://emby.guyuuan.com:23231/emby/Items/40777/Download?api_key=373c1a911e9449f1972dc4e431390745&mediaSourceId=mediasource_40777",
                    title = "蜘蛛侠",
                    artist = "artist",
                    albumTitle = "album",
                    artwork = PlaybackArtwork.Uri("https://emby.guyuuan.com:23231/emby/Items/39632/Images/Primary?maxHeight=940&maxWidth=626&tag=051df6d68720835e1d4b8599e812f200&keepAnimation=true&quality=90"),
                    mediaType = PlaybackMediaType.Video
                )),)
        }

        MpvComposeView(
            modifier = Modifier.fillMaxSize(), player = player, overlay = {
                if (pictureInPictureState == PictureInPictureState.Inactive) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        overlay()
                        PlaySpeed(
                            modifier = Modifier.align(alignment = Alignment.CenterEnd)
                                .padding(end = 16.dp),
                            value = playerSnapshot.speed
                        ) {
                            player.setSpeed(it)
                        }
                        BottomBar(
                            modifier = Modifier.align(alignment = Alignment.BottomCenter)
                                .windowInsetsPadding(insets = WindowInsets.safeContent),
                            playerState = player
                        )
                    }
                }
            })

        LaunchedEffect(playerSnapshot.isPlaying) {
            player.pictureInPicture.setEligible(playerSnapshot.isPlaying)
        }

        LaunchedEffect(Unit) {
            delay(2000.milliseconds)
            playVideo()
        }
    }
}
