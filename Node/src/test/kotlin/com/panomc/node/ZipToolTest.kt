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

    @Test
    fun `follows a link only when its target is inside the server`() {
        val outside = File(root, "outside").apply { mkdirs() }

        File(outside, "secret.txt").writeText("host file")

        val server = File(root, "server")
        val plugins = File(server, "plugins").apply { mkdirs() }
        val shared = File(server, "shared").apply { mkdirs() }
        val pano = File(plugins, "Pano").apply { mkdirs() }

        File(plugins, "a.yml").writeText("hello")
        File(shared, "kept.txt").writeText("inside")
        File(pano, "config.conf").writeText("platform { token = secret }")

        linkDirectory(File(plugins, "linked-outside"), outside)
        linkDirectory(File(plugins, "linked-inside"), shared)
        // A link back up the tree must not loop.
        linkDirectory(File(plugins, "loop"), plugins)
        // A link to the credentials must not carry them out under another name.
        linkDirectory(File(server, "pano-link"), pano)

        val archive = File(root, "out.zip")

        ZipTool.archive(server, listOf("plugins", "pano-link"), archive)

        val names = java.util.zip.ZipFile(archive).use { zip -> zip.entries().toList().map { it.name } }

        assertTrue("plugins/a.yml" in names, names.toString())
        assertTrue("plugins/linked-inside/kept.txt" in names, names.toString())
        assertFalse(names.any { it.contains("linked-outside") || it.contains("secret") }, names.toString())
        assertFalse(names.any { it.endsWith("config.conf") }, names.toString())
        assertFalse(names.any { it.startsWith("plugins/loop/") }, names.toString())
    }

    @Test
    fun `never puts the archive it is writing into itself`() {
        val plugins = File(root, "plugins").apply { mkdirs() }

        File(plugins, "a.yml").writeText("hello")

        val archive = File(plugins, "plugins.zip")

        ZipTool.archive(root, listOf("plugins"), archive)

        val names = java.util.zip.ZipFile(archive).use { zip -> zip.entries().toList().map { it.name } }

        assertTrue("plugins/a.yml" in names, names.toString())
        assertFalse(names.any { it.endsWith("plugins.zip") }, names.toString())

        // Reached through a link inside the server, it is still the same file.
        linkDirectory(File(root, "linked"), plugins)

        val linked = File(plugins, "again.zip")

        ZipTool.archive(root, listOf("linked"), linked)

        val linkedNames = java.util.zip.ZipFile(linked).use { zip -> zip.entries().toList().map { it.name } }

        assertTrue("linked/a.yml" in linkedNames, linkedNames.toString())
        assertFalse(linkedNames.any { it.endsWith("again.zip") }, linkedNames.toString())
    }

    /** A symlink where the OS allows one, else a directory junction (Windows without the symlink right). */
    private fun linkDirectory(link: File, target: File) {
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())

            return
        } catch (_: Exception) {
        }

        val windows = System.getProperty("os.name").lowercase().contains("win")

        org.junit.jupiter.api.Assumptions.assumeTrue(windows, "Links cannot be created here.")

        val exit = ProcessBuilder("cmd", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
            .redirectErrorStream(true)
            .start()
            .also { it.inputStream.readBytes() }
            .waitFor()

        org.junit.jupiter.api.Assumptions.assumeTrue(exit == 0 && link.isDirectory, "Links cannot be created here.")
    }
}
