package com.panomc.platform.server.feature

import com.panomc.platform.db.model.Server
import com.panomc.platform.error.FeatureUnavailable
import com.panomc.platform.error.ServerNoStdin
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.feature.ServerFeatureSource.NODE
import com.panomc.platform.server.feature.ServerFeatureSource.PANO
import com.panomc.platform.server.feature.ServerFeatureSource.PLUGIN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The whole preference table of §2.4.17 A, case by case.
 *
 * Resolution is pure, so every combination that matters can be stated as a fact here rather than
 * discovered in production: the failure mode this guards against is a control that looks enabled
 * and does nothing, which nothing downstream would catch.
 */
class ServerFeatureResolverTest {
    // ---------------------------------------------------------------- node only, no plugin

    @Test
    fun `a managed server with no plugin is served entirely by its node`() {
        val features = ServerFeatureResolver.resolve(nodeOnly())

        assertEquals(NODE, features.console.stream)
        assertEquals(NODE, features.console.history)
        assertEquals(NODE, features.console.input)
        assertEquals(NODE, features.power.stop)
        assertEquals(NODE, features.power.restart)
        assertFalse(features.power.start)
        assertTrue(features.power.kill)
        assertEquals(NODE, features.plugins.list)
        assertEquals(NODE, features.plugins.toggle)
        assertEquals(NODE, features.plugins.install)
        assertEquals(NODE, features.plugins.identify)
        assertEquals(NODE, features.files.source)
        assertEquals(NODE, features.files.transfer)
        assertEquals(NODE, features.backups.create)
        assertEquals(NODE, features.schedules.runner)
        assertTrue(features.metrics.host)
    }

    @Test
    fun `a node cannot count ticks, but it can still report memory and players`() {
        val features = ServerFeatureResolver.resolve(nodeOnly())

        assertNull(features.metrics.tps)
        assertEquals(NODE, features.metrics.memory)
        assertEquals(NODE, features.metrics.players)
        assertEquals(NODE, features.players.list)
        assertEquals(ServerFeatures.Players.QUALITY_SAMPLE, features.players.listQuality)
        assertEquals(NODE, features.players.actions)
    }

    // ---------------------------------------------------------------- plugin only, no node

    @Test
    fun `a linked server with a current plugin is served entirely by the plugin`() {
        val features = ServerFeatureResolver.resolve(pluginOnly(ServerCapability.entries.toSet()))

        assertEquals(PLUGIN, features.console.stream)
        assertEquals(PLUGIN, features.console.history)
        assertEquals(PLUGIN, features.console.input)
        assertEquals(PLUGIN, features.power.stop)
        assertEquals(PLUGIN, features.power.restart)
        assertEquals(PLUGIN, features.metrics.tps)
        assertEquals(PLUGIN, features.metrics.memory)
        assertEquals(PLUGIN, features.players.list)
        assertEquals(ServerFeatures.Players.QUALITY_FULL, features.players.listQuality)
        assertEquals(PLUGIN, features.plugins.list)
        assertEquals(PLUGIN, features.plugins.toggle)
        assertEquals(PLUGIN, features.plugins.install)
        assertEquals(PLUGIN, features.files.source)
        assertEquals(PLUGIN, features.backups.create)
        assertEquals(PLUGIN, features.schedules.runner)
    }

    @Test
    fun `a linked server never offers start, kill or host metrics`() {
        val features = ServerFeatureResolver.resolve(pluginOnly(ServerCapability.entries.toSet()))

        assertFalse(features.power.start)
        assertFalse(features.power.kill)
        assertFalse(features.metrics.host)
    }

    @Test
    fun `an outdated plugin contributes what it announced and nothing else`() {
        val features = ServerFeatureResolver.resolve(
            pluginOnly(setOf(ServerCapability.CONSOLE, ServerCapability.COMMANDS))
        )

        assertEquals(PLUGIN, features.console.stream)
        assertEquals(PLUGIN, features.console.input)
        assertNull(features.power.stop)
        assertNull(features.metrics.tps)
        assertNull(features.plugins.list)
        assertNull(features.files.source)
        assertNull(features.backups.create)
        assertNull(features.backups.restoreMode)
        // Pano's own clock is the last resort, so schedules never go away.
        assertEquals(PANO, features.schedules.runner)
    }

    @Test
    fun `a protocol 1 plugin leaves everything to Pano's own fallbacks`() {
        val features = ServerFeatureResolver.resolve(pluginOnly(emptySet()))

        assertEquals(PANO, features.console.stream)
        assertEquals(PANO, features.console.history)
        assertNull(features.console.input)
        assertNull(features.players.list)
        assertNull(features.players.listQuality)
        assertNull(features.players.actions)
        assertEquals(PANO, features.schedules.runner)
    }

    @Test
    fun `a plugin id this Pano has never heard of is ignored rather than rejected`() {
        val inputs = ServerFeatureInputs.of(
            server = linkedRow(listOf("console", "teleportation-beam", "files")),
            nodeConnected = false,
            pluginConnected = true
        )

        assertEquals(setOf(ServerCapability.CONSOLE, ServerCapability.FILES), inputs.capabilities)
        assertEquals(PLUGIN, ServerFeatureResolver.resolve(inputs).files.source)
    }

    // ---------------------------------------------------------------- both present

    @Test
    fun `with both present each feature goes to whichever does it better`() {
        val features = ServerFeatureResolver.resolve(both(ServerCapability.entries.toSet()))

        // The node owns the pipe and the disk.
        assertEquals(NODE, features.console.stream)
        assertEquals(NODE, features.console.input)
        assertEquals(NODE, features.power.stop)
        assertEquals(NODE, features.files.source)
        assertEquals(NODE, features.plugins.install)
        assertEquals(NODE, features.plugins.identify)
        assertEquals(NODE, features.backups.create)
        assertEquals(NODE, features.schedules.runner)

        // The plugin owns everything only the running game can see.
        assertEquals(PLUGIN, features.metrics.tps)
        assertEquals(PLUGIN, features.metrics.memory)
        assertEquals(PLUGIN, features.metrics.players)
        assertEquals(PLUGIN, features.players.list)
        assertEquals(ServerFeatures.Players.QUALITY_FULL, features.players.listQuality)
        assertEquals(PLUGIN, features.plugins.list)
        assertEquals(NODE, features.plugins.toggle)
    }

    @Test
    fun `a plugin outside the Bukkit family cannot toggle, so the node renames the jar instead`() {
        val features = ServerFeatureResolver.resolve(
            both(ServerCapability.entries.toSet(), type = ServerType.VELOCITY)
        )

        assertEquals(PLUGIN, features.plugins.list)
        assertEquals(NODE, features.plugins.toggle)
    }

    // ---------------------------------------------------------------- adopted / stopped / offline

    @Test
    fun `an adopted process loses node console input but keeps the plugin's`() {
        val adopted = both(setOf(ServerCapability.COMMANDS)).copy(stdinAvailable = false)
        val features = ServerFeatureResolver.resolve(adopted)

        assertEquals(NODE, features.console.stream)
        assertEquals(PLUGIN, features.console.input)
        // Actions would go through stdin on the node path, which is the half that is gone.
        assertEquals(PLUGIN, features.players.actions)
    }

    @Test
    fun `an adopted process with no plugin has nothing to write a command with`() {
        val adopted = nodeOnly().copy(stdinAvailable = false)
        val features = ServerFeatureResolver.resolve(adopted)

        assertNull(features.console.input)
        assertNull(features.players.actions)
        assertEquals(NODE, features.console.stream)
    }

    @Test
    fun `a stopped managed server offers start and nothing that needs a process`() {
        val features = ServerFeatureResolver.resolve(nodeOnly().copy(processState = ServerProcessState.STOPPED))

        assertTrue(features.power.start)
        assertFalse(features.power.kill)
        assertNull(features.power.stop)
        assertNull(features.power.restart)
        assertNull(features.metrics.memory)
        assertNull(features.metrics.players)
        assertNull(features.players.list)
        assertNull(features.players.actions)
        // Files and backups are on disk, so they are unaffected by the process being down.
        assertEquals(NODE, features.files.source)
        assertEquals(NODE, features.backups.create)
    }

    @Test
    fun `a node that went offline leaves a still-running game to its plugin`() {
        val features = ServerFeatureResolver.resolve(
            both(ServerCapability.entries.toSet()).copy(nodeConnected = false)
        )

        assertEquals(PLUGIN, features.console.stream)
        assertEquals(PLUGIN, features.console.input)
        assertEquals(PLUGIN, features.power.stop)
        assertEquals(PLUGIN, features.files.source)
        assertEquals(PLUGIN, features.backups.create)
        assertEquals(PLUGIN, features.schedules.runner)
        assertFalse(features.power.start)
        assertFalse(features.metrics.host)
    }

    // ---------------------------------------------------------------- restore modes

    @Test
    fun `a node restores in place only while the server is stopped`() {
        val stopped = ServerFeatureResolver.resolve(
            both(setOf(ServerCapability.BACKUPS)).copy(processState = ServerProcessState.STOPPED)
        )

        assertEquals(NODE, stopped.backups.restore)
        assertEquals(ServerFeatures.Backups.MODE_LIVE, stopped.backups.restoreMode)
    }

    @Test
    fun `a running server falls back to the plugin's next-start restore`() {
        val running = ServerFeatureResolver.resolve(both(setOf(ServerCapability.BACKUPS)))

        assertEquals(PLUGIN, running.backups.restore)
        assertEquals(ServerFeatures.Backups.MODE_NEXT_START, running.backups.restoreMode)
    }

    @Test
    fun `a running server whose plugin cannot restore offers nothing`() {
        val running = ServerFeatureResolver.resolve(nodeOnly())

        assertNull(running.backups.restore)
        assertNull(running.backups.restoreMode)
    }

    // ---------------------------------------------------------------- pick()

    @Test
    fun `pick hands back the resolved source`() {
        assertEquals(NODE, ServerFeatureResolver.pick(nodeOnly(), ServerFeature.FILES_SOURCE))
        assertEquals(
            PLUGIN,
            ServerFeatureResolver.pick(pluginOnly(setOf(ServerCapability.METRICS)), ServerFeature.METRICS_TPS)
        )
    }

    @Test
    fun `pick names the feature it refused`() {
        val error = assertThrows<FeatureUnavailable> {
            ServerFeatureResolver.pick(pluginOnly(emptySet()), ServerFeature.FILES_SOURCE)
        }

        assertTrue(error.encode(emptyMap()).contains("\"feature\":\"files.source\""))
        assertEquals(409, error.getStatusCode())
        assertEquals("FEATURE_UNAVAILABLE", error.getErrorCode())
    }

    @Test
    fun `pick keeps the lost-stdin answer for the one case it describes`() {
        assertThrows<ServerNoStdin> {
            ServerFeatureResolver.pick(nodeOnly().copy(stdinAvailable = false), ServerFeature.CONSOLE_INPUT)
        }
    }

    @Test
    fun `a linked server with no console route is an ordinary unavailable feature`() {
        assertThrows<FeatureUnavailable> {
            ServerFeatureResolver.pick(pluginOnly(emptySet()), ServerFeature.CONSOLE_INPUT)
        }
    }

    // ---------------------------------------------------------------- JSON shape

    @Test
    fun `the JSON is the shape the panel is written against`() {
        val json = ServerFeatureResolver.resolve(both(ServerCapability.entries.toSet())).toJsonObject()

        assertEquals("node", json.getJsonObject("console").getString("input"))
        assertEquals("plugin", json.getJsonObject("metrics").getString("tps"))
        assertTrue(json.getJsonObject("power").getBoolean("kill"))
        assertFalse(json.getJsonObject("power").getBoolean("start"))
        assertEquals("full", json.getJsonObject("players").getString("listQuality"))
        assertEquals("next-start", json.getJsonObject("backups").getString("restoreMode"))
        assertEquals("node", json.getJsonObject("schedules").getString("runner"))
    }

    @Test
    fun `an unavailable source is a null and not a missing key`() {
        val json = ServerFeatureResolver.resolve(pluginOnly(emptySet())).toJsonObject()

        assertTrue(json.getJsonObject("files").containsKey("source"))
        assertNull(json.getJsonObject("files").getString("source"))
        assertNull(json.getJsonObject("players").getString("listQuality"))
    }

    @Test
    fun `every pickable feature is reachable from the resolved shape`() {
        val features = ServerFeatureResolver.resolve(both(ServerCapability.entries.toSet()))

        ServerFeature.entries.forEach { feature ->
            assertTrue(
                features.sourceOf(feature) != null,
                "${feature.id} should be served when both a node and a current plugin are present"
            )
        }
    }

    /** A linked server row, the only place these tests need a real entity. */
    private fun linkedRow(capabilities: List<String>) = Server(
        name = "survival",
        motd = "",
        host = "127.0.0.1",
        port = 25565,
        playerCount = 0,
        maxPlayerCount = 20,
        type = ServerType.PAPER,
        version = "1.21",
        favicon = "",
        status = ServerStatus.ONLINE,
        startTime = 0,
        aesKey = "",
        capabilities = capabilities
    )

    private fun nodeOnly() = ServerFeatureInputs.of(
        kind = ServerKind.MANAGED,
        nodeConnected = true,
        processState = ServerProcessState.RUNNING
    )

    private fun pluginOnly(capabilities: Set<ServerCapability>, type: ServerType = ServerType.PAPER) =
        ServerFeatureInputs.of(
            kind = ServerKind.LINKED,
            pluginConnected = true,
            capabilities = capabilities,
            type = type
        )

    private fun both(capabilities: Set<ServerCapability>, type: ServerType = ServerType.PAPER) =
        ServerFeatureInputs.of(
            kind = ServerKind.MANAGED,
            nodeConnected = true,
            processState = ServerProcessState.RUNNING,
            pluginConnected = true,
            capabilities = capabilities,
            type = type
        )

    @Test
    fun `a vanilla server has no plugin directory for anyone to serve`() {
        val features = ServerFeatureResolver.resolve(
            ServerFeatureInputs.of(
                kind = ServerKind.MANAGED,
                nodeConnected = true,
                stdinAvailable = true,
                processState = ServerProcessState.RUNNING,
                pluginConnected = false,
                capabilities = emptySet(),
                type = ServerType.VANILLA
            )
        )

        assertNull(features.plugins.list)
        assertNull(features.plugins.toggle)
        assertNull(features.plugins.install)
        assertNull(features.plugins.identify)
        assertEquals(ServerFeatureSource.NODE, features.files.source)
    }
}
