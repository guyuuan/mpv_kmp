package com.guyuuan.mpv_kmp.data

data class MpvPlaylistItem(
    val index: Int,
    val filename: String,
    val title: String? = null,
    val selected: Boolean = false
)
