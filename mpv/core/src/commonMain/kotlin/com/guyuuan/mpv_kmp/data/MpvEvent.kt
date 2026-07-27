package com.guyuuan.mpv_kmp.data

import com.guyuuan.mpv_kmp.MpvEventType

data class MpvEvent(
    val type: MpvEventType,
    val name: String? = null,
    val value: String? = null,
    val error: Int = 0
)
