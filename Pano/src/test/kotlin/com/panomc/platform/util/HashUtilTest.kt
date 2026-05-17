package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class HashUtilTest {
    @Test
    fun `computeStableFileFingerprint stays stable across runs`(@TempDir tmp: Path) {
        val themeDir = tmp.toFile().also { it.mkdirs() }
        File(themeDir, "a.txt").writeBytes("hello world".toByteArray())
        File(themeDir, "sub").mkdirs()
        File(themeDir, "sub/b.txt").writeBytes("second file".toByteArray())
        File(themeDir, "sub/c.txt").writeBytes("third file content here".toByteArray())
        File(themeDir, "manifest.json").writeText("""{"id":"test"}""")

        val first = HashUtil.computeStableFileFingerprint(themeDir)
        val second = HashUtil.computeStableFileFingerprint(themeDir)
        assertEquals(first, second, "fingerprint must be stable across calls")
        assertEquals(64, first.length, "fingerprint must be 64-char hex SHA-256")
    }

    @Test
    fun `tampering a file changes the fingerprint`(@TempDir tmp: Path) {
        val themeDir = tmp.toFile()
        File(themeDir, "a.txt").writeBytes("original".toByteArray())
        File(themeDir, "manifest.json").writeText("""{"id":"test"}""")

        val before = HashUtil.computeStableFileFingerprint(themeDir)
        File(themeDir, "a.txt").writeBytes("MODIFIED".toByteArray())
        val after = HashUtil.computeStableFileFingerprint(themeDir)
        assertNotEquals(before, after, "fingerprint must change after a file is modified")
    }

    @Test
    fun `manifest changes do not affect the fingerprint`(@TempDir tmp: Path) {
        val themeDir = tmp.toFile()
        File(themeDir, "a.txt").writeBytes("content".toByteArray())
        File(themeDir, "manifest.json").writeText("""{"id":"test","fileFingerprint":""}""")

        val before = HashUtil.computeStableFileFingerprint(themeDir)
        File(themeDir, "manifest.json").writeText("""{"id":"test","fileFingerprint":"$before"}""")
        val after = HashUtil.computeStableFileFingerprint(themeDir)
        assertEquals(before, after, "manifest.json is excluded so editing it must not change the fingerprint")
    }

    /**
     * Cross-port parity sentinel: matches the JS-side fingerprint of this exact tree
     * (computed once with file-fingerprint.js on the same fixture; if the algorithm
     * ever drifts on either side this assertion catches it).
     */
    @Test
    fun `fingerprint of the canonical 3-file fixture matches the JS-side digest`(@TempDir tmp: Path) {
        val themeDir = tmp.toFile()
        File(themeDir, "a.txt").writeBytes("hello world".toByteArray())
        File(themeDir, "sub").mkdirs()
        File(themeDir, "sub/b.txt").writeBytes("second file".toByteArray())
        File(themeDir, "sub/c.txt").writeBytes("third file content here".toByteArray())
        File(themeDir, "manifest.json").writeText("""{"id":"test-theme","title":"Test","version":"v0.1.0","author":"x","panoVersion":"local","screenshots":[]}""")

        val expected = "9980f7e195160b3caad1912043c4cf36b374ebfcfc8d7788ad4f7e1e0e80c306"
        val actual = HashUtil.computeStableFileFingerprint(themeDir)
        assertEquals(expected, actual, "Kotlin must agree with the JS file-fingerprint.js port on the canonical fixture")
    }
}
