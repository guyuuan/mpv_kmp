package com.guyuuan.kmp.mpv.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopNativeLibrariesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun task(entries: List<String>): ExtractMpvKmpDesktopNativeLibsTask {
        val project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        val archive = project.file("native.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("fixture".toByteArray())
                zip.closeEntry()
            }
        }
        return project.tasks.create("extract", ExtractMpvKmpDesktopNativeLibsTask::class.java).apply {
            platform.set("windows-x86-64")
            enabledIntegration.set(true)
            outputDirectory.set(project.layout.buildDirectory.dir("native"))
            nativeLibraryArchives.from(archive)
        }
    }

    @Test
    fun extractsLibrariesAndPreservesNestedLicenses() {
        val task = task(listOf("mpv.dll", "avcodec.dll", "META-INF/LICENSE-mpv-kmp.txt", "META-INF/licenses/LGPL.txt"))
        task.extract()
        val output = task.outputDirectory.get().asFile
        assertTrue(output.resolve("mpv.dll").isFile)
        assertEquals("fixture", output.resolve("META-INF/licenses/LGPL.txt").readText())
    }

    @Test
    fun rejectsUnsafeAndUnexpectedNestedEntries() {
        listOf("../escape.dll", "META-INF/../../escape.dll", "/escape.dll",
            "C:/escape.dll", "META-INF\\escape.dll", "other/mpv.dll").forEach { entry ->
            val task = task(listOf("mpv.dll", entry))
            assertFailsWith<IllegalArgumentException>(entry) { task.extract() }
            assertFalse(task.outputDirectory.get().asFile.parentFile.resolve("escape.dll").exists())
        }
    }

    @Test
    fun acceptsPlatformMainLibraryNames() {
        listOf(
            "windows-x86-64" to "mpv-2.dll",
            "darwin-aarch64" to "libmpv.2.dylib",
            "linux-x86-64" to "libmpv.so.2",
        ).forEach { (platform, library) ->
            val task = task(listOf(library, "META-INF/LICENSE.txt"))
            task.platform.set(platform)
            task.extract()
            assertTrue(task.outputDirectory.get().asFile.resolve(library).isFile)
        }
    }

    @Test
    fun rejectsArchiveWithoutMainLibrary() {
        val task = task(listOf("avcodec.dll", "META-INF/LICENSE.txt"))
        assertFailsWith<IllegalArgumentException> { task.extract() }
    }

    @Test
    fun disablingLocalDiscoveryAfterApplyUsesArchiveDespiteLocalLibraries() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        val localRoot = project.file("mpv/core/src/jvmMain/resources")
        listOf("windows-x86-64", "windows-aarch64", "darwin-aarch64", "darwin-x86-64",
            "linux-x86-64", "linux-aarch64").forEach { localRoot.resolve(it).mkdirs() }
        project.pluginManager.apply(MpvKmpPlugin::class.java)
        val extension = project.extensions.getByType(MpvKmpExtension::class.java)
        val task = project.tasks.getByName("extractMpvKmpDesktopNativeLibs") as ExtractMpvKmpDesktopNativeLibsTask
        assertTrue(task.localNativeLibrariesDirectory.isPresent)
        extension.useLocalNativeLibraries.set(false)
        assertFalse(task.localNativeLibrariesDirectory.isPresent)
        val archive = project.file("published.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mpv.dll"))
            zip.write("published".toByteArray())
            zip.closeEntry()
        }
        task.platform.set("windows-x86-64")
        task.nativeLibraryArchives.setFrom(archive)
        task.extract()
        assertEquals("published", task.outputDirectory.get().asFile.resolve("mpv.dll").readText())
        val override = project.file("explicit").apply { mkdirs() }
        override.resolve("mpv.dll").writeText("override")
        extension.desktopNativeDirectoryOverride.set(override)
        task.extract()
        assertEquals("override", task.outputDirectory.get().asFile.resolve("mpv.dll").readText())
    }
}