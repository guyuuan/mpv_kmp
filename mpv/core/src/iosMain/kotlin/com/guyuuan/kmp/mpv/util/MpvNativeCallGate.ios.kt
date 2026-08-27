package com.guyuuan.kmp.mpv.util

import platform.Foundation.NSCondition

internal actual class MpvNativeCallGate {
    private val condition = NSCondition()
    private var closing = false
    private var activeCalls = 0

    actual fun reopen() = condition.withLock {
        check(activeCalls == 0) { "Cannot reopen libmpv while native calls are active" }
        closing = false
    }

    actual fun beginClosing() = condition.withLock {
        closing = true
    }

    actual fun <T> withControlCall(onClosing: () -> T, action: () -> T): T =
        withCall(onClosing, action)

    actual fun <T> withEventCall(onClosing: () -> T, action: () -> T): T =
        withCall(onClosing, action)

    actual fun <T> withRenderCall(onClosing: () -> T, action: () -> T): T =
        withCall(onClosing, action)

    actual fun <T> closeWhenIdle(action: () -> T): T {
        condition.withLock {
            closing = true
            while (activeCalls != 0) condition.wait()
        }
        return action()
    }

    private fun <T> withCall(onClosing: () -> T, action: () -> T): T {
        if (!acquire()) return onClosing()
        return try {
            action()
        } finally {
            release()
        }
    }

    private fun acquire(): Boolean = condition.withLock {
        if (closing) return@withLock false
        activeCalls += 1
        true
    }

    private fun release() = condition.withLock {
        activeCalls -= 1
        check(activeCalls >= 0) { "Unbalanced libmpv native call gate" }
        if (activeCalls == 0) condition.broadcast()
    }

    private inline fun <T> NSCondition.withLock(action: () -> T): T {
        lock()
        return try {
            action()
        } finally {
            unlock()
        }
    }
}
