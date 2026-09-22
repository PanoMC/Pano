package com.panomc.platform.server

import com.google.gson.Gson
import com.panomc.platform.db.model.Server
import com.panomc.platform.node.event.request.NodeHelloEventRequest
import com.panomc.platform.server.event.request.OnServerConnectEventRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A server's time zone (§2.4.25): only real IANA ids, and the plugin's over the node's.
 */
class ServerTimeZoneTest {
    @Test
    fun `a real IANA id is kept, trimmed`() {
        assertEquals("Europe/Istanbul", ServerTimeZone.normalise("Europe/Istanbul"))
        assertEquals("America/New_York", ServerTimeZone.normalise("  America/New_York "))
        assertEquals("UTC", ServerTimeZone.normalise("UTC"))
        assertEquals("Etc/UTC", ServerTimeZone.normalise("Etc/UTC"))
    }

    @Test
    fun `anything the panel's Intl could not take is dropped`() {
        listOf(null, "", "   ", "Mars/Olympus_Mons", "+03:00", "Z", "GMT+03:00", "europe/istanbul", "x".repeat(100))
            .forEach { assertNull(ServerTimeZone.normalise(it), it.toString()) }
    }

    @Test
    fun `the plugin's zone is taken, and an older plugin's silence keeps what was there`() {
        assertEquals("Europe/Berlin", ServerTimeZone.fromPlugin("Europe/Istanbul", "Europe/Berlin"))
        assertEquals("Europe/Istanbul", ServerTimeZone.fromPlugin("Europe/Istanbul", null))
        assertEquals("Europe/Istanbul", ServerTimeZone.fromPlugin("Europe/Istanbul", "not a zone"))
        assertNull(ServerTimeZone.fromPlugin(null, null))
    }

    @Test
    fun `the node speaks for servers no plugin has ever connected from`() {
        assertEquals("Europe/Istanbul", ServerTimeZone.fromNode(null, null, "Europe/Istanbul"))
        assertEquals("Europe/Istanbul", ServerTimeZone.fromNode("UTC", null, "Europe/Istanbul"), "a host that changed zone")
        assertNull(ServerTimeZone.fromNode("Europe/Istanbul", null, "Europe/Istanbul"), "no change, no write")
    }

    @Test
    fun `once a plugin has spoken the node only fills a gap`() {
        assertNull(ServerTimeZone.fromNode("UTC", "1.0.0", "Europe/Istanbul"), "the container's UTC is the plugin's answer")
        assertEquals("Europe/Istanbul", ServerTimeZone.fromNode(null, "1.0.0", "Europe/Istanbul"))
    }

    @Test
    fun `an invalid zone from a node is no zone at all`() {
        assertNull(ServerTimeZone.fromNode(null, null, "+03:00"))
        assertNull(ServerTimeZone.fromNode(null, null, null))
    }

    @Test
    fun `both peers' frames carry it, and the panel reads it off the server row`() {
        assertEquals("Europe/Istanbul", Gson().fromJson("""{"timeZone":"Europe/Istanbul"}""", NodeHelloEventRequest::class.java).timeZone)

        val connect = Gson().fromJson(
            """{"serverName":"survival","playerCount":0,"maxPlayerCount":20,"serverType":"PAPER","serverVersion":"1.21.8",
               "host":"127.0.0.1","port":25565,"startTime":0,"timeZone":"Europe/Berlin"}""",
            OnServerConnectEventRequest::class.java
        )

        assertEquals("Europe/Berlin", connect.timeZone)

        val server = Server(
            name = "survival", motd = "", host = "127.0.0.1", port = 25565, playerCount = 0, maxPlayerCount = 20,
            type = ServerType.PAPER, version = "1.21.8", favicon = "", permissionGranted = true,
            status = ServerStatus.OFFLINE, startTime = 0, aesKey = "key", timeZone = "Europe/Istanbul"
        )

        assertEquals("Europe/Istanbul", server.toPublicJsonObject().getString("timeZone"))
    }
}
