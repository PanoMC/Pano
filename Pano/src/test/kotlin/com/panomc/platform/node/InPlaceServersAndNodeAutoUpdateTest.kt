package com.panomc.platform.node

import com.google.gson.GsonBuilder
import com.panomc.platform.config.migration.ConfigMigration33To34
import com.panomc.platform.db.migration.DatabaseMigration52to53
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.InPlaceUnsupported
import com.panomc.platform.server.InPlaceServerRules
import com.panomc.platform.server.ServerCreateSource
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.platform.node.message.ImportMode
import com.panomc.platform.util.deserializer.BooleanDeserializer
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The backend half of "link an existing server with the Pano Agent" and of automatic node updates:
 * the in-place rules, the new columns, the protocol gate, the config key and the throttle that keeps
 * an automatic update from becoming a restart loop. No database and no Spring context.
 */
class InPlaceServersAndNodeAutoUpdateTest {
    private fun server(kind: ServerKind = ServerKind.MANAGED, inPlace: Boolean = false, directory: String? = null) = Server(
        id = 9,
        name = "smp",
        motd = "",
        host = "127.0.0.1",
        port = 25565,
        playerCount = 0,
        maxPlayerCount = 0,
        type = ServerType.PAPER,
        version = "1.21.8",
        favicon = "",
        status = ServerStatus.OFFLINE,
        startTime = 0,
        aesKey = "secret",
        kind = kind,
        inPlace = inPlace,
        directory = directory
    )

    // ------------------------------------------------------------------------------ in-place rules

    @Test
    fun `reinstalling or changing the software of an in-place server is IN_PLACE_UNSUPPORTED`() {
        val error = assertThrows(InPlaceUnsupported::class.java) {
            InPlaceServerRules.requireReinstallable(server(inPlace = true, directory = "/srv/smp"))
        }

        assertEquals("IN_PLACE_UNSUPPORTED", error.getErrorCode())
        assertEquals(409, error.getStatusCode())

        // An ordinary managed server is not affected.
        InPlaceServerRules.requireReinstallable(server())
    }

    @Test
    fun `removing a server keeps its files only when they were never the node's`() {
        assertTrue(InPlaceServerRules.filesKeptOnRemoval(server(inPlace = true)))
        assertTrue(InPlaceServerRules.filesKeptOnRemoval(server(kind = ServerKind.LINKED)))
        assertFalse(InPlaceServerRules.filesKeptOnRemoval(server()))
    }

    @Test
    fun `the server JSON says where the server lives and never leaks the key`() {
        val json = server(inPlace = true, directory = "/srv/smp").toPublicJsonObject()

        assertEquals(true, json.getBoolean("inPlace"))
        assertEquals("/srv/smp", json.getString("directory"))
        assertFalse(json.containsKey("aesKey"))

        val ordinary = server().toPublicJsonObject()

        assertEquals(false, ordinary.getBoolean("inPlace"))
        assertNull(ordinary.getString("directory"))
    }

    @Test
    fun `a row read back from MariaDB carries the new columns`() {
        val gson = GsonBuilder()
            .registerTypeAdapter(Boolean::class.java, BooleanDeserializer())
            .registerTypeAdapter(java.lang.Boolean::class.java, BooleanDeserializer())
            .create()

        // Only what matters here, the way the MySQL client hands a row over: tinyints for booleans.
        val row = JsonObject()
            .put("id", 9).put("name", "smp").put("motd", "").put("host", "127.0.0.1").put("port", 25565)
            .put("type", "PAPER").put("version", "1.21.8").put("status", "OFFLINE").put("aesKey", "k")
            .put("permissionGranted", 1).put("kind", "MANAGED")
            .put("inPlace", 1).put("directory", "/home/ben/smp")

        val read = gson.fromJson(row.encode(), Server::class.java)

        assertTrue(read.inPlace)
        assertEquals("/home/ben/smp", read.directory)

        val node = gson.fromJson(
            JsonObject()
                .put("id", 3).put("uuid", "n").put("name", "agent").put("kind", "REMOTE").put("aesKey", "k")
                .put("agent", 1)
                .encode(),
            Node::class.java
        )

        assertTrue(node.agent)
    }

    @Test
    fun `the create wizard's IN_PLACE source maps to the node's IN_PLACE import`() {
        assertEquals(ServerCreateSource.IN_PLACE, ServerCreateSource.fromId("in_place"))
        assertEquals("IN_PLACE", ImportMode.IN_PLACE.name)
    }

    // ----------------------------------------------------------------------------- migrations

    @Test
    fun `migrates 52 to 53 with the two server columns and the node's agent flag`() {
        val migration = DatabaseMigration52to53()

        assertEquals(52, migration.from)
        assertEquals(53, migration.to)
        assertEquals(3, migration.handlers.size)
        assertTrue(migration.isMigratable(52))
        assertFalse(migration.isMigratable(51))
    }

    @Test
    fun `config 33 to 34 adds node-auto-update, on, next to what the block already holds`() {
        val config = JsonObject()
            .put("config-version", 33)
            .put("managed-servers", JsonObject().put("plugin-jar-dir", "/dev/plugins"))

        ConfigMigration33To34().migrate(config)

        val block = config.getJsonObject("managed-servers")

        assertEquals(true, block.getBoolean("node-auto-update"))
        assertEquals("/dev/plugins", block.getString("plugin-jar-dir"))

        val migration = ConfigMigration33To34()

        assertEquals(33, migration.from)
        assertEquals(34, migration.to)
    }

    @Test
    fun `config 33 to 34 writes back a hand-removed block and keeps an existing choice`() {
        val missing = JsonObject().put("config-version", 33)

        ConfigMigration33To34().migrate(missing)

        assertEquals(true, missing.getJsonObject("managed-servers").getBoolean("node-auto-update"))
        assertTrue(missing.getJsonObject("managed-servers").containsKey("plugin-jar-dir"))

        val off = JsonObject().put("managed-servers", JsonObject().put("node-auto-update", false))

        ConfigMigration33To34().migrate(off)

        assertEquals(false, off.getJsonObject("managed-servers").getBoolean("node-auto-update"))
    }

    // ---------------------------------------------------------------------------------- protocol

    @Test
    fun `in-place adoption needs protocol 5`() {
        assertEquals(5, NodeProtocol.VERSION)
        assertFalse(NodeProtocol.supportsInPlace(null))
        assertFalse(NodeProtocol.supportsInPlace(4))
        assertTrue(NodeProtocol.supportsInPlace(5))
    }

    // ---------------------------------------------------------------------------------- throttle

    @Test
    fun `an automatic update is tried once per node and jar, then not again for thirty minutes`() {
        val throttle = NodeAutoUpdateThrottle()
        val start = 1_000_000L

        assertTrue(throttle.tryAcquire(1, "abc", start))
        assertFalse(throttle.tryAcquire(1, "abc", start + 1))
        assertFalse(throttle.tryAcquire(1, "ABC", start + 29 * 60 * 1000L), "same digest, other case")

        // Thirty minutes later the same offer may go out once more.
        assertTrue(throttle.tryAcquire(1, "abc", start + NodeAutoUpdateThrottle.DEFAULT_WINDOW_MILLIS))
        assertFalse(throttle.tryAcquire(1, "abc", start + NodeAutoUpdateThrottle.DEFAULT_WINDOW_MILLIS + 1))
    }

    @Test
    fun `a new jar is a new offer and nodes are throttled apart`() {
        val throttle = NodeAutoUpdateThrottle()

        assertTrue(throttle.tryAcquire(1, "old", 0))
        assertTrue(throttle.tryAcquire(1, "new", 1), "a rebuilt or released jar goes out at once")
        assertFalse(throttle.tryAcquire(1, "new", 2))

        assertTrue(throttle.tryAcquire(2, "new", 2), "another node is not held back by the first")

        throttle.forget(1)

        assertTrue(throttle.tryAcquire(1, "new", 3), "a forgotten node starts over")
    }
}
