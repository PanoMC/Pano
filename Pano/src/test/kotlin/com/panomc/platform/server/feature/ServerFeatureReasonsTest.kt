package com.panomc.platform.server.feature

import com.panomc.platform.error.FeatureUnavailable
import com.panomc.platform.error.ServerNoStdin
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerProtocol
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.feature.ServerFeatureReason.NODE_OFFLINE
import com.panomc.platform.server.feature.ServerFeatureReason.NODE_ONLY
import com.panomc.platform.server.feature.ServerFeatureReason.NOT_SUPPORTED
import com.panomc.platform.server.feature.ServerFeatureReason.NO_STDIN
import com.panomc.platform.server.feature.ServerFeatureReason.PLUGIN_LACKS
import com.panomc.platform.server.feature.ServerFeatureReason.PLUGIN_NOT_CONNECTED
import com.panomc.platform.server.feature.ServerFeatureReason.PLUGIN_OUTDATED
import com.panomc.platform.server.feature.ServerFeatureReason.SERVER_RUNNING
import com.panomc.platform.server.feature.ServerFeatureReason.SERVER_STOPPED
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The reason table of §2.4.35 (SM-70), feature by feature.
 *
 * A reason is only worth having if it names what the server actually lacks: "update the plugin"
 * on a server whose node is merely offline, or "start the server" on one that is running, is the
 * very sentence this ticket exists to remove. So every code is pinned to the inputs that produce
 * it, and a sweep over the whole input space proves every empty entry is explained and no served
 * one is.
 */
class ServerFeatureReasonsTest {
    // ---------------------------------------------------------------- NODE_OFFLINE

    @Test
    fun `a managed server whose node is gone explains everything by that`() {
        val reasons = reasonsOf(managed(nodeConnected = false))

        listOf(
            "console.input", "power.start", "power.stop", "power.restart", "power.kill",
            "metrics.memory", "metrics.players", "metrics.host", "players.list", "players.actions",
            "plugins.list", "plugins.toggle", "plugins.install", "plugins.identify",
            "files.source", "files.transfer", "backups.create", "backups.restore"
        ).forEach { assertEquals(NODE_OFFLINE, reasons[it]?.reason, it) }
    }

    @Test
    fun `an offline node leaves the node-only entries to explain when the plugin covers the rest`() {
        val reasons = reasonsOf(
            managed(nodeConnected = false, pluginConnected = true, capabilities = ALL)
        )

        assertEquals(NODE_OFFLINE, reasons["power.start"]?.reason)
        assertEquals(NODE_OFFLINE, reasons["power.kill"]?.reason)
        assertEquals(NODE_OFFLINE, reasons["metrics.host"]?.reason)
        // The plugin serves these, so they have nothing to explain.
        assertNull(reasons["files.source"])
        assertNull(reasons["console.input"])
        assertNull(reasons["metrics.tps"])
    }

    // ---------------------------------------------------------------- SERVER_STOPPED

    @Test
    fun `a stopped managed server asks to be started for everything that needs the game`() {
        val reasons = reasonsOf(managed(processState = ServerProcessState.STOPPED))

        listOf(
            "power.stop", "power.restart", "power.kill",
            "metrics.tps", "metrics.memory", "metrics.players", "players.list", "players.actions"
        ).forEach { assertEquals(SERVER_STOPPED, reasons[it]?.reason, it) }

        // The node takes a command for a stopped process too, so the prompt stays open.
        assertNull(reasons["console.input"])
        // Disk work does not care whether the process runs.
        assertNull(reasons["files.source"])
        assertNull(reasons["backups.create"])
        assertNull(reasons["backups.restore"])
        assertNull(reasons["plugins.list"])
        assertNull(reasons["power.start"])
    }

    @Test
    fun `a crashed or installing server is as stopped as a stopped one`() {
        listOf(ServerProcessState.CRASHED, ServerProcessState.INSTALLING, null).forEach { state ->
            val reasons = reasonsOf(managed(processState = state))

            assertEquals(SERVER_STOPPED, reasons["metrics.memory"]?.reason, "$state")
            assertEquals(SERVER_STOPPED, reasons["players.list"]?.reason, "$state")
        }
    }

    @Test
    fun `a linked server with no plugin connected asks to be started for the live parts`() {
        val reasons = reasonsOf(linked(pluginConnected = false))

        listOf(
            "console.input", "power.stop", "power.restart",
            "metrics.tps", "metrics.memory", "metrics.players", "players.list", "players.actions"
        ).forEach { assertEquals(SERVER_STOPPED, reasons[it]?.reason, it) }
    }

    // ---------------------------------------------------------------- PLUGIN_NOT_CONNECTED

    @Test
    fun `a linked server with no plugin connected names the plugin for the disk parts`() {
        val reasons = reasonsOf(linked(pluginConnected = false))

        assertEquals(unavailable(PLUGIN_NOT_CONNECTED, ServerCapability.FILES), reasons["files.source"])
        assertEquals(unavailable(PLUGIN_NOT_CONNECTED, ServerCapability.FILES), reasons["files.transfer"])
        assertEquals(unavailable(PLUGIN_NOT_CONNECTED, ServerCapability.BACKUPS), reasons["backups.create"])
        assertEquals(unavailable(PLUGIN_NOT_CONNECTED, ServerCapability.BACKUPS), reasons["backups.restore"])
        assertEquals(unavailable(PLUGIN_NOT_CONNECTED, ServerCapability.PLUGINS), reasons["plugins.list"])
        assertEquals(unavailable(PLUGIN_NOT_CONNECTED, ServerCapability.PLUGINS), reasons["plugins.toggle"])
        assertEquals(
            unavailable(PLUGIN_NOT_CONNECTED, ServerCapability.PLUGIN_INSTALL),
            reasons["plugins.install"]
        )
    }

    @Test
    fun `a running managed server without the plugin has no tick counter to offer`() {
        val reasons = reasonsOf(managed())

        assertEquals(unavailable(PLUGIN_NOT_CONNECTED, ServerCapability.METRICS), reasons["metrics.tps"])
    }

    // ---------------------------------------------------------------- PLUGIN_OUTDATED

    @Test
    fun `a plugin from before the capability handshake is outdated, not lacking`() {
        val reasons = reasonsOf(
            linked(pluginProtocol = ServerProtocol.LEGACY_PROTOCOL_VERSION)
        )

        assertEquals(unavailable(PLUGIN_OUTDATED, ServerCapability.FILES), reasons["files.source"])
        assertEquals(unavailable(PLUGIN_OUTDATED, ServerCapability.BACKUPS), reasons["backups.create"])
        assertEquals(unavailable(PLUGIN_OUTDATED, ServerCapability.COMMANDS), reasons["console.input"])
        assertEquals(unavailable(PLUGIN_OUTDATED, ServerCapability.METRICS), reasons["metrics.tps"])
        assertEquals(unavailable(PLUGIN_OUTDATED, ServerCapability.PLAYERS), reasons["players.list"])
        assertEquals(unavailable(PLUGIN_OUTDATED, ServerCapability.POWER), reasons["power.stop"])
        // Updating the plugin would not bring these, so they are not blamed on it.
        assertEquals(NODE_ONLY, reasons["power.start"]?.reason)
    }

    // ---------------------------------------------------------------- PLUGIN_LACKS

    @Test
    fun `a current plugin that does not announce a capability lacks exactly that one`() {
        val reasons = reasonsOf(linked(capabilities = setOf(ServerCapability.CONSOLE, ServerCapability.COMMANDS)))

        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.FILES), reasons["files.source"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.FILES), reasons["files.transfer"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.BACKUPS), reasons["backups.create"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.BACKUPS), reasons["backups.restore"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.PLUGINS), reasons["plugins.list"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.PLUGIN_INSTALL), reasons["plugins.install"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.PLUGIN_INSTALL), reasons["plugins.identify"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.METRICS), reasons["metrics.tps"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.METRICS), reasons["metrics.memory"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.PLAYERS), reasons["players.list"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.POWER), reasons["power.stop"])
        // Announced, therefore served, therefore nothing to explain.
        assertNull(reasons["console.input"])
        assertNull(reasons["players.actions"])
    }

    @Test
    fun `a current plugin that announces nothing at all lacks, it is not outdated`() {
        val reasons = reasonsOf(linked(capabilities = emptySet()))

        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.COMMANDS), reasons["console.input"])
        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.FILES), reasons["files.source"])
    }

    @Test
    fun `a managed server's own plugin can lack the one thing only it could do`() {
        val reasons = reasonsOf(managed(pluginConnected = true, capabilities = setOf(ServerCapability.CONSOLE)))

        assertEquals(unavailable(PLUGIN_LACKS, ServerCapability.METRICS), reasons["metrics.tps"])
    }

    // ---------------------------------------------------------------- NODE_ONLY

    @Test
    fun `a linked server can never start, kill or report its host`() {
        listOf(true, false).forEach { connected ->
            val reasons = reasonsOf(linked(pluginConnected = connected))

            assertEquals(NODE_ONLY, reasons["power.start"]?.reason, "connected=$connected")
            assertEquals(NODE_ONLY, reasons["power.kill"]?.reason, "connected=$connected")
            assertEquals(NODE_ONLY, reasons["metrics.host"]?.reason, "connected=$connected")
            assertNull(reasons["power.start"]?.capability)
        }
    }

    @Test
    fun `a linked server with a complete plugin only misses what a node would add`() {
        val reasons = reasonsOf(linked())

        assertEquals(setOf("power.start", "power.kill", "metrics.host"), reasons.keys)
    }

    // ---------------------------------------------------------------- NOT_SUPPORTED

    @Test
    fun `vanilla has no plugins for anyone, node or not`() {
        listOf(true, false).forEach { nodeConnected ->
            val reasons = reasonsOf(managed(nodeConnected = nodeConnected, type = ServerType.VANILLA))

            listOf("plugins.list", "plugins.toggle", "plugins.install", "plugins.identify").forEach {
                assertEquals(NOT_SUPPORTED, reasons[it]?.reason, "$it nodeConnected=$nodeConnected")
            }
        }
    }

    @Test
    fun `a proxy counts no ticks even when its plugin announces metrics`() {
        val linkedProxy = ServerFeatureResolver.resolve(linked(type = ServerType.VELOCITY))
        val managedProxy = ServerFeatureResolver.resolve(
            managed(pluginConnected = true, capabilities = ALL, type = ServerType.BUNGEECORD)
        )

        assertNull(linkedProxy.metrics.tps)
        assertEquals(NOT_SUPPORTED, linkedProxy.reasons["metrics.tps"]?.reason)
        // Memory and players are still the plugin's to report.
        assertEquals(ServerFeatureSource.PLUGIN, linkedProxy.metrics.memory)
        assertNull(managedProxy.metrics.tps)
        assertEquals(NOT_SUPPORTED, managedProxy.reasons["metrics.tps"]?.reason)
    }

    @Test
    fun `software no Pano plugin exists for has no tick counter`() {
        listOf(ServerType.FORGE, ServerType.NEOFORGE, ServerType.VANILLA).forEach { type ->
            assertEquals(NOT_SUPPORTED, reasonsOf(managed(type = type))["metrics.tps"]?.reason, "$type")
        }
    }

    @Test
    fun `a plugin toggle outside the Bukkit family with no node to rename the jar is not supported`() {
        val reasons = reasonsOf(linked(type = ServerType.VELOCITY))

        assertEquals(NOT_SUPPORTED, reasons["plugins.toggle"]?.reason)
        assertNull(reasons["plugins.list"])
    }

    // ---------------------------------------------------------------- SERVER_RUNNING / NO_STDIN

    @Test
    fun `a running server with no plugin to leave a marker restores once stopped`() {
        assertEquals(SERVER_RUNNING, reasonsOf(managed())["backups.restore"]?.reason)
        assertEquals(
            SERVER_RUNNING,
            reasonsOf(managed(pluginConnected = true, capabilities = setOf(ServerCapability.CONSOLE)))
                ["backups.restore"]?.reason
        )
        assertNull(reasonsOf(managed(pluginConnected = true, capabilities = ALL))["backups.restore"])
    }

    @Test
    fun `an adopted process with no plugin to relay has lost its stdin`() {
        val reasons = reasonsOf(managed(stdinAvailable = false))

        assertEquals(NO_STDIN, reasons["console.input"]?.reason)
        assertEquals(NO_STDIN, reasons["players.actions"]?.reason)
        // Reading the console never needed the pipe.
        assertNull(reasons["console.stream"])
    }

    @Test
    fun `a stopped server with a stale stdin flag asks to be started, not restarted`() {
        val reasons = reasonsOf(managed(stdinAvailable = false, processState = ServerProcessState.STOPPED))

        assertEquals(SERVER_STOPPED, reasons["console.input"]?.reason)
    }

    // ---------------------------------------------------------------- the served are never explained

    @Test
    fun `start on a running server has nothing to do, which is not a missing feature`() {
        val features = ServerFeatureResolver.resolve(managed())

        assertFalse(features.power.start)
        assertNull(features.reasons["power.start"])
    }

    @Test
    fun `a managed server with a node and a complete plugin explains nothing but start`() {
        val reasons = reasonsOf(managed(pluginConnected = true, capabilities = ALL))

        assertTrue(reasons.isEmpty(), "unexpected reasons: $reasons")
    }

    @Test
    fun `every empty entry is explained and every served one is not, across the whole input space`() {
        var checked = 0

        inputSpace().forEach { inputs ->
            val features = ServerFeatureResolver.resolve(inputs)

            assertTrue(ServerFeatures.ENTRY_IDS.containsAll(features.reasons.keys))

            ServerFeatures.ENTRY_IDS.forEach { id ->
                val available = features.isAvailable(id)
                val reason = features.reasons[id]
                val alreadyStarted = id == ServerFeatures.POWER_START && inputs.nodeAvailable &&
                    inputs.processAlive

                when {
                    available -> assertNull(reason, "$id is served but explained as $reason for $inputs")
                    alreadyStarted -> assertNull(reason, "start on a live process is not unavailable")
                    else -> assertTrue(reason != null, "$id is unavailable with no reason for $inputs")
                }

                if (reason != null && reason.reason in PLUGIN_REASONS) {
                    assertTrue(reason.capability != null, "$id ${reason.reason} names no capability")
                }

                checked++
            }
        }

        assertTrue(checked > 10_000, "the sweep covered only $checked cases")
    }

    // ---------------------------------------------------------------- pick() and the 409

    @Test
    fun `a refusal carries the reason and the capability the notice already shows`() {
        val error = assertThrows<FeatureUnavailable> {
            ServerFeatureResolver.pick(linked(capabilities = setOf(ServerCapability.CONSOLE)), ServerFeature.FILES_SOURCE)
        }
        val body = JsonObject(error.encode(emptyMap()))

        assertEquals(409, error.getStatusCode())
        assertEquals("FEATURE_UNAVAILABLE", body.getString("error"))
        assertEquals("files.source", body.getString("feature"))
        assertEquals("PLUGIN_LACKS", body.getString("reason"))
        assertEquals("files", body.getString("capability"))
    }

    @Test
    fun `a refusal that is not about the plugin carries no capability`() {
        val error = assertThrows<FeatureUnavailable> {
            ServerFeatureResolver.pick(managed(nodeConnected = false), ServerFeature.BACKUPS_CREATE)
        }
        val body = JsonObject(error.encode(emptyMap()))

        assertEquals("NODE_OFFLINE", body.getString("reason"))
        assertFalse(body.containsKey("capability"))
    }

    @Test
    fun `every refusal pick makes agrees with the reasons map`() {
        inputSpace().forEach { inputs ->
            val features = ServerFeatureResolver.resolve(inputs)

            ServerFeature.entries.filter { features.sourceOf(it) == null }.forEach { feature ->
                val expected = features.reasons.getValue(feature.id)
                val error = runCatching { ServerFeatureResolver.pick(inputs, feature) }.exceptionOrNull()
                val body = JsonObject((error as com.panomc.platform.model.Error).encode(emptyMap()))

                assertEquals(feature.id, body.getString("feature"))
                assertEquals(expected.reason.name, body.getString("reason"))
                assertEquals(expected.capability?.id, body.getString("capability"))
            }
        }
    }

    @Test
    fun `the lost stdin keeps its own code, with the reason alongside`() {
        val error = assertThrows<ServerNoStdin> {
            ServerFeatureResolver.pick(managed(stdinAvailable = false), ServerFeature.CONSOLE_INPUT)
        }
        val body = JsonObject(error.encode(emptyMap()))

        assertEquals("NO_STDIN", body.getString("reason"))
        assertEquals("console.input", body.getString("feature"))
    }

    @Test
    fun `player actions without stdin are an ordinary refusal with the stdin reason`() {
        val error = assertThrows<FeatureUnavailable> {
            ServerFeatureResolver.pick(managed(stdinAvailable = false), ServerFeature.PLAYERS_ACTIONS)
        }

        assertEquals("NO_STDIN", JsonObject(error.encode(emptyMap())).getString("reason"))
    }

    @Test
    fun `a stopped server with a stale stdin flag is refused as stopped`() {
        val error = assertThrows<FeatureUnavailable> {
            ServerFeatureResolver.pick(
                managed(stdinAvailable = false, processState = ServerProcessState.STOPPED),
                ServerFeature.CONSOLE_INPUT
            )
        }

        assertEquals("SERVER_STOPPED", JsonObject(error.encode(emptyMap())).getString("reason"))
    }

    // ---------------------------------------------------------------- JSON shape

    @Test
    fun `reasons travel as a flat map of dotted ids to codes`() {
        val json = ServerFeatureResolver.resolve(linked(capabilities = setOf(ServerCapability.CONSOLE)))
            .toJsonObject()
        val reasons = json.getJsonObject("reasons")

        assertEquals("PLUGIN_LACKS", reasons.getString("files.source"))
        assertEquals("NODE_ONLY", reasons.getString("power.start"))
        assertEquals("PLUGIN_LACKS", reasons.getString("plugins.toggle"))
        // A served entry is absent rather than null, so `reasons[path]` is the whole question.
        assertFalse(reasons.containsKey("console.stream"))
        assertFalse(reasons.containsKey("schedules.runner"))
    }

    @Test
    fun `a fully served server sends an empty reasons object, not a missing one`() {
        val json = ServerFeatureResolver.resolve(managed(pluginConnected = true, capabilities = ALL)).toJsonObject()

        assertTrue(json.containsKey("reasons"))
        assertTrue(json.getJsonObject("reasons").isEmpty)
    }

    // ---------------------------------------------------------------- helpers

    private fun reasonsOf(inputs: ServerFeatureInputs) = ServerFeatureResolver.resolve(inputs).reasons

    private fun unavailable(reason: ServerFeatureReason, capability: ServerCapability? = null) =
        ServerFeatureUnavailability(reason, capability)

    /** A managed server on a connected node with a running process and no plugin, unless told otherwise. */
    private fun managed(
        nodeConnected: Boolean = true,
        stdinAvailable: Boolean = true,
        processState: ServerProcessState? = ServerProcessState.RUNNING,
        pluginConnected: Boolean = false,
        capabilities: Set<ServerCapability> = emptySet(),
        type: ServerType = ServerType.PAPER
    ) = ServerFeatureInputs.of(
        kind = ServerKind.MANAGED,
        nodeConnected = nodeConnected,
        stdinAvailable = stdinAvailable,
        processState = processState,
        pluginConnected = pluginConnected,
        capabilities = capabilities,
        type = type
    )

    /** A linked server whose current plugin is connected and announces everything, unless told otherwise. */
    private fun linked(
        pluginConnected: Boolean = true,
        capabilities: Set<ServerCapability> = ALL,
        type: ServerType = ServerType.PAPER,
        pluginProtocol: Int = ServerProtocol.CURRENT_PROTOCOL_VERSION
    ) = ServerFeatureInputs.of(
        kind = ServerKind.LINKED,
        pluginConnected = pluginConnected,
        // A legacy plugin announces nothing, whatever the test would like it to have.
        capabilities = if (pluginProtocol <= ServerProtocol.LEGACY_PROTOCOL_VERSION) emptySet() else capabilities,
        type = type,
        pluginProtocol = pluginProtocol
    )

    /** Every combination of inputs that can be told apart, a few capability sets deep. */
    private fun inputSpace(): Sequence<ServerFeatureInputs> = sequence {
        val capabilitySets = listOf(
            emptySet(),
            ALL,
            setOf(ServerCapability.CONSOLE, ServerCapability.COMMANDS),
            setOf(ServerCapability.METRICS, ServerCapability.PLAYERS, ServerCapability.PLUGINS),
            setOf(ServerCapability.FILES, ServerCapability.BACKUPS, ServerCapability.PLUGIN_INSTALL)
        )
        val types = listOf(ServerType.PAPER, ServerType.VELOCITY, ServerType.FABRIC, ServerType.VANILLA, ServerType.FORGE)
        val states = listOf(null) + ServerProcessState.entries

        for (kind in ServerKind.entries)
            for (nodeConnected in listOf(true, false))
                for (stdin in listOf(true, false))
                    for (state in states)
                        for (pluginConnected in listOf(true, false))
                            for (protocol in listOf(ServerProtocol.LEGACY_PROTOCOL_VERSION, ServerProtocol.CURRENT_PROTOCOL_VERSION))
                                for (capabilities in capabilitySets)
                                    for (type in types)
                                        yield(
                                            ServerFeatureInputs.of(
                                                kind = kind,
                                                nodeConnected = nodeConnected,
                                                stdinAvailable = stdin,
                                                processState = state,
                                                pluginConnected = pluginConnected,
                                                capabilities = capabilities,
                                                type = type,
                                                pluginProtocol = protocol
                                            )
                                        )
    }

    private companion object {
        val ALL: Set<ServerCapability> = ServerCapability.entries.toSet()

        val PLUGIN_REASONS = setOf(PLUGIN_NOT_CONNECTED, PLUGIN_OUTDATED, PLUGIN_LACKS)
    }
}
