package com.guyuuan.mpv_kmp.props

object MpvDecoderProperties {
    const val CURRENT_VIDEO_CODEC = "selected-tracks/video/codec"
    const val CURRENT_VIDEO_CODEC_DESCRIPTION = "selected-tracks/video/codec-desc"
    const val VIDEO_CODEC = "video-codec"
    const val HWDEC_CURRENT = "hwdec-selected"
    const val VIDEO_PARAMS = "video-params"
    const val VIDEO_OUT_PARAMS = "video-out-params"
    const val CURRENT_AUDIO_CODEC = "selected-tracks/audio/codec"
    const val CURRENT_AUDIO_CODEC_DESCRIPTION = "selected-tracks/audio/codec-desc"
    const val AUDIO_CODEC = "audio-codec"
    const val AUDIO_CODEC_NAME = "audio-codec-name"
    const val AUDIO_PARAMS = "audio-params"
    const val AUDIO_OUT_PARAMS = "audio-out-params"

    val VIDEO: List<String> = listOf(
        CURRENT_VIDEO_CODEC,
        CURRENT_VIDEO_CODEC_DESCRIPTION,
        VIDEO_CODEC,
        HWDEC_CURRENT,
        VIDEO_PARAMS,
        VIDEO_OUT_PARAMS
    )
    val AUDIO: List<String> = listOf(
        CURRENT_AUDIO_CODEC,
        CURRENT_AUDIO_CODEC_DESCRIPTION,
        AUDIO_CODEC,
        AUDIO_CODEC_NAME,
        AUDIO_PARAMS,
        AUDIO_OUT_PARAMS
    )
    val ALL: List<String> = VIDEO + AUDIO
}
