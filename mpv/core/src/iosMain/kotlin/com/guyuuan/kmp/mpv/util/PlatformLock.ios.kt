package com.guyuuan.kmp.mpv.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

actual class PlatformLock {
    private val lock = SynchronizedObject()
    actual fun <T> withLock(action: () -> T): T = synchronized(lock) {
        action()
    }
}