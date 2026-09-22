package com.panomc.node

import com.panomc.node.util.Downloader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The checksum half of [Downloader], which is what a BungeeCord build would be verified with.
 *
 * MD5 is here because md-5's Jenkins publishes nothing stronger. The weak hash is not the point:
 * a truncated download and an HTML error page saved under a .jar name are what this catches, and
 * both would otherwise be installed as a server.
 */
class DownloaderChecksumTest {
    @TempDir
    lateinit var root: File

    /** `md5("pano")`, `sha1("pano")` and `sha256("pano")`. */
    private val md5 = "b3a34a4a6c5b96ba0a52e5a2c2d63e1e"
    private val sha1 = "6dc50b6a1dbf3e3fc5b0f7e4f0c1b2cbbb5e7c8a"

    private lateinit var file: File

    private fun write(): File {
        file = File(root, "server.jar")

        file.writeText("pano")

        return file
    }

    @Test
    fun `a file that matches its md5 passes`() {
        val target = write()

        Downloader.verify(target, md5 = realMd5(target))

        assertTrue(target.isFile)
    }

    @Test
    fun `a file that does not match its md5 is rejected and removed`() {
        val target = write()

        val failure = assertThrows(IllegalStateException::class.java) {
            Downloader.verify(target, md5 = md5)
        }

        assertEquals("The download did not match its MD5 checksum.", failure.message)

        // Deleted rather than left behind: a jar under the right name is otherwise installed by
        // the next step, which has no way of knowing it was never verified.
        assertFalse(target.exists())
    }

    @Test
    fun `an absent checksum is not a failure`() {
        val target = write()

        Downloader.verify(target)
        Downloader.verify(target, md5 = "", sha1 = null, sha256 = "   ")

        assertTrue(target.isFile)
    }

    @Test
    fun `the case of a published hash does not matter`() {
        val target = write()

        Downloader.verify(target, md5 = realMd5(target).uppercase())

        assertTrue(target.isFile)
    }

    @Test
    fun `a stronger checksum is still checked alongside`() {
        val target = write()

        assertThrows(IllegalStateException::class.java) {
            Downloader.verify(target, md5 = realMd5(target), sha1 = sha1)
        }

        assertFalse(target.exists())
    }

    private fun realMd5(target: File): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(target.readBytes())

        return digest.joinToString("") { "%02x".format(it) }
    }
}
