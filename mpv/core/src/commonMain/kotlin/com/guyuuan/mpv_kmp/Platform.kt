package com.guyuuan.mpv_kmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform