package com.guyuuan.kmp.mpv.data

import com.guyuuan.kmp.mpv.MpvEventType

data class MpvEvent(
    val type: MpvEventType,
    val name: String? = null,
    val value: String? = null,
    val error: Int = 0
)
