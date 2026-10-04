package com.panomc.node

import com.panomc.node.files.FileService
import com.panomc.node.files.TransferService
import com.panomc.node.files.ZipStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class ZipStreamTest {
    @TempDir
    lateinit var server: File

    private fun seed() {
        File(server, "plugins/Essentials").mkdirs()
        File(server, "plugins/Essentials/config.yml").writeText("motd: hi")
        File(server, "plugins/Pano").mkdirs()
        File(server, "plugins/Pano/config.conf").writeText("token = secret")
        File(server, "plugins/Pano/data.yml").writeText("data")
        File(server, "plugins/Empty").mkdirs()
        File(server, "plugins/a.jar").writeText("jar")
        File(server, "world/region").mkdirs()
        File(server, "world/region/r.0.0.mca").writeText("region")
        File(server, "server.properties").writeText("online-mode=true")
        File(server, "server.json").writeText("{}")
    }

    private fun entries(bytes: ByteArray): Map<String, String?> {
        val result = linkedMapOf<String, String?>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break

                result[entry.name] = if (entry.isDirectory) null else zip.readBytes().decodeToString()
            }
        }

        return result
    }

    @Test
    fun `names entries relative to the base and skips denied children`() {
        seed()

        val output = ByteArrayOutputStream()

        ZipStream.write(server, "plugins", listOf("plugins/Essentials", "plugins/Pano", "plugins/Empty", "plugins/a.jar"), output)

        val zip = entries(output.toByteArray())

        assertEquals(
            setOf("Essentials/config.yml", "Pano/data.yml", "Empty/", "a.jar"),
            zip.keys
        )
        assertEquals("motd: hi", zip["Essentials/config.yml"])
        assertEquals("jar", zip["a.jar"])
    }

    @Test
    fun `a root base keeps the full path and never ships a denied file`() {
        seed()

        val output = ByteArrayOutputStream()

        ZipStream.write(server, "", listOf("plugins", "server.properties"), output)

        val zip = entries(output.toByteArray())

        assertTrue("plugins/Essentials/config.yml" in zip)
        assertTrue("plugins/Empty/" in zip)
        assertTrue("server.properties" in zip)
        assertTrue(zip.keys.none { it.endsWith("config.conf") })
    }

    @Test
    fun `a directory whose children are all denied is still written as a folder`() {
        File(server, "plugins/pano").mkdirs()
        File(server, "plugins/pano/config.conf").writeText("secret")

        val output = ByteArrayOutputStream()

        ZipStream.write(server, "plugins", listOf("plugins/pano"), output)

        assertEquals(setOf("pano/"), entries(output.toByteArray()).keys)
    }

    @Test
    fun `symbolic links are skipped`() {
        seed()

        val outside = Files.createTempFile("pano-zip-outside", ".txt").toFile()

        try {
            outside.writeText("outside")

            val link = File(server, "plugins/Essentials/link.txt")

            runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }

            val output = ByteArrayOutputStream()

            ZipStream.write(server, "plugins", listOf("plugins/Essentials"), output)

            assertEquals(setOf("Essentials/config.yml"), entries(output.toByteArray()).keys)
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `stops once the selection is larger than the ceiling`() {
        seed()

        assertThrows(IllegalStateException::class.java) {
            ZipStream.write(server, "", listOf("world", "plugins"), ByteArrayOutputStream(), maxBytes = 4)
        }
    }

    @Test
    fun `validates every top-level entry like a single pull`() {
        seed()

        assertNull(TransferService.validateArchive(server, "plugins", listOf("plugins/Essentials", "plugins/a.jar")))
        assertNull(TransferService.validateArchive(server, "", listOf("world", "server.properties")))

        assertEquals(
            FileService.ERROR_PATH_DENIED,
            TransferService.validateArchive(server, "plugins", listOf("plugins/Pano/config.conf"))
        )
        assertEquals(
            FileService.ERROR_PATH_DENIED,
            TransferService.validateArchive(server, "", listOf("server.json"))
        )
        assertEquals(
            FileService.ERROR_PATH_DENIED,
            TransferService.validateArchive(server, "plugins", listOf("world"))
        )
        assertEquals(
            FileService.ERROR_PATH_DENIED,
            TransferService.validateArchive(server, "", listOf("@backup/abc"))
        )
        assertEquals(
            FileService.ERROR_NOT_FOUND,
            TransferService.validateArchive(server, "plugins", listOf("plugins/missing.jar"))
        )
    }
}
