package com.guyuuan.kmp.mpv.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvNativeCallGateTest {
    @Test
    fun closeWaitsForControlAndRenderCallsAndRejectsNewCalls() {
        val gate = MpvNativeCallGate()
        val callsEntered = CountDownLatch(2)
        val releaseCalls = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)

        try {
            val controlCall = executor.submit<Int> {
                gate.withControlCall(onClosing = { -1 }) {
                    callsEntered.countDown()
                    releaseCalls.await()
                    0
                }
            }
            val renderCall = executor.submit<Boolean> {
                gate.withRenderCall(onClosing = { false }) {
                    callsEntered.countDown()
                    releaseCalls.await()
                    true
                }
            }
            assertTrue(callsEntered.await(5, TimeUnit.SECONDS))

            gate.beginClosing()
            executor.execute {
                gate.closeWhenIdle { closeCompleted.countDown() }
            }

            assertEquals(-1, gate.withControlCall(onClosing = { -1 }) { 0 })
            assertFalse(gate.withEventCall(onClosing = { false }) { true })
            assertFalse(closeCompleted.await(100, TimeUnit.MILLISECONDS))

            releaseCalls.countDown()
            assertTrue(closeCompleted.await(5, TimeUnit.SECONDS))
            assertEquals(0, controlCall.get(5, TimeUnit.SECONDS))
            assertTrue(renderCall.get(5, TimeUnit.SECONDS))
        } finally {
            releaseCalls.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun gateCanReopenAfterCloseCompletes() {
        val gate = MpvNativeCallGate()
        gate.beginClosing()
        gate.closeWhenIdle { }
        gate.closeWhenIdle { }

        assertEquals(-1, gate.withControlCall(onClosing = { -1 }) { 0 })

        gate.reopen()

        assertEquals(0, gate.withControlCall(onClosing = { -1 }) { 0 })
    }

    @Test
    fun interruptedCloseStillWaitsAndRunsDestroyAction() {
        val gate = MpvNativeCallGate()
        val callEntered = CountDownLatch(1)
        val releaseCall = CountDownLatch(1)
        val destroyRan = AtomicBoolean(false)
        val interruptRestored = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val call = executor.submit {
                gate.withControlCall(onClosing = {}) {
                    callEntered.countDown()
                    releaseCall.await()
                }
            }
            assertTrue(callEntered.await(5, TimeUnit.SECONDS))

            val closer = Thread {
                gate.closeWhenIdle { destroyRan.set(true) }
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
            closer.start()
            gate.beginClosing()
            closer.interrupt()

            assertFalse(destroyRan.get())
            releaseCall.countDown()
            closer.join(5_000)
            call.get(5, TimeUnit.SECONDS)

            assertFalse(closer.isAlive)
            assertTrue(destroyRan.get())
            assertTrue(interruptRestored.get())
        } finally {
            releaseCall.countDown()
            executor.shutdownNow()
        }
    }
}
