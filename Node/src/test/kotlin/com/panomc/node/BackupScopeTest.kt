package com.panomc.node

import com.panomc.node.backup.BackupMode
import com.panomc.node.backup.BackupScope
import com.panomc.node.backup.BackupScopeException
import com.panomc.node.backup.BackupScopeKind
import com.panomc.node.files.BackupExcludeMatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BackupScopeTest {
    @TempDir
    lateinit var server: File

    private val defaults = BackupExcludeMatcher(null)

    private fun worlds(exclude: BackupExcludeMatcher = defaults): List<String> =
        BackupScope.resolve(server, BackupScopeKind.WORLDS, null, exclude).roots

    private fun custom(vararg include: String): List<String> =
        BackupScope.resolve(server, BackupScopeKind.CUSTOM, include.toList(), defaults).roots

    @Test
    fun `the default level and its dimensions are the worlds`() {
        File(server, "world").mkdirs()
        File(server, "world_nether").mkdirs()
        File(server, "world_the_end").mkdirs()
        File(server, "plugins").mkdirs()

        assertEquals(listOf("world", "world_nether", "world_the_end"), worlds())
    }

    @Test
    fun `level-name decides which directory is the world`() {
        File(server, "server.properties").writeText("motd=hi\nlevel-name=survival\n")
        File(server, "survival").mkdirs()
        File(server, "survival_nether").mkdirs()
        // Not the level any more, and without a level.dat nothing else makes it one.
        File(server, "world").mkdirs()

        assertEquals(listOf("survival", "survival_nether"), worlds())
    }

    @Test
    fun `any other top-level directory with a level dat is a world too`() {
        File(server, "world").mkdirs()
        File(server, "creative").mkdirs()
        File(server, "creative/level.dat").writeText("x")
        File(server, "plugins/Multiverse").mkdirs()
        // Only a level.dat directly inside counts.
        File(server, "archive/old").mkdirs()
        File(server, "archive/old/level.dat").writeText("x")

        assertEquals(listOf("creative", "world"), worlds())
    }

    @Test
    fun `no world directory at all fails with NO_WORLDS`() {
        File(server, "plugins").mkdirs()
        File(server, "server.jar").writeText("jar")

        val failure = assertThrows(BackupScopeException::class.java) { worlds() }

        assertEquals("NO_WORLDS", failure.code)
    }

    @Test
    fun `a hostile level-name is ignored rather than followed`() {
        File(server, "server.properties").writeText("level-name=../../etc\n")
        File(server, "lobby").mkdirs()
        File(server, "lobby/level.dat").writeText("x")

        assertEquals(listOf("lobby"), worlds())
    }

    @Test
    fun `a world named in the excludes is left out`() {
        File(server, "world").mkdirs()
        File(server, "world_nether").mkdirs()

        assertEquals(listOf("world"), worlds(BackupExcludeMatcher(listOf("world_nether/"))))
    }

    @Test
    fun `worlds in a backup are the top-level directories holding a level dat`() {
        val paths = sequenceOf(
            "world/level.dat",
            "world/region/r.0.0.mca",
            "world_nether/DIM-1/r.0.0.mca",
            "creative/level.dat",
            "archive/old/level.dat",
            "level.dat"
        )

        assertEquals(setOf("creative", "world"), BackupScope.worldsIn(paths))
    }

    @Test
    fun `custom takes its includes, collapsed and sorted`() {
        File(server, "world/region").mkdirs()
        File(server, "plugins/LuckPerms").mkdirs()
        File(server, "server.properties").writeText("a=b")

        assertEquals(listOf("plugins/LuckPerms", "server.properties", "world"), custom("world/region", "world", "server.properties", "plugins/LuckPerms", "missing"))
    }

    @Test
    fun `a custom glob selects matching top-level entries`() {
        File(server, "world").mkdirs()
        File(server, "world_nether").mkdirs()
        File(server, "plugins").mkdirs()

        assertEquals(listOf("world", "world_nether"), custom("world*"))
    }

    @Test
    fun `custom never selects a denylisted path`() {
        File(server, "plugins/Pano").mkdirs()
        File(server, "plugins/Pano/config.conf").writeText("token")
        File(server, "server.json").writeText("{}")

        val failure = assertThrows(BackupScopeException::class.java) { custom("server.json", "plugins/Pano/config.conf") }

        assertEquals("INVALID_SCOPE", failure.code)
    }

    @Test
    fun `custom without includes, or with an escaping one, is INVALID_SCOPE`() {
        assertEquals("INVALID_SCOPE", assertThrows(BackupScopeException::class.java) { custom() }.code)
        assertEquals("INVALID_SCOPE", assertThrows(BackupScopeException::class.java) { custom("  ") }.code)
        assertEquals("INVALID_SCOPE", assertThrows(BackupScopeException::class.java) { custom("../outside") }.code)
    }

    @Test
    fun `modes and scopes default when absent and are refused when unknown`() {
        assertEquals(BackupMode.FULL, BackupMode.parse(null))
        assertEquals(BackupMode.SNAPSHOT, BackupMode.parse("SNAPSHOT"))
        assertNull(BackupMode.parse("INCREMENTAL"))

        assertEquals(BackupScopeKind.ALL, BackupScopeKind.parse(""))
        assertEquals(BackupScopeKind.WORLDS, BackupScopeKind.parse("WORLDS"))
        assertNull(BackupScopeKind.parse("EVERYTHING"))
    }
}
