package com.panomc.platform.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class ConfigBackupPathTest {
    @Test
    fun `a config file without a parent directory is backed up next to it`() {
        val backup = backupFileFor(File("config.conf"), "2026-01-01_00-00-00")

        assertEquals(File("config.conf").absoluteFile.parentFile, backup.parentFile)
        assertEquals("config-backup-2026-01-01_00-00-00.conf", backup.name)
    }

    @Test
    fun `a config file in a folder is backed up in that folder`() {
        val dir = File("some-dir").absoluteFile
        val backup = backupFileFor(File(dir, "config.conf"), "d")

        assertEquals(dir, backup.parentFile)
    }
}
