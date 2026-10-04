package com.panomc.platform.server

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerPropertyKeysTest {
    @Test
    fun `reads any key the file could hold, as text`() {
        val read = ServerPropertyKeys.read(
            JsonObject()
                .put("motd", "A Pano server")
                .put("max-players", 40)
                .put("online-mode", false)
                .put("difficulty", "hard")
                .put("rcon.password", "secret")
                .put("level-type", "minecraft:flat")
                .put("entity-broadcast-range-percentage", 100)
        )

        assertEquals(
            mapOf(
                "motd" to "A Pano server",
                "max-players" to "40",
                "online-mode" to "false",
                "difficulty" to "hard",
                "rcon.password" to "secret",
                "level-type" to "minecraft:flat",
                "entity-broadcast-range-percentage" to "100"
            ),
            read
        )
    }

    @Test
    fun `drops a key Pano manages elsewhere, and one that is not a key at all`() {
        val read = ServerPropertyKeys.read(
            JsonObject()
                .put("motd", "kept")
                // The startup port: allocated by the node, never written from here.
                .put("server-port", 25599)
                // Not plain tokens: each could end the line or start another key.
                .put("evil=key", "x")
                .put("Motd", "upper case is not how the file spells anything")
                .put("with space", "x")
                .put("", "x")
        )

        assertEquals(mapOf("motd" to "kept"), read)
        assertTrue(ServerPropertyKeys.isAllowed("query.port"))
        assertFalse(ServerPropertyKeys.isAllowed("server-port"))
    }

    @Test
    fun `drops a value server properties cannot hold`() {
        val read = ServerPropertyKeys.read(
            JsonObject()
                .put("motd", JsonObject().put("text", "nope"))
                .put("gamemode", JsonArray().add("survival"))
                .put("pvp", true)
        )

        assertEquals(mapOf("pvp" to "true"), read)
    }

    @Test
    fun `a null value is left alone rather than written as the text null`() {
        assertEquals(emptyMap<String, String>(), ServerPropertyKeys.read(JsonObject().putNull("motd")))
        assertEquals(emptyMap<String, String>(), ServerPropertyKeys.read(null))
    }

    @Test
    fun `a newline in a value cannot start a second line`() {
        val read = ServerPropertyKeys.read(JsonObject().put("motd", "one\nonline-mode=false"))

        assertEquals(mapOf("motd" to "one online-mode=false"), read)
    }

    @Test
    fun `parses a file the way the node reads it`() {
        val parsed = ServerPropertyKeys.parseFile(
            """
            #Minecraft server properties
            #Managed by Pano
            motd=A Pano server
            max-players = 40
            resource-pack=https://example.com/pack.zip?v=1
            server-port=25569

            ! also a comment
            broken line without separator
            =no key
            """.trimIndent()
        )

        assertEquals(
            mapOf(
                "motd" to "A Pano server",
                "max-players" to "40",
                // Split at the first `=` only: a URL keeps its own.
                "resource-pack" to "https://example.com/pack.zip?v=1",
                // Returned even though it would be refused on the way back: the page shows the
                // file as it is.
                "server-port" to "25569"
            ),
            parsed
        )
        assertEquals(emptyMap<String, String>(), ServerPropertyKeys.parseFile(null))
    }

    @Test
    fun `reads a value the way the server wrote it`() {
        // Properties.store escapes the colon and non-ASCII; the page must see the plain value.
        val parsed = ServerPropertyKeys.parseFile(
            "level-type=minecraft\\:normal\nmotd=T\\u00FCrk\\u00E7e sunucu\\!\nresource-pack=https\\://x/y\\=1\ngenerator-settings=\\\\ back"
        )

        assertEquals("minecraft:normal", parsed["level-type"])
        assertEquals("Türkçe sunucu!", parsed["motd"])
        assertEquals("https://x/y=1", parsed["resource-pack"])
        assertEquals("\\ back", parsed["generator-settings"])
        assertEquals("plain", ServerPropertyKeys.unescape("plain"))
    }
}
