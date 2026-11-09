package com.panomc.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstallManagerZipTest {
    @TempDir
    lateinit var dir: File

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File(dir, "theme.zip")

        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }

        return file
    }

    @Test
    fun `a normal theme zip is unpacked`() {
        val out = File(dir, "out").apply { mkdirs() }

        assertTrue(InstallManager.extractZipSafely(zip("manifest.json" to "{}", "build/index.js" to "x"), out))
        assertEquals("x", File(out, "build/index.js").readText())
    }

    @Test
    fun `an entry pointing outside the folder is refused and not written`() {
        val out = File(dir, "out").apply { mkdirs() }

        assertFalse(InstallManager.extractZipSafely(zip("manifest.json" to "{}", "../evil.txt" to "x"), out))
        assertFalse(File(dir, "evil.txt").exists())
    }

    @Test
    fun `theme ids that leave the themes folder are refused`() {
        assertTrue(InstallManager.THEME_ID.matches("vanilla-theme"))
        assertFalse(InstallManager.THEME_ID.matches("../plugins"))
        assertFalse(InstallManager.THEME_ID.matches(""))
        assertFalse(InstallManager.THEME_ID.matches("a/b"))
    }
}
