package com.guyuuan.kmp.mpv.util

expect class PlatformLock constructor(){
    fun <T> withLock(action: () -> T): T
}