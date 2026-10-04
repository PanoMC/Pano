package com.panomc.platform.node.message

import com.google.gson.Gson
import com.panomc.platform.node.dto.ScannedPluginData
import com.panomc.platform.node.event.request.ServerProcessMetricsEventRequest
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The node-side half of §2.4.17 B, as it goes on and comes off the wire.
 *
 * Message names are derived from class names, so a rename here renames a wire message and silently
 * stops a daemon from understanding it — these assertions are what makes that a failed build
 * rather than a feature that does nothing in production.
 */
class NodeFallbackMessagesTest {
    @Test
    fun `a console search carries its query to the node`() {
        val json = JsonObject(ConsoleHistoryMessage("abc", 500, 5, "joined").encode())

        assertEquals("CONSOLE_HISTORY", json.getString("event"))
        assertEquals("abc", json.getString("serverUuid"))
        assertEquals(500, json.getInteger("limit"))
        assertEquals(5, json.getInteger("skip"))
        assertEquals("joined", json.getString("query"))

        // And a plain page sends no query at all for the node to act on.
        assertNull(JsonObject(ConsoleHistoryMessage("abc", 500).encode()).getString("query"))
    }

    @Test
    fun `plugin scan goes out as PLUGIN_SCAN`() {
        val json = JsonObject(PluginScanMessage("abc").encode())

        assertEquals("PLUGIN_SCAN", json.getString("event"))
        assertEquals("abc", json.getString("serverUuid"))
    }

    @Test
    fun `plugin toggle carries the file and the wanted state`() {
        val json = JsonObject(PluginToggleMessage("abc", "EssentialsX.jar", false).encode())

        assertEquals("PLUGIN_TOGGLE", json.getString("event"))
        assertEquals("EssentialsX.jar", json.getString("file"))
        assertFalse(json.getBoolean("enabled"))
    }

    @Test
    fun `the plugin state push tells a node what only Pano can see`() {
        val json = JsonObject(ServerPluginStateMessage("abc", true).encode())

        assertEquals("SERVER_PLUGIN_STATE", json.getString("event"))
        assertEquals("abc", json.getString("serverUuid"))
        assertTrue(json.getBoolean("connected"))
    }

    @Test
    fun `a scan reply is read into rows the panel can render`() {
        val payload = JsonObject()
            .put("ok", true)
            .put(
                "plugins",
                JsonArray()
                    .add(
                        JsonObject()
                            .put("file", "EssentialsX.jar")
                            .put("name", "Essentials")
                            .put("version", "2.20.1")
                            .put("main", "com.earth2me.essentials.Essentials")
                            .put("api", "1.20")
                            .put("description", "Essential commands")
                            .put("authors", JsonArray().add("md_5").add(42))
                            .put("enabled", true)
                            .put("kind", "plugin")
                    )
                    .add(JsonObject().put("file", "Old.jar.disabled").put("name", "Old"))
            )

        val plugins = ScannedPluginData.listFrom(payload)

        assertEquals(2, plugins.size)
        assertEquals("Essentials", plugins[0].name)
        assertEquals(listOf("md_5"), plugins[0].authors)
        assertEquals("plugin", plugins[0].kind)
        // No `enabled` key, so the file name decides it -- which is the node's own rule.
        assertFalse(plugins[1].enabled)
    }

    @Test
    fun `an entry with no file is dropped instead of breaking the page`() {
        val payload = JsonObject().put(
            "plugins",
            JsonArray().add(JsonObject().put("name", "Nameless")).add("not an object")
        )

        assertTrue(ScannedPluginData.listFrom(payload).isEmpty())
    }

    @Test
    fun `a scan reply decoded off the wire is read the same as one built by hand`() {
        // Decoding keeps the entries as raw maps, not JsonObjects; the DTO must still see them.
        val payload = JsonObject(
            """{"ok":true,"plugins":[{"file":"LuckPerms.jar","name":"LuckPerms","authors":["Luck"]},""" +
                """{"file":"Other.jar.disabled"}]}"""
        )

        val plugins = ScannedPluginData.listFrom(payload)

        assertEquals(2, plugins.size)
        assertEquals("LuckPerms", plugins[0].name)
        assertEquals(listOf("Luck"), plugins[0].authors)
        assertFalse(plugins[1].enabled)
    }

    @Test
    fun `a scan reply with no plugins at all is an empty list and not a failure`() {
        assertTrue(ScannedPluginData.listFrom(JsonObject().put("ok", true)).isEmpty())
    }

    @Test
    fun `a jar with no descriptor is named after its file`() {
        val plugin = ScannedPluginData.fromJson(JsonObject().put("file", "Mystery.jar"))

        assertEquals("Mystery", plugin?.name)
        assertTrue(plugin?.enabled == true)
    }

    @Test
    fun `the process metrics frame reads either name for the same two numbers`() {
        val new = ServerProcessMetricsEventRequest(cpuPercent = 12.5, rssBytes = 100)

        assertEquals(12.5, new.processCpu)
        assertEquals(100, new.residentBytes)

        val old = ServerProcessMetricsEventRequest(cpu = 3.5, memRss = 200)

        assertEquals(3.5, old.processCpu)
        assertEquals(200, old.residentBytes)
    }

    @Test
    fun `a frame with no ping half is not a server with nobody on it`() {
        val request = ServerProcessMetricsEventRequest(cpuPercent = 1.0, rssBytes = 1)

        assertFalse(request.hasPing)
        assertNull(request.playerCount)

        assertTrue(ServerProcessMetricsEventRequest(playerCount = 0, maxPlayers = 20).hasPing)
    }

    @Test
    fun `the frame carries the size of the server directory when the node has walked it`() {
        val decoded = Gson().fromJson(
            """{"serverUuid":"abc","t":5,"cpuPercent":1.0,"rssBytes":2,"diskBytes":41231953920}""",
            ServerProcessMetricsEventRequest::class.java
        )

        assertEquals(41231953920L, decoded.diskBytes)
    }

    @Test
    fun `a stopped server's frame is a size and nothing else`() {
        val decoded = Gson().fromJson(
            """{"serverUuid":"abc","t":5,"diskBytes":41231953920}""",
            ServerProcessMetricsEventRequest::class.java
        )

        assertTrue(decoded.isDiskOnly)
        assertNull(decoded.processCpu)
        assertFalse(decoded.hasPing)
    }

    @Test
    fun `a frame with traffic in it is a running server's even without a process reading`() {
        assertFalse(ServerProcessMetricsEventRequest(diskBytes = 10, netRxBps = 0).isDiskOnly)
        assertFalse(ServerProcessMetricsEventRequest(diskBytes = 10, netTxBps = 5).isDiskOnly)

        val decoded = Gson().fromJson(
            """{"serverUuid":"abc","cpuPercent":1.0,"rssBytes":2,"netRxBps":5000,"netTxBps":250}""",
            ServerProcessMetricsEventRequest::class.java
        )

        assertEquals(5000L, decoded.netRxBps)
        assertEquals(250L, decoded.netTxBps)
    }

    @Test
    fun `a running server's frame is never mistaken for a stopped one`() {
        // Any one of the three halves is enough: the process reading, the resident memory, or the
        // ping a server with no plugin in it answers.
        assertFalse(ServerProcessMetricsEventRequest(cpuPercent = 0.0, diskBytes = 10).isDiskOnly)
        assertFalse(ServerProcessMetricsEventRequest(rssBytes = 0, diskBytes = 10).isDiskOnly)
        assertFalse(ServerProcessMetricsEventRequest(playerCount = 0, diskBytes = 10).isDiskOnly)
        assertFalse(ServerProcessMetricsEventRequest(cpu = 1.0, memRss = 2, diskBytes = 10).isDiskOnly)
    }

    @Test
    fun `a frame with nothing in it at all is not a disk report either`() {
        assertFalse(ServerProcessMetricsEventRequest(serverUuid = "abc").isDiskOnly)
        assertFalse(ServerProcessMetricsEventRequest(serverUuid = "abc", t = 5).isDiskOnly)
    }

    @Test
    fun `a node that has not measured a directory yet sends no size at all`() {
        // Not a zero: a forty gigabyte world takes minutes to walk, and the first frames after a
        // start (or after a restore threw the figure away) simply have nothing to report.
        val decoded = Gson().fromJson(
            """{"serverUuid":"abc","cpuPercent":1.0,"rssBytes":2}""",
            ServerProcessMetricsEventRequest::class.java
        )

        assertNull(decoded.diskBytes)
    }
}
