package com.guyuuan.mpv_kmp.props

object MpvPlaybackProperties {
    const val PAUSE = "pause"
    const val TIME_POSITION = "time-pos"
    const val DURATION = "duration"
    const val SPEED = "speed"

    val ALL: List<String> = listOf(PAUSE, TIME_POSITION, DURATION,SPEED)
}
