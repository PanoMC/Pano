package com.panomc.node

import com.panomc.node.files.PluginScanner
import com.panomc.node.files.ScannedPlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One fixture jar per descriptor format, built in a temp directory.
 *
 * A zip with one text file in it is exactly what a plugin jar is as far as this scanner is
 * concerned, so the fixtures are real: the descriptors below are trimmed copies of what LuckPerms,
 * Velocity's own example, Fabric API and a Forge mod actually ship.
 */
class PluginScannerTest {
    @TempDir
    lateinit var root: File

    private val scanner = PluginScanner()

    @Test
    fun `reads a bukkit plugin yml`() {
        jar(
            "LuckPerms.jar",
            "plugin.yml" to """
                name: LuckPerms
                version: '5.4.102'
                main: me.lucko.luckperms.bukkit.LPBukkitBootstrap
                api-version: '1.13'
                description: A permissions plugin
                authors: [Luck, someone]
                commands:
                  luckperms:
                    description: nested, and skipped
            """.trimIndent()
        )

        val plugin = scan().single()

        assertEquals("LuckPerms.jar", plugin.file)
        assertEquals("LuckPerms", plugin.name)
        assertEquals("5.4.102", plugin.version)
        assertEquals("me.lucko.luckperms.bukkit.LPBukkitBootstrap", plugin.main)
        assertEquals("1.13", plugin.api)
        assertEquals("A permissions plugin", plugin.description)
        assertEquals(listOf("Luck", "someone"), plugin.authors)
        assertTrue(plugin.enabled)
        assertEquals(PluginScanner.KIND_PLUGIN, plugin.kind)
    }

    @Test
    fun `reads a paper plugin yml with a block description and a list of authors`() {
        jar(
            "Modern.jar",
            "paper-plugin.yml" to """
                # A comment nobody reads
                name: Modern
                version: 2.0.0
                main: com.example.Modern
                api-version: "1.21"
                description: >
                  A plugin whose description
                  runs over two lines.
                authors:
                  - Ada
                  - Grace
            """.trimIndent()
        )

        val plugin = scan().single()

        assertEquals("Modern", plugin.name)
        assertEquals("2.0.0", plugin.version)
        assertEquals("1.21", plugin.api)
        assertEquals("A plugin whose description runs over two lines.", plugin.description)
        assertEquals(listOf("Ada", "Grace"), plugin.authors)
    }

    @Test
    fun `reads a bungee yml with a single author`() {
        jar(
            "Proxy.jar",
            "bungee.yml" to """
                name: ProxyThing
                main: com.example.ProxyThing
                version: 1.2.3
                author: Ada
            """.trimIndent()
        )

        val plugin = scan().single()

        assertEquals("ProxyThing", plugin.name)
        assertEquals(listOf("Ada"), plugin.authors)
        assertEquals(PluginScanner.KIND_PLUGIN, plugin.kind)
    }

    @Test
    fun `reads a velocity plugin json`() {
        jar(
            "VelocityThing.jar",
            "velocity-plugin.json" to """
                {
                  "id": "velocitything",
                  "name": "Velocity Thing",
                  "version": "1.0.0",
                  "description": "Does a thing on a proxy",
                  "authors": ["Ada"],
                  "main": "com.example.VelocityThing"
                }
            """.trimIndent()
        )

        val plugin = scan().single()

        assertEquals("Velocity Thing", plugin.name)
        assertEquals("1.0.0", plugin.version)
        assertEquals("com.example.VelocityThing", plugin.main)
        assertEquals(listOf("Ada"), plugin.authors)
        assertEquals(PluginScanner.KIND_PLUGIN, plugin.kind)
    }

    @Test
    fun `reads a fabric mod json, authors and all the shapes they come in`() {
        jar(
            "fabric-api.jar",
            "fabric.mod.json" to """
                {
                  "schemaVersion": 1,
                  "id": "fabric-api",
                  "name": "Fabric API",
                  "version": "0.102.0",
                  "description": "Essential hooks for modding with Fabric.",
                  "authors": ["FabricMC", { "name": "Ada" }],
                  "entrypoints": { "main": ["net.fabricmc.api.ModInitializer"] },
                  "depends": { "minecraft": ">=1.21", "fabricloader": ">=0.15" }
                }
            """.trimIndent()
        )

        val plugin = scan(PluginScanner.KIND_MOD).single()

        assertEquals("Fabric API", plugin.name)
        assertEquals("0.102.0", plugin.version)
        assertEquals("net.fabricmc.api.ModInitializer", plugin.main)
        assertEquals(">=1.21", plugin.api)
        assertEquals(listOf("FabricMC", "Ada"), plugin.authors)
        assertEquals(PluginScanner.KIND_MOD, plugin.kind)
    }

    @Test
    fun `reads a quilt mod json out of its nested loader block`() {
        jar(
            "quilted.jar",
            "quilt.mod.json" to """
                {
                  "schema_version": 1,
                  "quilt_loader": {
                    "group": "com.example",
                    "id": "quilted",
                    "version": "3.1.0",
                    "metadata": {
                      "name": "Quilted",
                      "description": "A quilt mod",
                      "contributors": { "Ada": "Owner", "Grace": "Author" }
                    }
                  }
                }
            """.trimIndent()
        )

        val plugin = scan(PluginScanner.KIND_MOD).single()

        assertEquals("Quilted", plugin.name)
        assertEquals("3.1.0", plugin.version)
        assertEquals("A quilt mod", plugin.description)
        assertEquals(listOf("Ada", "Grace"), plugin.authors)
        assertEquals(PluginScanner.KIND_MOD, plugin.kind)
    }

    @Test
    fun `reads the first mods table of a forge mods toml`() {
        jar(
            "jei.jar",
            "META-INF/mods.toml" to """
                modLoader = "javafml"
                loaderVersion = "[47,)"
                license = "MIT"

                [[mods]]
                modId = "jei"
                version = "15.3.0.4"
                displayName = "Just Enough Items"
                authors = "mezz"
                description = '''
                JEI is an item and recipe viewing mod
                for Minecraft.
                '''

                [[dependencies.jei]]
                modId = "forge"
                mandatory = true
            """.trimIndent()
        )

        val plugin = scan(PluginScanner.KIND_MOD).single()

        assertEquals("Just Enough Items", plugin.name)
        assertEquals("15.3.0.4", plugin.version)
        assertEquals("JEI is an item and recipe viewing mod for Minecraft.", plugin.description)
        assertEquals(listOf("mezz"), plugin.authors)
        assertEquals(PluginScanner.KIND_MOD, plugin.kind)
    }

    @Test
    fun `reads a neoforge mods toml and splits the authors it packs into one string`() {
        jar(
            "neomod.jar",
            "META-INF/neoforge.mods.toml" to """
                modLoader = "javafml"

                [[mods]]
                modId = "neomod"
                version = "1.0.0"
                displayName = "Neo Mod"
                authors = "Ada, Grace"
                description = "A neoforge mod"
            """.trimIndent()
        )

        val plugin = scan(PluginScanner.KIND_MOD).single()

        assertEquals("Neo Mod", plugin.name)
        assertEquals("1.0.0", plugin.version)
        assertEquals(listOf("Ada", "Grace"), plugin.authors)
    }

    @Test
    fun `a disabled jar keeps its identity and says it is off`() {
        jar(
            "LuckPerms.jar.disabled",
            "plugin.yml" to "name: LuckPerms\nversion: 5.4.102\nmain: me.lucko.Main"
        )

        val plugin = scan().single()

        assertEquals("LuckPerms.jar.disabled", plugin.file)
        assertEquals("LuckPerms", plugin.name)
        assertFalse(plugin.enabled)
    }

    @Test
    fun `a jar with no descriptor is still listed under its own file name`() {
        jar("mystery.jar", "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0")

        // Not a zip at all: a truncated download, which a panel still has to be able to show.
        File(root, "broken.jar").writeText("this is not a zip")

        val plugins = scan(PluginScanner.KIND_MOD).associateBy { it.name }

        assertEquals(setOf("mystery", "broken"), plugins.keys)
        assertNull(plugins["mystery"]?.version)
        assertEquals(PluginScanner.KIND_MOD, plugins["broken"]?.kind)
    }

    @Test
    fun `anything that is not a jar is not a plugin`() {
        File(root, "config.yml").writeText("nope")
        File(root, "LuckPerms").mkdirs()
        File(root, ".jar").writeText("nope")

        assertTrue(scan().isEmpty())
    }

    @Test
    fun `a jar is parsed once and re-read when it changes`() {
        val file = jar("Thing.jar", "plugin.yml" to "name: Thing\nversion: 1.0.0")

        val first = scan().single()
        val second = scan().single()

        // The same object, not merely an equal one: the point of the cache is that the zip was
        // not opened again.
        assertSame(first, second)

        jar("Thing.jar", "plugin.yml" to "name: Thing\nversion: 2.0.0")

        // The size changed here, but a rewrite that keeps the size is the case that matters, so
        // the timestamp is moved too.
        file.setLastModified(file.lastModified() + 5_000)

        assertEquals("2.0.0", scan().single().version)
    }

    @Test
    fun `a jar that was deleted stops being listed`() {
        val file = jar("Gone.jar", "plugin.yml" to "name: Gone\nversion: 1.0.0")

        assertEquals(1, scan().size)

        assertTrue(file.delete())

        assertTrue(scan().isEmpty())
    }

    @Test
    fun `the directory follows the software and then what is actually there`() {
        assertEquals("mods", PluginScanner.directoryName("FABRIC"))
        assertEquals("mods", PluginScanner.directoryName("neoforge"))
        assertEquals("plugins", PluginScanner.directoryName("paper"))
        assertEquals("plugins", PluginScanner.directoryName(null))

        // An import whose software was never identified: the directory that exists wins.
        val server = File(root, "server").apply { mkdirs() }

        File(server, "mods").mkdirs()

        assertEquals("mods", PluginScanner.resolveDirectory(server, "").name)
        assertEquals("mods", PluginScanner.resolveDirectory(server, "fabric").name)

        File(server, "plugins").mkdirs()

        assertEquals("plugins", PluginScanner.resolveDirectory(server, "paper").name)
        assertEquals(PluginScanner.KIND_MOD, PluginScanner.kindOf(File(server, "mods")))
        assertEquals(PluginScanner.KIND_PLUGIN, PluginScanner.kindOf(File(server, "plugins")))
    }

    @Test
    fun `a descriptor bigger than the limit is not read`() {
        jar(
            "Huge.jar",
            "plugin.yml" to "name: Huge\nversion: 1.0.0\ndescription: " +
                "x".repeat(PluginScanner.MAX_DESCRIPTOR_BYTES + 1)
        )

        val plugin = scan().single()

        assertEquals("Huge", plugin.name)
        assertNull(plugin.version)
    }

    private fun scan(kind: String = PluginScanner.KIND_PLUGIN): List<ScannedPlugin> = scanner.scan(root, kind)

    private fun jar(name: String, vararg entries: Pair<String, String>): File {
        val file = File(root, name)

        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (path, content) ->
                out.putNextEntry(ZipEntry(path))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }

        return file
    }
}
