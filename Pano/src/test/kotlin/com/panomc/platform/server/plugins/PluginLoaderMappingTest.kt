package com.panomc.platform.server.plugins

import com.panomc.platform.server.ServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginLoaderMappingTest {
    @Test
    fun `a paper server accepts the whole bukkit family`() {
        val loaders = PluginLoaderMapping.modrinthLoaders(ServerType.PAPER)

        assertTrue(loaders.containsAll(listOf("paper", "spigot", "bukkit")))
        assertEquals("plugin", PluginLoaderMapping.modrinthProjectType(ServerType.PAPER))
        assertEquals("plugins", PluginLoaderMapping.targetDir(ServerType.PAPER))
        assertEquals("PAPER", PluginLoaderMapping.hangarPlatform(ServerType.PAPER))
    }

    @Test
    fun `mod loaders read mods and search for mods`() {
        listOf(ServerType.FABRIC, ServerType.QUILT, ServerType.FORGE, ServerType.NEOFORGE).forEach { type ->
            assertEquals("mods", PluginLoaderMapping.targetDir(type))
            assertEquals("mod", PluginLoaderMapping.modrinthProjectType(type))
            assertTrue(PluginLoaderMapping.isModLoader(type))
        }

        assertTrue(PluginLoaderMapping.modrinthLoaders(ServerType.QUILT).contains("fabric"))
        assertEquals(PluginLoaderMapping.CURSEFORGE_CLASS_MODS, PluginLoaderMapping.curseForgeClassId(ServerType.FABRIC))
        assertEquals(
            PluginLoaderMapping.CURSEFORGE_CLASS_PLUGINS,
            PluginLoaderMapping.curseForgeClassId(ServerType.PAPER)
        )
    }

    @Test
    fun `hangar has nothing for the mod loaders`() {
        assertNull(PluginLoaderMapping.hangarPlatform(ServerType.FABRIC))
        assertEquals("WATERFALL", PluginLoaderMapping.hangarPlatform(ServerType.BUNGEECORD))
        assertEquals("VELOCITY", PluginLoaderMapping.hangarPlatform(ServerType.VELOCITY))
    }

    @Test
    fun `vanilla can run nothing at all`() {
        assertFalse(PluginLoaderMapping.supportsPlugins(ServerType.VANILLA))
        assertTrue(PluginLoaderMapping.modrinthLoaders(ServerType.VANILLA).isEmpty())
    }

    @Test
    fun `a patch release also searches its minor line`() {
        assertEquals(listOf("1.21.8", "1.21"), PluginLoaderMapping.gameVersions(ServerType.PAPER, "1.21.8"))
        assertEquals(listOf("1.21"), PluginLoaderMapping.gameVersions(ServerType.PAPER, "1.21"))
    }

    @Test
    fun `an unparsable or proxy version filters on nothing`() {
        assertTrue(PluginLoaderMapping.gameVersions(ServerType.PAPER, "1.8.x").isEmpty())
        assertTrue(PluginLoaderMapping.gameVersions(ServerType.PAPER, null).isEmpty())
        assertTrue(PluginLoaderMapping.gameVersions(ServerType.VELOCITY, "1.21.8").isEmpty())
    }

    @Test
    fun `compatibility treats no requirement as everything matching`() {
        assertTrue(PluginCompatibility.matches(listOf("paper"), emptyList()))
        assertTrue(PluginCompatibility.matches(listOf("PAPER"), listOf("paper")))
        assertFalse(PluginCompatibility.matches(emptyList(), listOf("paper")))
        assertFalse(PluginCompatibility.matches(listOf("fabric"), listOf("paper")))
    }
}
