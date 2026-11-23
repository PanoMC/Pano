package com.panomc.platform.db

import com.panomc.platform.db.MariaDBManager.Companion.RELEASE_FILE
import com.panomc.platform.db.MariaDBManager.Companion.installedRelease
import com.panomc.platform.db.MariaDBManager.Companion.needsUpgrade
import com.panomc.platform.db.MariaDBManager.Companion.restoreInterruptedSwap
import com.panomc.platform.db.MariaDBManager.Companion.rollBack
import com.panomc.platform.db.MariaDBManager.Companion.swapIn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MariaDBManagerUpgradeTest {
    private fun folder(root: File, name: String, release: String? = null) = File(root, name).apply {
        File(this, "bin").mkdirs()
        File(this, "bin/mariadbd").writeText(name)
        if (release != null) File(this, RELEASE_FILE).writeText(release)
    }

    private fun contentOf(folder: File) = File(folder, "bin/mariadbd").readText()

    @Test
    fun `installed release is read from the release file`(@TempDir root: File) {
        assertNull(installedRelease(folder(root, "none")))
        assertNull(installedRelease(folder(root, "empty", " \n")))
        assertEquals("v11.8.9-r1", installedRelease(folder(root, "old", "v11.8.9-r1\n")))
    }

    @Test
    fun `upgrade only when the release differs or is unknown`() {
        assertTrue(needsUpgrade(null, "v11.8.10-r1"))
        assertTrue(needsUpgrade("v11.8.9-r1", "v11.8.10-r1"))
        assertFalse(needsUpgrade("v11.8.10-r1", "v11.8.10-r1"))
    }

    @Test
    fun `swap keeps the old binaries as backup`(@TempDir root: File) {
        val current = folder(root, "mariadb", "v11.8.9-r1")
        val staged = folder(root, "mariadb-new", "v11.8.10-r1")
        val backup = folder(root, "mariadb-old") // a leftover from an earlier run

        swapIn(current, staged, backup)

        assertEquals("mariadb-new", contentOf(current))
        assertEquals("v11.8.10-r1", installedRelease(current))
        assertEquals("mariadb", contentOf(backup))
        assertFalse(staged.exists())
    }

    @Test
    fun `failed swap puts the current binaries back`(@TempDir root: File) {
        val current = folder(root, "mariadb", "v11.8.9-r1")
        val missing = File(root, "mariadb-new")
        val backup = File(root, "mariadb-old")

        assertThrows(Exception::class.java) { swapIn(current, missing, backup) }

        assertEquals("mariadb", contentOf(current))
        assertFalse(backup.exists())
    }

    @Test
    fun `roll back restores the old binaries`(@TempDir root: File) {
        val current = folder(root, "mariadb", "v11.8.9-r1")
        val staged = folder(root, "mariadb-new", "v11.8.10-r1")
        val backup = File(root, "mariadb-old")

        swapIn(current, staged, backup)
        rollBack(current, backup)

        assertEquals("mariadb", contentOf(current))
        assertEquals("v11.8.9-r1", installedRelease(current))
        assertFalse(backup.exists())
    }

    @Test
    fun `interrupted swap is restored only when the binaries are gone`(@TempDir root: File) {
        val current = File(root, "mariadb")
        val backup = folder(root, "mariadb-old")

        restoreInterruptedSwap(current, backup)
        assertEquals("mariadb-old", contentOf(current))
        assertFalse(backup.exists())

        folder(root, "mariadb-old")
        restoreInterruptedSwap(current, backup)
        assertEquals("mariadb-old", contentOf(current))
        assertTrue(backup.exists())
    }
}
