package com.panomc.node

import com.panomc.node.files.ZipTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipToolTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `round trips a directory`() {
        val plugins = File(root, "plugins")

        plugins.mkdirs()

        File(plugins, "a.yml").writeText("hello")

        val archive = File(root, "out.zip")

        ZipTool.archive(root, listOf("plugins"), archive)

        assertTrue(ZipTool.isZip(archive))

        val target = File(root, "restored")

        ZipTool.extract(root, archive, target)

        assertEquals("hello", File(target, "plugins/a.yml").readText())
    }

    @Test
    fun `refuses an entry that climbs out of the extraction directory`() {
        val archive = File(root, "evil.zip")

        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../../escaped.txt"))
            out.write("owned".toByteArray())
            out.closeEntry()
        }

        val target = File(root, "unpack")

        assertThrows(IllegalArgumentException::class.java) {
            ZipTool.extract(root, archive, target)
        }

        assertFalse(File(root.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `refuses an absolute entry`() {
        val archive = File(root, "absolute.zip")

        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("/etc/pano-owned"))
            out.write("x".toByteArray())
            out.closeEntry()
        }

        // A leading slash is stripped to a relative path rather than escaping, so this must land
        // inside the extraction directory and nowhere near /etc.
        val target = File(root, "unpack")

        ZipTool.extract(root, archive, target)

        assertTrue(File(target, "etc/pano-owned").isFile)
    }

    @Test
    fun `never archives the credentials`() {
        val pano = File(root, "plugins/Pano")

        pano.mkdirs()

        File(pano, "config.conf").writeText("platform { token = secret }")
        File(pano, "notes.txt").writeText("fine")

        val archive = File(root, "plugins.zip")

        ZipTool.archive(root, listOf("plugins"), archive)

        val target = File(root, "restored")

        ZipTool.extract(root, archive, target)

        assertFalse(File(target, "plugins/Pano/config.conf").exists())
        assertTrue(File(target, "plugins/Pano/notes.txt").isFile)
    }

    @Test
    fun `spots something that is not an archive`() {
        val notAZip = File(root, "index.html")

        notAZip.writeText("<html>404</html>")

        assertFalse(ZipTool.isZip(notAZip))
    }

    @Test
    fun `lifts a server out of the folder somebody zipped it in`() {
        val extracted = File(root, "extracted")

        File(extracted, "MySMP/plugins").mkdirs()
        File(extracted, "MySMP/server.jar").writeText("jar")
        File(extracted, "MySMP/server.properties").writeText("server-port=25565")

        assertTrue(ZipTool.flattenSingleRoot(extracted))

        assertTrue(File(extracted, "server.jar").isFile)
        assertTrue(File(extracted, "plugins").isDirectory)
        assertFalse(File(extracted, "MySMP").exists())
    }

    @Test
    fun `leaves a directory that is already a server exactly as it is`() {
        val extracted = File(root, "already")

        extracted.mkdirs()
        File(extracted, "server.jar").writeText("jar")
        File(extracted, "world").mkdirs()

        assertFalse(ZipTool.flattenSingleRoot(extracted))

        assertTrue(File(extracted, "server.jar").isFile)
        assertTrue(File(extracted, "world").isDirectory)
    }

    @Test
    fun `does not flatten a single file, only a single directory`() {
        val extracted = File(root, "onefile")

        extracted.mkdirs()
        File(extracted, "server.jar").writeText("jar")

        assertFalse(ZipTool.flattenSingleRoot(extracted))
        assertTrue(File(extracted, "server.jar").isFile)
    }

    @Test
    fun `ignores the folder macOS adds when it zips`() {
        val extracted = File(root, "mac")

        File(extracted, "__MACOSX").mkdirs()
        File(extracted, "MySMP").mkdirs()
        File(extracted, "MySMP/server.jar").writeText("jar")

        assertTrue(ZipTool.flattenSingleRoot(extracted))
        assertTrue(File(extracted, "server.jar").isFile)
    }
}
