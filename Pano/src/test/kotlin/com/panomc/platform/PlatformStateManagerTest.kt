package com.panomc.platform

import com.panomc.platform.hosted.ContainerMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The restart path shared by the panel restart and the Pano Backup restore must go through the container launcher when hosted. */
class PlatformStateManagerTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `hosted restart exits 75 for the launcher and spawns nothing`() = runBlocking {
        val exits = mutableListOf<Int>()
        val manager = PlatformStateManager().apply {
            containerMode = { ContainerMode(mapOf("PANO_HOSTED" to "pano-host"), null, dir) }
            shutdown = { exits.add(it) }
        }

        manager.restart(background = true)

        assertEquals(listOf(ContainerMode.EXIT_RESTART), exits)
    }

    @Test
    fun `self-hosted restart outside a jar refuses before shutting down`() {
        val exits = mutableListOf<Int>()
        val manager = PlatformStateManager().apply {
            containerMode = { ContainerMode(emptyMap(), null, File(dir, "missing")) }
            shutdown = { exits.add(it) }
        }

        // Tests run from classes directories, so the detached-JVM path refuses instead of spawning.
        assertThrows<IllegalStateException> { runBlocking { manager.restart() } }
        assertTrue(exits.isEmpty())
    }
}
