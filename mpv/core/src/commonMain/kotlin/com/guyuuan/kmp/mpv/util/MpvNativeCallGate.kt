package com.guyuuan.kmp.mpv.util

/**
 * Prevents native libmpv calls from racing with handle destruction.
 *
 * Closing is terminal until [reopen] is called by a later successful initialization attempt.
 * Calls already in flight are allowed to finish; calls arriving after closing starts use their
 * supplied fallback without entering native code.
 */
internal expect class MpvNativeCallGate() {
    fun reopen()
    fun beginClosing()

    fun <T> withControlCall(onClosing: () -> T, action: () -> T): T
    fun <T> withEventCall(onClosing: () -> T, action: () -> T): T
    fun <T> withRenderCall(onClosing: () -> T, action: () -> T): T

    /** Waits without a timeout until every admitted call has left native code. */
    fun <T> closeWhenIdle(action: () -> T): T
}
