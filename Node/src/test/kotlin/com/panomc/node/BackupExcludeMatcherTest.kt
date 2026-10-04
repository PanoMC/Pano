package com.panomc.node

import com.panomc.node.files.BackupExcludeMatcher
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupExcludeMatcherTest {
    @Test
    fun `excludes the default noise`() {
        val matcher = BackupExcludeMatcher(null)

        assertTrue(matcher.matches("logs"))
        assertTrue(matcher.matches("logs/latest.log"))
        assertTrue(matcher.matches("cache/mojang_1.21.8.jar"))
        assertTrue(matcher.matches("paper-1.21.8.jar.tmp"))
        assertTrue(matcher.matches("plugins/update/x.jar.tmp"))
    }

    @Test
    fun `keeps everything worth restoring`() {
        val matcher = BackupExcludeMatcher(null)

        assertFalse(matcher.matches("world/level.dat"))
        assertFalse(matcher.matches("server.properties"))
        assertFalse(matcher.matches("plugins/LuckPerms/config.yml"))
        assertFalse(matcher.matches("server.jar"))
        // A directory pattern must not match something that merely starts with the same letters.
        assertFalse(matcher.matches("logsomething/a.txt"))
    }

    @Test
    fun `an empty list means the defaults, not "exclude nothing"`() {
        assertTrue(BackupExcludeMatcher(emptyList()).matches("logs/latest.log"))
    }

    @Test
    fun `takes an exact relative path`() {
        val matcher = BackupExcludeMatcher(listOf("world_nether"))

        assertTrue(matcher.matches("world_nether"))
        assertFalse(matcher.matches("world_nether/level.dat"))
        assertFalse(matcher.matches("a/world_nether"))
    }

    @Test
    fun `globs only the file name`() {
        val matcher = BackupExcludeMatcher(listOf("*.log"))

        assertTrue(matcher.matches("logs/latest.log"))
        assertTrue(matcher.matches("latest.log"))
        assertFalse(matcher.matches("latest.log.gz"))
    }

    @Test
    fun `the node's console log is always left out, whatever the patterns say`() {
        listOf(BackupExcludeMatcher(null), BackupExcludeMatcher(listOf("world_nether"))).forEach { matcher ->
            assertTrue(matcher.matches(".pano-node/console.log"))
            assertTrue(matcher.matches(".pano-node/console.log.1"))
            assertTrue(matcher.matches(".pano-node/console.log.2"))
        }

        // Only those files: the rest of .pano-node, and a console.log anywhere else, are the
        // operator's business.
        assertFalse(BackupExcludeMatcher(listOf("world_nether")).matches(".pano-node/process.json"))
        assertFalse(BackupExcludeMatcher(listOf("world_nether")).matches("console.log"))
        assertFalse(BackupExcludeMatcher(listOf("world_nether")).matches("plugins/.pano-node/console.log"))
    }

    @Test
    fun `a detached server's runtime files are always left out`() {
        val matcher = BackupExcludeMatcher(listOf("world_nether"))

        listOf("launch.sh", "stdin", "console.out", "console.out.1", "exit", "exit.tmp", "launcher.log").forEach {
            assertTrue(matcher.matches(".pano-node/$it"), it)
        }

        assertFalse(matcher.matches("console.out"))
        assertFalse(matcher.matches(".pano-node/exits"))
    }

    @Test
    fun `never matches the server directory itself`() {
        assertFalse(BackupExcludeMatcher(listOf("logs/")).matches(""))
    }
}
