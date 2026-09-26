package com.panomc.platform.hosted

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContainerModeTest {
    @TempDir
    lateinit var dir: File

    private fun jar(name: String = "Pano-1.0.0.jar") = File(dir, name).apply { writeText("jar") }

    @Test
    fun `inactive without env or pointer`() {
        val mode = ContainerMode(emptyMap(), jar(), File(dir, "missing"))
        assertFalse(mode.active)
        assertFalse(mode.isHosted)
        assertEquals(File(dir, "missing"), mode.dataDir)
    }

    @Test
    fun `active when hosted or container env is set`() {
        val hosted = ContainerMode(mapOf("PANO_HOSTED" to "pano-host"), null, dir)
        assertTrue(hosted.active)
        assertTrue(hosted.isHosted)
        assertEquals("pano-host", hosted.hosted)

        assertTrue(ContainerMode(mapOf("PANO_CONTAINER" to "1"), null, dir).active)
        assertFalse(ContainerMode(mapOf("PANO_CONTAINER" to "0", "PANO_HOSTED" to " "), null, dir).active)
    }

    @Test
    fun `active when the pointer next to the jar names it`() {
        val running = jar()
        File(dir, ".pano-jar").writeText("Pano-1.0.0.jar")

        val mode = ContainerMode(emptyMap(), running, File(dir, "elsewhere"))
        assertTrue(mode.active)
        assertEquals(dir.absoluteFile, mode.dataDir.absoluteFile)
    }

    @Test
    fun `pointer naming another jar does not activate`() {
        val running = jar()
        File(dir, ".pano-jar").writeText("Pano-2.0.0.jar")
        assertFalse(ContainerMode(emptyMap(), running, dir).active)
    }

    @Test
    fun `restart exits with 75 in container mode only`() = runBlocking {
        val codes = mutableListOf<Int>()
        val exit = ProcessExit { codes += it }

        assertTrue(ContainerMode(mapOf("PANO_HOSTED" to "pano-host"), null, dir).restartInPlace { exit.request(it) })
        exit.exit()
        assertEquals(listOf(75), codes)

        var called = false
        assertFalse(ContainerMode(emptyMap(), null, dir).restartInPlace { called = true })
        assertFalse(called)
    }

    @Test
    fun `self update stages jar then exits 75`() = runBlocking {
        val data = File(dir, "data").apply { mkdirs() }
        val staged = File(dir, "download.tmp").apply { writeText("new") }
        val codes = mutableListOf<Int>()
        val mode = ContainerMode(mapOf("PANO_CONTAINER" to "1"), null, data)

        assertTrue(mode.installAndRestart(staged, "1.2.3") {
            assertEquals("Pano-1.2.3.jar", JarPointer(data).current())
            codes += it
        })
        assertEquals(listOf(75), codes)
    }

    @Test
    fun `first exit code request wins`() {
        val codes = mutableListOf<Int>()
        val exit = ProcessExit { codes += it }
        exit.exit()
        assertTrue(exit.request(75))
        assertFalse(exit.request(0))
        exit.exit()
        assertEquals(listOf(0, 75), codes)
    }
}
