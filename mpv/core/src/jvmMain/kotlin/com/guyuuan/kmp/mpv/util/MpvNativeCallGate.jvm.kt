package com.guyuuan.kmp.mpv.util

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual class MpvNativeCallGate {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var closing = false
    private var activeCalls = 0

    actual fun reopen() = lock.withLock {
        check(activeCalls == 0) { "Cannot reopen libmpv while native calls are active" }
        closing = false
    }

    actual fun beginClosing() = lock.withLock {
        closing = true
    }

    actual fun <T> withControlCall(onClosing: () -> T, action: () -> T): T =
        withCall(onClosing, action)

    actual fun <T> withEventCall(onClosing: () -> T, action: () -> T): T =
        withCall(onClosing, action)

    actual fun <T> withRenderCall(onClosing: () -> T, action: () -> T): T =
        withCall(onClosing, action)

    actual fun <T> closeWhenIdle(action: () -> T): T {
        var interrupted = false
        lock.withLock {
            closing = true
            while (activeCalls != 0) {
                try {
                    idle.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        return try {
            action()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun <T> withCall(onClosing: () -> T, action: () -> T): T {
        if (!acquire()) return onClosing()
        return try {
            action()
        } finally {
            release()
        }
    }

    private fun acquire(): Boolean = lock.withLock {
        if (closing) return@withLock false
        activeCalls += 1
        true
    }

    private fun release() = lock.withLock {
        activeCalls -= 1
        check(activeCalls >= 0) { "Unbalanced libmpv native call gate" }
        if (activeCalls == 0) idle.signalAll()
    }
}
