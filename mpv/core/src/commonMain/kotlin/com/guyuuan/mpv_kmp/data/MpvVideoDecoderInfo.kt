package com.guyuuan.mpv_kmp.data

data class MpvVideoDecoderInfo(
    val codec: String?,
    val codecDescription: String?,
    val decoderCodec: String?,
    val hardwareDecoder: String?,
    val params: String?,
    val outputParams: String?
)
