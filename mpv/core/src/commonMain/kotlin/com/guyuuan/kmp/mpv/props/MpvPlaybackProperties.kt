package com.guyuuan.kmp.mpv.props

object MpvPlaybackProperties {
    const val PAUSE = "pause"
    const val TIME_POSITION = "time-pos"
    const val DURATION = "duration"
    const val SPEED = "speed"
    const val CACHE_BUFFERING_STATE = "cache-buffering-state"

    val ALL: List<String> = listOf(
        PAUSE,
        TIME_POSITION,
        DURATION,
        SPEED,
        CACHE_BUFFERING_STATE
    )
}
