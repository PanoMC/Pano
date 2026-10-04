package com.panomc.platform.hosted

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JarPointerTest {
    @TempDir
    lateinit var dir: File

    private fun staged(content: String) =
        File.createTempFile("pano-download_", ".tmp", dir.resolve("stage").apply { mkdirs() }).apply { writeText(content) }

    private fun data() = dir.resolve("data").apply { mkdirs() }

    @Test
    fun `first install writes pointer without previous`() {
        val pointer = JarPointer(data())
        val name = pointer.stage(staged("v1"), "1.0.0-alpha.1")

        assertEquals("Pano-1.0.0-alpha.1.jar", name)
        assertEquals("v1", File(pointer.dataDir, name).readText())
        assertEquals(name, File(pointer.dataDir, ".pano-jar").readText())
        assertNull(pointer.previous())
        assertFalse(File(pointer.dataDir, ".pano-jar.previous").exists())
    }

    @Test
    fun `switch keeps previous and prunes older jars`() {
        val data = data()
        val pointer = JarPointer(data)
        pointer.stage(staged("v1"), "1.0.0")
        pointer.stage(staged("v2"), "v1.0.1")
        File(data, "Pano-0.9.0.jar").writeText("stray")
        File(data, "other.jar").writeText("keep")

        val name = pointer.stage(staged("v3"), "1.0.2")

        assertEquals("Pano-1.0.2.jar", pointer.current())
        assertEquals("Pano-1.0.1.jar", pointer.previous())
        assertEquals("Pano-1.0.1.jar", File(data, ".pano-jar.previous").readText())
        assertEquals(
            setOf("Pano-1.0.2.jar", "Pano-1.0.1.jar", "other.jar"),
            data.listFiles()!!.filter { it.name.endsWith(".jar") }.map { it.name }.toSet()
        )
        assertEquals("v3", File(data, name).readText())
    }

    @Test
    fun `restaging the current version keeps previous`() {
        val pointer = JarPointer(data())
        pointer.stage(staged("v1"), "1.0.0")
        pointer.stage(staged("v2"), "1.0.1")
        pointer.stage(staged("v2b"), "1.0.1")

        assertEquals("Pano-1.0.1.jar", pointer.current())
        assertEquals("Pano-1.0.0.jar", pointer.previous())
        assertEquals("v2b", File(pointer.dataDir, "Pano-1.0.1.jar").readText())
        assertTrue(File(pointer.dataDir, "Pano-1.0.0.jar").isFile)
    }

    @Test
    fun `no tmp files are left and pointer is replaced atomically`() {
        val data = data()
        val pointer = JarPointer(data)
        // A stale tmp from an interrupted earlier write must not break or survive the next one.
        File(data, ".pano-jar.tmp").writeText("garbage")
        pointer.stage(staged("v1"), "1.0.0")
        pointer.stage(staged("v2"), "1.0.1")

        assertEquals(
            setOf(".pano-jar", ".pano-jar.previous", "Pano-1.0.0.jar", "Pano-1.0.1.jar"),
            data.list()!!.toSet()
        )
    }

    @Test
    fun `invalid versions and pointer contents are rejected`() {
        val pointer = JarPointer(data())
        assertThrows<IllegalArgumentException> { pointer.stage(staged("x"), "../evil") }
        assertThrows<IllegalArgumentException> { pointer.stage(staged("x"), "1.0/2") }
        assertThrows<IllegalArgumentException> { pointer.stage(staged("x"), "") }

        File(pointer.dataDir, ".pano-jar").writeText("sub/Pano-1.jar")
        assertNull(pointer.current())
    }
}
