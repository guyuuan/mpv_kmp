package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.UIKit.UIColor
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun MpvComposeView(
    modifier: Modifier,
    state: MpvPlayer
) {
    UIKitView(
        modifier = modifier,
        factory = {
            UIView().apply {
                backgroundColor = UIColor.blackColor

                val layerPtr = CFBridgingRetain(layer)
                if (layerPtr != null) {
                    state.player.attach(layerPtr)
                    CFBridgingRelease(layerPtr)
                }
            }
        },
        update = {}
    )
}
