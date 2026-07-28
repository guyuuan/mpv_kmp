package com.guyuuan.mpv_kmp.util

actual class PlatformLock {
    actual fun <T> withLock(action: () -> T): T =synchronized(this) {
        action()
    }
}