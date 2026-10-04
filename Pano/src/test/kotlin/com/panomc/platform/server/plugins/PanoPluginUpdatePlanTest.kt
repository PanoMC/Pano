package com.panomc.platform.server.plugins

import com.panomc.platform.node.dto.ScannedPluginData
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.plugins.PanoPluginUpdatePlan.Candidate
import com.panomc.platform.server.plugins.PanoPluginUpdatePlan.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The decisions behind "update the Pano plugin": which route a server takes, which servers "update
 * all" touches, and which jar in a plugin directory is the one being replaced.
 */
class PanoPluginUpdatePlanTest {
    @Test
    fun `a managed server with its node online goes through the node, whatever the plugin says`() {
        assertEquals(Mode.NODE, PanoPluginUpdatePlan.modeFor(managed = true, nodeConnected = true, pluginConnected = true, pluginCanSelfUpdate = true))
        assertEquals(Mode.NODE, PanoPluginUpdatePlan.modeFor(managed = true, nodeConnected = true, pluginConnected = false, pluginCanSelfUpdate = false))
    }

    @Test
    fun `the plugin updates itself only when it is connected and announces self-update`() {
        // A linked server, and a managed one whose node is away: the plugin is the only way in.
        assertEquals(Mode.PLUGIN, PanoPluginUpdatePlan.modeFor(managed = false, nodeConnected = false, pluginConnected = true, pluginCanSelfUpdate = true))
        assertEquals(Mode.PLUGIN, PanoPluginUpdatePlan.modeFor(managed = true, nodeConnected = false, pluginConnected = true, pluginCanSelfUpdate = true))

        assertNull(PanoPluginUpdatePlan.modeFor(managed = false, nodeConnected = false, pluginConnected = true, pluginCanSelfUpdate = false))
        assertNull(PanoPluginUpdatePlan.modeFor(managed = false, nodeConnected = false, pluginConnected = false, pluginCanSelfUpdate = true))
        assertNull(PanoPluginUpdatePlan.modeFor(managed = true, nodeConnected = false, pluginConnected = false, pluginCanSelfUpdate = false))

        // A linked row never has a node, so a connected flag for one cannot make it take that route.
        assertNull(PanoPluginUpdatePlan.modeFor(managed = false, nodeConnected = true, pluginConnected = false, pluginCanSelfUpdate = false))
    }

    @Test
    fun `a refusal names the thing that is missing`() {
        assertEquals(PanoPluginUpdatePlan.REASON_NO_PLUGIN_MODULE, PanoPluginUpdatePlan.refusalFor(ServerType.VANILLA, managed = true, pluginConnected = false))
        assertEquals(PanoPluginUpdatePlan.REASON_NO_PLUGIN_MODULE, PanoPluginUpdatePlan.refusalFor(ServerType.FORGE, managed = true, pluginConnected = true))
        assertEquals(PanoPluginUpdatePlan.REASON_PLUGIN_TOO_OLD, PanoPluginUpdatePlan.refusalFor(ServerType.PAPER, managed = false, pluginConnected = true))
        assertEquals(PanoPluginUpdatePlan.REASON_NODE_OFFLINE, PanoPluginUpdatePlan.refusalFor(ServerType.PAPER, managed = true, pluginConnected = false))
        assertEquals(PanoPluginUpdatePlan.REASON_SERVER_OFFLINE, PanoPluginUpdatePlan.refusalFor(ServerType.VELOCITY, managed = false, pluginConnected = false))
    }

    @Test
    fun `only a definite newer release is an update`() {
        assertTrue(PanoPluginUpdatePlan.needsUpdate("1.0.0-alpha.62", "1.0.0-alpha.63"))
        assertFalse(PanoPluginUpdatePlan.needsUpdate("1.0.0-alpha.63", "1.0.0-alpha.63"))
        assertFalse(PanoPluginUpdatePlan.needsUpdate("1.0.0-alpha.64", "1.0.0-alpha.63"))

        // Every "maybe" is a no: development jars, no version reported, nothing looked up yet.
        assertFalse(PanoPluginUpdatePlan.needsUpdate("local-build", "1.0.0-alpha.63"))
        assertFalse(PanoPluginUpdatePlan.needsUpdate("1.0.0-alpha.62", "local-build"))
        assertFalse(PanoPluginUpdatePlan.needsUpdate(null, "1.0.0-alpha.63"))
        assertFalse(PanoPluginUpdatePlan.needsUpdate("1.0.0-alpha.62", null))
    }

    @Test
    fun `update all picks exactly the servers behind the newest build of their own module`() {
        val latest = mapOf(
            ServerType.PAPER to "1.0.0-alpha.63",
            ServerType.FOLIA to "1.0.0-alpha.63",
            ServerType.VELOCITY to "1.0.0-alpha.63",
            // A release that shipped no Fabric jar: its servers have nothing newer to get.
            ServerType.FABRIC to null
        )

        val candidates = listOf(
            Candidate(1, ServerType.PAPER, "1.0.0-alpha.62"),
            Candidate(2, ServerType.PAPER, "1.0.0-alpha.63"),
            Candidate(3, ServerType.FOLIA, "1.0.0-alpha.60"),
            Candidate(4, ServerType.VELOCITY, null),
            Candidate(5, ServerType.FABRIC, "1.0.0-alpha.1"),
            Candidate(6, ServerType.VANILLA, "1.0.0-alpha.1"),
            Candidate(7, ServerType.VELOCITY, "local-build")
        )

        val due = PanoPluginUpdatePlan.serversNeedingUpdate(candidates) { latest[it] }

        assertEquals(listOf(1L, 3L), due.map { it.serverId })
    }

    @Test
    fun `the jar being replaced is found by its descriptor name first`() {
        val scanned = listOf(
            scanned("EssentialsX-2.21.0.jar", "Essentials"),
            // Renamed by hand, but still the Pano plugin as far as the server is concerned.
            scanned("my-renamed-link.jar", "Pano"),
            scanned("pano-spigot-1.0.0-alpha.60.jar", "Something else")
        )

        assertEquals("my-renamed-link.jar", PanoPluginUpdatePlan.panoJarIn(scanned, "spigot"))
    }

    @Test
    fun `an unreadable descriptor falls back to this platform's asset name or pano jar only`() {
        // The node names an unreadable jar after its file, which is what these entries look like.
        assertEquals(
            "pano-velocity-1.0.0-alpha.62.jar",
            PanoPluginUpdatePlan.panoJarIn(
                listOf(
                    scanned("pano-limbo-auth-1.2.0.jar", "pano-limbo-auth-1.2.0"),
                    scanned("pano-velocity-1.0.0-alpha.62.jar", "pano-velocity-1.0.0-alpha.62")
                ),
                "velocity"
            )
        )

        assertEquals("pano.jar", PanoPluginUpdatePlan.panoJarIn(listOf(scanned("pano.jar", "pano")), "fabric"))

        // Another platform's asset and an unrelated pano-* jar are never mistaken for this one.
        assertNull(
            PanoPluginUpdatePlan.panoJarIn(
                listOf(
                    scanned("pano-limbo-auth-1.2.0.jar", "pano-limbo-auth-1.2.0"),
                    scanned("pano-spigot-1.0.0-alpha.62.jar", "pano-spigot-1.0.0-alpha.62")
                ),
                "velocity"
            )
        )
    }

    @Test
    fun `a disabled jar is not the running plugin and is left alone`() {
        val scanned = listOf(
            scanned("pano-spigot-1.0.0-alpha.50.jar.disabled", "Pano", enabled = false),
            scanned("pano-spigot-1.0.0-alpha.62.jar", "Pano")
        )

        assertEquals("pano-spigot-1.0.0-alpha.62.jar", PanoPluginUpdatePlan.panoJarIn(scanned, "spigot"))
        assertNull(PanoPluginUpdatePlan.panoJarIn(scanned.take(1), "spigot"))
        assertNull(PanoPluginUpdatePlan.panoJarIn(emptyList(), "spigot"))
    }

    @Test
    fun `the routes keep the wire names the panel reads`() {
        assertEquals("node", Mode.NODE.wire)
        assertEquals("plugin", Mode.PLUGIN.wire)
    }

    @Test
    fun `a hand update is offered only where the admin is the one who can place the jar`() {
        // Linked, plugin too old to replace itself or not connected: the admin's hands only.
        assertTrue(PanoPluginUpdatePlan.canUpdateByHand(PanoPluginUpdatePlan.REASON_PLUGIN_TOO_OLD, managed = false))
        assertTrue(PanoPluginUpdatePlan.canUpdateByHand(PanoPluginUpdatePlan.REASON_SERVER_OFFLINE, managed = false))

        // Managed: the node installs it once it is back; a hand-placed jar would race it.
        assertFalse(PanoPluginUpdatePlan.canUpdateByHand(PanoPluginUpdatePlan.REASON_PLUGIN_TOO_OLD, managed = true))
        assertFalse(PanoPluginUpdatePlan.canUpdateByHand(PanoPluginUpdatePlan.REASON_NODE_OFFLINE, managed = true))

        // Nothing to download, or nothing to do.
        assertFalse(PanoPluginUpdatePlan.canUpdateByHand(PanoPluginUpdatePlan.REASON_NO_PLUGIN_MODULE, managed = false))
        assertFalse(PanoPluginUpdatePlan.canUpdateByHand(PanoPluginUpdatePlan.REASON_UP_TO_DATE, managed = false))
    }

    private fun scanned(file: String, name: String, enabled: Boolean = true) =
        ScannedPluginData(file = file, name = name, enabled = enabled)
}
