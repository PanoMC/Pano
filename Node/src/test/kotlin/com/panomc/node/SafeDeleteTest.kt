package com.panomc.node

import com.panomc.node.util.SafeDelete
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class SafeDeleteTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `deletes a plain tree and reports a missing one as gone`() {
        val tree = File(directory, "tree").apply { mkdirs() }

        File(tree, "a/b").mkdirs()
        File(tree, "a/b/c.txt").writeText("x")
        File(tree, "d.txt").writeText("y")

        assertTrue(SafeDelete.tree(tree))
        assertFalse(tree.exists())
        assertTrue(SafeDelete.tree(File(directory, "never-there")))
    }

    @Test
    fun `removes a link inside the tree without emptying what it points at`() {
        val outside = File(directory, "outside").apply { mkdirs() }
        val precious = File(outside, "world.dat").apply { writeText("keep me") }
        val server = File(directory, "server").apply { mkdirs() }
        val cache = File(server, "plugins/cache").apply { mkdirs() }

        assumeTrue(runCatching {
            Files.createSymbolicLink(File(cache, "x").toPath(), outside.toPath())
            Files.createSymbolicLink(File(cache, "file-link").toPath(), precious.toPath())
        }.isSuccess)

        assertTrue(SafeDelete.tree(File(server, "plugins")))

        assertFalse(File(server, "plugins").exists())
        assertTrue(precious.isFile)
        assertTrue(precious.readText() == "keep me")
    }

    @Test
    fun `removes a windows junction inside the tree without emptying what it points at`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("win"))

        val outside = File(directory, "outside").apply { mkdirs() }
        val precious = File(outside, "world.dat").apply { writeText("keep me") }
        val server = File(directory, "server").apply { mkdirs() }
        val junction = File(server, "plugins/cache/x").apply { parentFile.mkdirs() }

        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", junction.absolutePath, outside.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes() }
            .waitFor() == 0

        assumeTrue(made && junction.isDirectory)

        assertTrue(SafeDelete.tree(File(server, "plugins")))

        assertFalse(File(server, "plugins").exists())
        assertTrue(precious.isFile)
    }

    @Test
    fun `removes a link given directly, and a dangling one`() {
        val outside = File(directory, "outside").apply { mkdirs() }
        val precious = File(outside, "keep.txt").apply { writeText("x") }
        val link = File(directory, "link")
        val dangling = File(directory, "dangling")

        assumeTrue(runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            Files.createSymbolicLink(dangling.toPath(), File(directory, "nowhere").toPath())
        }.isSuccess)

        assertTrue(SafeDelete.tree(link))
        assertTrue(SafeDelete.tree(dangling))

        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(dangling.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(precious.isFile)
    }
}
