package com.panomc.node

import com.panomc.node.task.ModpackIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModpackIndexTest {
    /** A trimmed but structurally faithful `modrinth.index.json`. */
    private val fixture = """
        {
          "formatVersion": 1,
          "game": "minecraft",
          "versionId": "1.4.2",
          "name": "Test Pack",
          "files": [
            {
              "path": "mods/lithium.jar",
              "hashes": { "sha1": "aaa", "sha512": "bbb" },
              "env": { "client": "required", "server": "required" },
              "downloads": ["https://cdn.modrinth.com/data/x/versions/y/lithium.jar"],
              "fileSize": 512000
            },
            {
              "path": "mods/sodium.jar",
              "hashes": { "sha1": "ccc" },
              "env": { "client": "required", "server": "unsupported" },
              "downloads": ["https://cdn.modrinth.com/data/z/versions/w/sodium.jar"],
              "fileSize": 900000
            },
            {
              "path": "mods/optional-extras.jar",
              "env": { "client": "optional", "server": "optional" },
              "downloads": ["https://cdn.modrinth.com/data/q/versions/r/extras.jar"]
            },
            {
              "path": "config/unenvironmented.toml",
              "downloads": ["https://cdn.modrinth.com/data/c/versions/d/cfg.toml"]
            }
          ],
          "dependencies": {
            "minecraft": "1.20.1",
            "fabric-loader": "0.14.21"
          }
        }
    """.trimIndent()

    @Test
    fun `reads the loader and the game version a pack needs`() {
        val pack = ModpackIndex.parse(fixture)

        assertNotNull(pack)
        assertEquals("Test Pack", pack!!.name)
        assertEquals("1.4.2", pack.versionId)
        assertEquals("1.20.1", pack.minecraftVersion)
        assertEquals("fabric-loader", pack.loader)
        assertEquals("0.14.21", pack.loaderVersion)
    }

    @Test
    fun `keeps every file a server can use and drops only the unsupported ones`() {
        val pack = ModpackIndex.parse(fixture)!!

        val paths = pack.files.map { it.path }

        // An optional mod is installed: on a server the person who chose the pack decides, and a
        // missing optional mod usually means clients cannot join.
        assertTrue(paths.contains("mods/lithium.jar"))
        assertTrue(paths.contains("mods/optional-extras.jar"))
        // No `env` at all is not a refusal.
        assertTrue(paths.contains("config/unenvironmented.toml"))
        // Explicitly unsupported is the only thing that is left out.
        assertTrue(!paths.contains("mods/sodium.jar"))
    }

    @Test
    fun `carries the hashes and mirrors of an entry`() {
        val entry = ModpackIndex.parse(fixture)!!.files.first { it.path == "mods/lithium.jar" }

        assertEquals("bbb", entry.sha512)
        assertEquals("aaa", entry.sha1)
        assertEquals(512000L, entry.size)
        assertEquals(1, entry.downloads.size)
    }

    @Test
    fun `refuses an entry with no usable download`() {
        val pack = ModpackIndex.parse(
            """
            {
              "game": "minecraft",
              "files": [
                { "path": "mods/a.jar", "downloads": [] },
                { "path": "mods/b.jar", "downloads": ["ftp://example.com/b.jar"] },
                { "path": "mods/c.jar", "downloads": ["https://example.com/c.jar"] }
              ],
              "dependencies": { "minecraft": "1.20.1", "forge": "47.1.3" }
            }
            """.trimIndent()
        )!!

        assertEquals(listOf("mods/c.jar"), pack.files.map { it.path })
        assertEquals("forge", pack.loader)
        assertEquals("47.1.3", pack.loaderVersion)
    }

    @Test
    fun `is null for anything that is not a Minecraft pack manifest`() {
        assertNull(ModpackIndex.parse(null))
        assertNull(ModpackIndex.parse(""))
        assertNull(ModpackIndex.parse("not json"))
        assertNull(ModpackIndex.parse("""{ "game": "terraria", "files": [] }"""))
    }

    @Test
    fun `a pack with no loader is readable and simply names none`() {
        val pack = ModpackIndex.parse("""{ "game": "minecraft", "dependencies": { "minecraft": "1.21" } }""")

        assertNotNull(pack)
        assertNull(pack!!.loader)
        assertEquals("1.21", pack.minecraftVersion)
        assertTrue(pack.files.isEmpty())
    }
}
