package com.guyuuan.mpv_kmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun MpvComposeView(
    modifier: Modifier = Modifier,
    state: MpvPlayerState
)
