package com.guyuuan.mpv_kmp.data

data class MpvSubtitleTrack(
    val index: Int,
    val id: Int,
    val title: String? = null,
    val language: String? = null,
    override val selected: Boolean = false,
    val external: Boolean = false,
    val externalFilename: String? = null,
    val codec: String? = null,
    val defaultTrack: Boolean = false,
    val forced: Boolean = false
) : TrackItem
