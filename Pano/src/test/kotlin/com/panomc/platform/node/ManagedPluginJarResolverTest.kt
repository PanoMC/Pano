package com.panomc.platform.node

import com.panomc.platform.server.ServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ManagedPluginJarResolverTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `maps every bukkit flavour onto the spigot build`() {
        listOf(ServerType.SPIGOT, ServerType.BUKKIT, ServerType.PAPER, ServerType.FOLIA, ServerType.PURPUR)
            .forEach { type ->
                assertEquals("spigot", ManagedPluginJarResolver.platformOf(type), type.name)
            }
    }

    @Test
    fun `maps the proxies and the mod loaders`() {
        assertEquals("velocity", ManagedPluginJarResolver.platformOf(ServerType.VELOCITY))
        assertEquals("bungeecord", ManagedPluginJarResolver.platformOf(ServerType.BUNGEECORD))
        assertEquals("bungeecord", ManagedPluginJarResolver.platformOf(ServerType.WATERFALL))
        assertEquals("fabric", ManagedPluginJarResolver.platformOf(ServerType.FABRIC))
        assertEquals("fabric", ManagedPluginJarResolver.platformOf(ServerType.QUILT))
    }

    @Test
    fun `has no plugin for the software that has no module`() {
        listOf(ServerType.VANILLA, ServerType.FORGE, ServerType.NEOFORGE).forEach { type ->
            assertNull(ManagedPluginJarResolver.platformOf(type), type.name)
            assertNull(ManagedPluginJarResolver.configPathOf(type), type.name)
        }
    }

    @Test
    fun `mod loaders get mods, everything else plugins`() {
        assertEquals("mods", ManagedPluginJarResolver.targetDirOf(ServerType.FABRIC))
        assertEquals("mods", ManagedPluginJarResolver.targetDirOf(ServerType.QUILT))
        assertEquals("plugins", ManagedPluginJarResolver.targetDirOf(ServerType.PAPER))
        assertEquals("plugins", ManagedPluginJarResolver.targetDirOf(ServerType.VELOCITY))
    }

    @Test
    fun `config path follows each platform's own data folder`() {
        assertEquals("plugins/Pano/config.conf", ManagedPluginJarResolver.configPathOf(ServerType.PAPER))
        assertEquals("plugins/Pano/config.conf", ManagedPluginJarResolver.configPathOf(ServerType.BUNGEECORD))
        // Velocity keys its data folder on the lowercase plugin id, not on the display name.
        assertEquals("plugins/pano/config.conf", ManagedPluginJarResolver.configPathOf(ServerType.VELOCITY))
        assertEquals("config/pano/config.conf", ManagedPluginJarResolver.configPathOf(ServerType.FABRIC))
    }

    @Test
    fun `matches a versioned release asset and nothing else`() {
        assertTrue(ManagedPluginJarResolver.matchesAsset("pano-spigot-1.0.0-alpha.62.jar", "spigot"))
        assertTrue(ManagedPluginJarResolver.matchesAsset("pano-spigot-local-build.jar", "spigot"))
        // The zip of the same jar is published next to it and must never be mistaken for it.
        assertFalse(ManagedPluginJarResolver.matchesAsset("pano-spigot-1.0.0-alpha.62.zip", "spigot"))
        assertFalse(ManagedPluginJarResolver.matchesAsset("pano-velocity-1.0.0.jar", "spigot"))
        assertFalse(ManagedPluginJarResolver.matchesAsset("pano-core-1.0.0.jar", "spigot"))
        assertFalse(ManagedPluginJarResolver.matchesAsset("LICENSE", "spigot"))
    }

    @Test
    fun `finds a module build inside a checkout`() {
        val libs = File(root, "Spigot/build/libs")

        libs.mkdirs()

        val jar = File(libs, "pano-spigot-local-build.jar")

        jar.writeText("jar")

        assertEquals(jar.canonicalPath, ManagedPluginJarResolver.findLocalJar(root, "spigot")?.canonicalPath)
        assertNull(ManagedPluginJarResolver.findLocalJar(root, "fabric"))
    }

    @Test
    fun `prefers the newest jar when a module has several`() {
        val libs = File(root, "Velocity/build/libs")

        libs.mkdirs()

        val older = File(libs, "pano-velocity-1.0.0-alpha.1.jar")
        val newer = File(libs, "pano-velocity-1.0.0-alpha.2.jar")

        older.writeText("old")
        newer.writeText("new")

        older.setLastModified(1_000_000L)
        newer.setLastModified(2_000_000L)

        assertEquals(newer.canonicalPath, ManagedPluginJarResolver.findLocalJar(root, "velocity")?.canonicalPath)
    }

    @Test
    fun `a local jar is published under a path every node can resolve`() {
        // Never a file:// url: only a node on this very machine could open one, and the remote
        // node's install threw on it while still reporting the install as done.
        assertEquals("/api/node/plugin-jars/spigot", ManagedPluginJarResolver.pluginJarPath("spigot"))
        assertEquals("/api/node/plugin-jars/velocity", ManagedPluginJarResolver.pluginJarPath("velocity"))

        ManagedPluginJarResolver.PLATFORMS.forEach { platform ->
            assertTrue(ManagedPluginJarResolver.pluginJarPath(platform).startsWith("/"), platform)
        }
    }

    @Test
    fun `does not search past the depth limit`() {
        val deep = File(root, "a/b/c/d/e/build/libs")

        deep.mkdirs()

        File(deep, "pano-spigot-1.0.0.jar").writeText("jar")

        assertNull(ManagedPluginJarResolver.findLocalJar(root, "spigot"))
    }
}
