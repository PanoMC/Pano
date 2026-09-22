package com.panomc.platform.node

import com.panomc.platform.config.PanoConfig
import com.panomc.platform.config.migration.ConfigMigration34To35
import com.panomc.platform.route.api.panel.server.PanelToggleServerAgentLinkAPI
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The "Link with the Pano Agent" switch (SM-77, `managed-servers.accept-agent-links`): off drops
 * every live agent code and refuses any agent code like a wrong one, the dialog is answered without
 * anything that pairs, and node pairing is not touched.
 */
class AgentLinkSwitchTest {
    private val vertx = Vertx.vertx()

    @AfterEach
    fun close() {
        vertx.close()
    }

    @Test
    fun `turning agent links off drops every live code, and turning them on brings none back`() {
        val codes = NodePairingCodeManager(vertx)
        val managedServers = PanoConfig.Companion.ManagedServersConfig()
        val now = System.currentTimeMillis()

        assertTrue(managedServers.acceptAgentLinks, "on by default")

        val first = codes.agentCodeFor(userId = 1, now = now)
        val second = codes.agentCodeFor(userId = 2, now = now)

        assertEquals(2, codes.liveAgentCodes(now))

        assertFalse(PanelToggleServerAgentLinkAPI.toggle(managedServers, codes))
        assertFalse(managedServers.acceptAgentLinks)
        assertEquals(0, codes.liveAgentCodes(now))

        assertTrue(PanelToggleServerAgentLinkAPI.toggle(managedServers, codes))
        assertTrue(managedServers.acceptAgentLinks)

        // Back on: the commands that were on screen when it went off still pair nothing.
        assertNull(codes.takeAgentCode(first.code, now, acceptAgentLinks = true))
        assertNull(codes.takeAgentCode(second.code, now, acceptAgentLinks = true))

        // A fresh code works again.
        val fresh = codes.agentCodeFor(userId = 1, now = now)

        assertNotEquals(first.code, fresh.code)
        assertEquals(fresh, codes.takeAgentCode(fresh.code, now, acceptAgentLinks = true))
    }

    @Test
    fun `while off an agent code is no code at all, and the node code still pairs`() {
        val codes = NodePairingCodeManager(vertx)
        val now = System.currentTimeMillis()

        // Minted while on, then the switch went off some other way (config.conf edited by hand).
        val minted = codes.agentCodeFor(userId = 5, now = now)

        assertNull(codes.takeAgentCode(minted.code, now, acceptAgentLinks = false), "refused")
        assertEquals(0, codes.liveAgentCodes(now), "and gone, not kept for later")
        assertNull(codes.takeAgentCode(minted.code, now, acceptAgentLinks = true))

        // Exactly what a code that never existed gets, which the connect endpoint turns into the
        // wrong-code error: an agent code never matches the node code.
        assertNull(codes.takeAgentCode("nosuchcodeatall2", now, acceptAgentLinks = false))
        assertFalse(codes.matches(minted.code))

        // Node pairing is not part of the switch.
        assertTrue(codes.matches(codes.getPairingCode().toString()))
        assertNull(codes.takeAgentCode(codes.getPairingCode().toString(), now, acceptAgentLinks = false))
        assertTrue(codes.matches(codes.getPairingCode().toString()))
    }

    @Test
    fun `dropping codes reports how many went`() {
        val codes = NodePairingCodeManager(vertx)

        codes.agentCodeFor(userId = 1)
        codes.agentCodeFor(userId = 2)
        codes.agentCodeFor(userId = 3)

        assertEquals(3, codes.dropAgentCodes())
        assertEquals(0, codes.dropAgentCodes())
    }

    @Test
    fun `the dialog while off gets the jar and how to start it, and nothing that pairs`() {
        val disabled = NodeInstallScriptProvider.agentLinkDisabled("https://pano.example.com/api/node/pano-agent.jar")

        assertEquals(
            mapOf(
                "enabled" to false,
                "jarUrl" to "https://pano.example.com/api/node/pano-agent.jar",
                "jarFileName" to "pano-agent.jar",
                "startCommand" to "java -jar pano-agent.jar",
                "javaVersion" to 17
            ),
            disabled
        )

        listOf("code", "expiresAt", "runCommand", "downloadCommand", "downloadCommandWindows", "panoUrl").forEach {
            assertFalse(disabled.containsKey(it), it)
        }

        val enabled = NodeInstallScriptProvider.agentLink(
            code = "abcdefghjkmnpqrs",
            expiresAt = 42L,
            panoUrl = "https://pano.example.com",
            jarUrl = "https://pano.example.com/api/node/pano-agent.jar"
        )

        assertEquals(true, enabled["enabled"])
        assertNotNull(enabled["code"])

        // Everything the disabled answer has, the enabled one has with the same value.
        disabled.filterKeys { it != "enabled" }.forEach { (key, value) -> assertEquals(value, enabled[key], key) }
    }

    @Test
    fun `the latest daemon version is Pano's release, and unknown for a development build`() {
        assertEquals("1.2.0", NodeInstallScriptProvider.releaseVersion("1.2.0"))
        assertEquals("1.0.0-beta.34", NodeInstallScriptProvider.releaseVersion("1.0.0-beta.34"))

        listOf(null, "", " ", "null", "local-build", "LOCAL-BUILD").forEach {
            assertNull(NodeInstallScriptProvider.releaseVersion(it), "$it")
        }
    }

    @Test
    fun `config 34 to 35 adds accept-agent-links, on, and keeps what the block holds`() {
        val config = JsonObject()
            .put("config-version", 34)
            .put("managed-servers", JsonObject().put("plugin-jar-dir", "/dev/plugins").put("node-auto-update", false))

        ConfigMigration34To35().migrate(config)

        val block = config.getJsonObject("managed-servers")

        assertEquals(true, block.getBoolean("accept-agent-links"))
        assertEquals("/dev/plugins", block.getString("plugin-jar-dir"))
        assertEquals(false, block.getBoolean("node-auto-update"))

        val migration = ConfigMigration34To35()

        assertEquals(34, migration.from)
        assertEquals(35, migration.to)
        assertTrue(migration.isMigratable(34))

        val missing = JsonObject().put("config-version", 34)

        ConfigMigration34To35().migrate(missing)

        val written = missing.getJsonObject("managed-servers")

        assertEquals(true, written.getBoolean("accept-agent-links"))
        assertEquals(true, written.getBoolean("node-auto-update"))
        assertTrue(written.containsKey("plugin-jar-dir"))

        val off = JsonObject().put("managed-servers", JsonObject().put("accept-agent-links", false))

        ConfigMigration34To35().migrate(off)

        assertEquals(false, off.getJsonObject("managed-servers").getBoolean("accept-agent-links"))
    }
}
