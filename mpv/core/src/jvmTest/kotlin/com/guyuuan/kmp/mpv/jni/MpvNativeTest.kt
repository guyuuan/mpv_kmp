package com.guyuuan.kmp.mpv.jni

import com.guyuuan.kmp.mpv.JvmMpv
import com.sun.jna.Library
import com.sun.jna.NativeLibrary
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MpvNativeTest {
    @Test
    fun unixMpvLibrariesUseLocalSymbolVisibility() {
        assertEquals(0x2 or 0x4, mpvNativeOpenOptions("darwin")[Library.OPTION_OPEN_FLAGS])
        assertEquals(0x2, mpvNativeOpenOptions("linux")[Library.OPTION_OPEN_FLAGS])
        assertTrue(mpvNativeOpenOptions("windows").isEmpty())
    }

    @Test
    fun bundledMacosMpvDoesNotExportSymbolsToTheProcess() {
        if (!System.getProperty("os.name").contains("mac", ignoreCase = true)) return

        val nativeDirectory = findBundledMacosNativeDirectory()
        val previousNativeDirectory = System.getProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST)
        try {
            System.setProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST, nativeDirectory.absolutePath)

            assertTrue(MpvNative.lib.mpv_client_api_version() > 0)
            assertFailsWith<UnsatisfiedLinkError> {
                NativeLibrary.getProcess().getFunction("mpv_create")
            }
        } finally {
            if (previousNativeDirectory == null) {
                System.clearProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST)
            } else {
                System.setProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST, previousNativeDirectory)
            }
        }
    }

    @Test
    fun bundledWindowsMpvLoadsWithIsolatedSearchPath() {
        if (!System.getProperty("os.name").contains("windows", ignoreCase = true)) return

        val nativeDirectory = findBundledWindowsNativeDirectory()
        val previousNativeDirectory = System.getProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST)
        try {
            System.setProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST, nativeDirectory.absolutePath)
            assertTrue(MpvNative.lib.mpv_client_api_version() > 0)
        } finally {
            if (previousNativeDirectory == null) {
                System.clearProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST)
            } else {
                System.setProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST, previousNativeDirectory)
            }
        }
    }

    @Test
    fun desktopPlayerCanStopTerminateAndReinitializeRepeatedly() {
        if (!System.getProperty("os.name").contains("mac", ignoreCase = true)) return

        val nativeDirectory = findBundledMacosNativeDirectory()
        val previousNativeDirectory = System.getProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST)
        val commandExecutor = Executors.newSingleThreadExecutor()
        val player = JvmMpv()
        try {
            System.setProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST, nativeDirectory.absolutePath)
            repeat(100) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                assertTrue(player.initialize())
                player.setCoroutineScope(scope)
                player.observeProperty("pause")

                val stopResult = commandExecutor.submit<Int> { player.stop() }
                player.terminate()
                assertTrue(stopResult.get(5, TimeUnit.SECONDS) <= 0)
                scope.cancel()
            }
        } finally {
            player.terminate()
            commandExecutor.shutdownNow()
            if (previousNativeDirectory == null) {
                System.clearProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST)
            } else {
                System.setProperty(MPV_NATIVE_DIR_PROPERTY_FOR_TEST, previousNativeDirectory)
            }
        }
    }

    private fun findBundledMacosNativeDirectory(): File {
        val arch = when (val osArch = System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64" -> "x86-64"
            else -> error("Unsupported macOS test architecture: $osArch")
        }
        val platform = "darwin-$arch"
        return listOf(
            File("src/jvmMain/resources/$platform"),
            File("mpv/core/src/jvmMain/resources/$platform")
        ).firstOrNull { it.resolve("libmpv.dylib").isFile }
            ?: error("Cannot find bundled macOS mpv libraries for $platform")
    }

    private fun findBundledWindowsNativeDirectory(): File {
        val platform = "windows-x86-64"
        return listOf(
            File("src/jvmMain/resources/$platform"),
            File("mpv/core/src/jvmMain/resources/$platform")
        ).firstOrNull { it.resolve("mpv.dll").isFile }
            ?: error("Cannot find bundled Windows mpv libraries for $platform")
    }

    private companion object {
        const val MPV_NATIVE_DIR_PROPERTY_FOR_TEST = "mpv.kmp.native.dir"
    }
}
