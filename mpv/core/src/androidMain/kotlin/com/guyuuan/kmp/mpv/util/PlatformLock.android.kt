package com.guyuuan.kmp.mpv.util

actual class PlatformLock {
    actual fun <T> withLock(action: () -> T): T = synchronized(this){
        action()
    }
}