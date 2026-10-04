package com.panomc.platform.server.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupWorldsTest {
    @Test
    fun `reads level-name, defaulting to world`() {
        assertEquals("world", BackupWorlds.levelNameOf(null))
        assertEquals("world", BackupWorlds.levelNameOf(""))
        assertEquals("world", BackupWorlds.levelNameOf("motd=A server\nlevel-name=\n"))
        assertEquals("survival", BackupWorlds.levelNameOf("#Minecraft server properties\nlevel-name=survival\nmotd=x\n"))
        assertEquals("my world", BackupWorlds.levelNameOf("level-name=my world"))
    }

    @Test
    fun `takes the level, its nether and end, then other worlds alphabetically`() {
        val directories = listOf("plugins", "world_the_end", "world", "logs", "world_nether", "spawn", "creative")

        assertEquals(
            listOf("world", "world_nether", "world_the_end", "creative", "spawn"),
            BackupWorlds.select("world", directories, listOf("spawn", "creative"))
        )
    }

    @Test
    fun `only names worlds that exist`() {
        assertEquals(listOf("world"), BackupWorlds.select("world", listOf("world", "plugins"), emptyList()))
        assertTrue(BackupWorlds.select("world", listOf("plugins"), listOf("gone")).isEmpty())
    }

    @Test
    fun `does not probe the level's own directories or ones that are never worlds`() {
        val candidates = BackupWorlds.probeCandidates(
            "world",
            listOf("world", "world_nether", "world_the_end", "plugins", "Logs", ".pano-node", "lobby", "arena")
        )

        assertEquals(listOf("arena", "lobby"), candidates)
    }

    @Test
    fun `caps how many directories it probes`() {
        val many = (1..200).map { "dir%03d".format(it) }

        assertEquals(BackupWorlds.MAX_PROBES, BackupWorlds.probeCandidates("world", many).size)
    }
}
