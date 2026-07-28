package com.guyuuan.mpv_kmp.util

expect class PlatformLock constructor(){
    fun <T> withLock(action: () -> T): T
}