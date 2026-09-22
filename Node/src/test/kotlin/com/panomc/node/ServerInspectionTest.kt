package com.panomc.node

import com.panomc.node.task.ServerInspection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

class ServerInspectionTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `finds nothing in a directory that holds no jar`() {
        File(directory, "server.properties").writeText("server-port=25577\n")

        val result = ServerInspection.inspect(directory)

        assertNull(result.jar)
        assertNull(result.software)
        assertEquals(25577, result.port)
    }

    @Test
    fun `prefers the jar this daemon writes over anything else`() {
        jar(File(directory, "server.jar"), size = 1)
        jar(File(directory, "paper-1.21.8.jar"), size = 4000)

        assertEquals("server.jar", ServerInspection.findServerJar(directory))
    }

    @Test
    fun `picks the biggest jar when nothing is named server`() {
        jar(File(directory, "small-mod.jar"), size = 10)
        jar(File(directory, "purpur-1.21.8-2345.jar"), size = 9000)

        assertEquals("purpur-1.21.8-2345.jar", ServerInspection.findServerJar(directory))
    }

    @Test
    fun `never mistakes a leftover installer for the server`() {
        // Forge and NeoForge leave their installer behind, and it is bigger than what it produced.
        jar(File(directory, "forge-1.20.1-47.1.3-installer.jar"), size = 50_000)
        jar(File(directory, "run.jar"), size = 100)

        assertEquals("run.jar", ServerInspection.findServerJar(directory))
    }

    @Test
    fun `reads the software out of a name`() {
        assertEquals("paper", ServerInspection.softwareFromName("paper-1.21.8-41.jar"))
        assertEquals("purpur", ServerInspection.softwareFromName("purpur-1.21.8.jar"))
        assertEquals("velocity", ServerInspection.softwareFromName("velocity-3.3.0.jar"))
        assertEquals("vanilla", ServerInspection.softwareFromName("minecraft_server.1.21.8.jar"))
        // neoforge must win over forge, which is a substring of it.
        assertEquals("neoforge", ServerInspection.softwareFromName("neoforge-21.1.72-installer.jar"))
        assertNull(ServerInspection.softwareFromName("server.jar"))
        assertNull(ServerInspection.softwareFromName(null))
    }

    @Test
    fun `trusts the jar manifest over the file name`() {
        jar(File(directory, "server.jar"), title = "Purpur")

        val result = ServerInspection.inspect(directory)

        assertEquals("server.jar", result.jar)
        assertEquals("purpur", result.software)
    }

    @Test
    fun `falls back to the pack's loader when the directory says nothing`() {
        jar(File(directory, "server.jar"))

        val result = ServerInspection.inspect(directory, packVersion = "1.20.1", packLoader = "fabric-loader")

        assertEquals("fabric", result.software)
        assertEquals("1.20.1", result.version)
        // The minimum for 1.20.1, not the newest the host has: an automatic pick of a too-new
        // Java crashed a 1.21 server on shutdown once already.
        assertEquals(17, result.javaMajor)
    }

    @Test
    fun `reports an accepted eula and does not invent one`() {
        assertFalse(ServerInspection.isEulaAccepted(File(directory, "eula.txt")))

        val eula = File(directory, "eula.txt")

        eula.writeText("#By changing the setting below to TRUE\neula=false\n")
        assertFalse(ServerInspection.isEulaAccepted(eula))

        eula.writeText("#comment\neula = TRUE\n")
        assertTrue(ServerInspection.isEulaAccepted(eula))
    }

    @Test
    fun `refuses a port that is not one`() {
        jar(File(directory, "server.jar"))
        File(directory, "server.properties").writeText("server-port=0\n")

        assertNull(ServerInspection.inspect(directory).port)
    }

    /** Writes a jar with an optional manifest title and a filler entry of [size] bytes. */
    private fun jar(file: File, title: String? = null, size: Int = 32) {
        val manifest = Manifest()

        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"

        title?.let { manifest.mainAttributes[Attributes.Name.IMPLEMENTATION_TITLE] = it }

        JarOutputStream(file.outputStream().buffered(), manifest).use { out ->
            out.putNextEntry(ZipEntry("filler.bin"))
            out.write(ByteArray(size))
            out.closeEntry()
        }
    }
}
