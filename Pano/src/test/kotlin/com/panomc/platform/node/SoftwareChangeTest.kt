package com.panomc.platform.node

import com.panomc.platform.error.KeepIncompatible
import com.panomc.platform.error.NetworkRoleConflict
import com.panomc.platform.node.message.InstallServerSpec
import com.panomc.platform.node.message.ReinstallKeepSpec
import com.panomc.platform.node.message.ReinstallServerMessage
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.software.ServerSoftwareFamily
import com.panomc.platform.server.software.ServerSoftwareFamily.BUKKIT
import com.panomc.platform.server.software.ServerSoftwareFamily.BUNGEE
import com.panomc.platform.server.software.ServerSoftwareFamily.FABRIC
import com.panomc.platform.server.software.ServerSoftwareFamily.FORGE
import com.panomc.platform.server.software.ServerSoftwareFamily.VANILLA
import com.panomc.platform.server.software.ServerSoftwareFamily.VELOCITY
import com.panomc.platform.server.software.SoftwareChangeKeep
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Changing a managed server's software (SM-66, §2.4.31): the family table, what may be kept
 * between which families, the errors a request can get, and the order the steps run in.
 */
class SoftwareChangeTest {
    private fun keep(worlds: Boolean, plugins: Boolean, configs: Boolean) = SoftwareChangeKeep(worlds, plugins, configs)

    // ------------------------------------------------------------------------------- families

    @Test
    fun `every software id lands in the family the contract names`() {
        mapOf(
            "paper" to BUKKIT, "purpur" to BUKKIT, "folia" to BUKKIT, "spigot" to BUKKIT, "craftbukkit" to BUKKIT,
            "fabric" to FABRIC, "quilt" to FABRIC,
            "forge" to FORGE, "neoforge" to FORGE,
            "vanilla" to VANILLA,
            "velocity" to VELOCITY,
            "bungeecord" to BUNGEE, "waterfall" to BUNGEE
        ).forEach { (id, family) -> assertEquals(family, ServerSoftwareFamily.ofSoftware(id), id) }

        assertEquals(BUKKIT, ServerSoftwareFamily.ofSoftware("Paper"))
        assertNull(ServerSoftwareFamily.ofSoftware("sponge"))
        assertNull(ServerSoftwareFamily.ofSoftware(null))
    }

    @Test
    fun `a row without a software id is placed by its type, and every type has a family`() {
        assertEquals(FORGE, ServerSoftwareFamily.of(null, ServerType.NEOFORGE))
        assertEquals(BUNGEE, ServerSoftwareFamily.of(null, ServerType.WATERFALL))
        assertEquals(BUKKIT, ServerSoftwareFamily.of("purpur", ServerType.VANILLA))

        ServerType.entries.forEach { type ->
            assertEquals(type.isProxy, ServerSoftwareFamily.ofType(type).isProxy, type.name)
        }
    }

    @Test
    fun `kind is backend or proxy`() {
        assertEquals(listOf("backend", "backend", "backend", "backend", "proxy", "proxy"), ServerSoftwareFamily.entries.map { it.kind })
    }

    // ---------------------------------------------------------------------- allowed and defaults

    @Test
    fun `within one family everything is kept by default`() {
        assertEquals(keep(true, true, true), SoftwareChangeKeep.defaults(BUKKIT, BUKKIT))
        assertEquals(keep(true, true, true), SoftwareChangeKeep.defaults(FABRIC, FABRIC))
        assertEquals(keep(true, true, true), SoftwareChangeKeep.defaults(FORGE, FORGE))
    }

    @Test
    fun `vanilla has no plugins to keep`() {
        assertEquals(keep(true, false, true), SoftwareChangeKeep.allowed(VANILLA, VANILLA))
    }

    @Test
    fun `a proxy keeps its plugins and configs but has no worlds`() {
        assertEquals(keep(false, true, true), SoftwareChangeKeep.allowed(VELOCITY, VELOCITY))
        assertEquals(keep(false, true, true), SoftwareChangeKeep.allowed(BUNGEE, BUNGEE))
    }

    @Test
    fun `bukkit, fabric and vanilla swap worlds and the vanilla configs but never plugins`() {
        listOf(BUKKIT to FABRIC, FABRIC to BUKKIT, BUKKIT to VANILLA, VANILLA to FABRIC).forEach { (from, to) ->
            assertEquals(keep(true, false, true), SoftwareChangeKeep.allowed(from, to), "$from -> $to")
            assertEquals(keep(true, false, true), SoftwareChangeKeep.defaults(from, to), "$from -> $to")
        }
    }

    @Test
    fun `forge to another backend keeps only the worlds`() {
        assertEquals(keep(true, false, false), SoftwareChangeKeep.allowed(FORGE, BUKKIT))
        assertEquals(keep(true, false, false), SoftwareChangeKeep.allowed(VANILLA, FORGE))
    }

    @Test
    fun `between a proxy and a backend nothing carries over`() {
        listOf(BUKKIT to VELOCITY, VELOCITY to BUKKIT, BUKKIT to BUNGEE, VELOCITY to BUNGEE, FABRIC to VELOCITY)
            .forEach { (from, to) ->
                assertEquals(keep(false, false, false), SoftwareChangeKeep.allowed(from, to), "$from -> $to")
            }
    }

    @Test
    fun `a node older than the keep protocol is offered the worlds only`() {
        assertEquals(keep(true, false, false), SoftwareChangeKeep.allowed(BUKKIT, BUKKIT, nodeKeepsEverything = false))
        assertEquals(keep(true, false, false), SoftwareChangeKeep.defaults(BUKKIT, BUKKIT, nodeKeepsEverything = false))
        assertFalse(NodeProtocol.supportsReinstallKeep(3))
        assertFalse(NodeProtocol.supportsReinstallKeep(null))
        assertTrue(NodeProtocol.supportsReinstallKeep(NodeProtocol.REINSTALL_KEEP_VERSION))
    }

    // --------------------------------------------------------------------------------- reasons

    @Test
    fun `reasons name the structural cause first and the node's age last`() {
        assertEquals(
            mapOf("worlds" to "PROXY", "plugins" to "FAMILY_CHANGED", "configs" to "FAMILY_CHANGED"),
            SoftwareChangeKeep.reasons(BUKKIT, VELOCITY, nodeKeepsEverything = false)
        )
        assertEquals(
            mapOf("worlds" to null, "plugins" to "NODE_TOO_OLD", "configs" to "NODE_TOO_OLD"),
            SoftwareChangeKeep.reasons(BUKKIT, BUKKIT, nodeKeepsEverything = false)
        )
        assertEquals(
            mapOf("worlds" to null, "plugins" to "NO_PLUGINS", "configs" to null),
            SoftwareChangeKeep.reasons(VANILLA, VANILLA)
        )
        assertEquals(
            mapOf("worlds" to null, "plugins" to "FAMILY_CHANGED", "configs" to null),
            SoftwareChangeKeep.reasons(BUKKIT, FABRIC)
        )
    }

    @Test
    fun `a reason is given exactly for the switches that are not allowed`() {
        ServerSoftwareFamily.entries.forEach { from ->
            ServerSoftwareFamily.entries.forEach { to ->
                listOf(true, false).forEach { node ->
                    val allowed = SoftwareChangeKeep.allowed(from, to, node).toJson()
                    val reasons = SoftwareChangeKeep.reasons(from, to, node)

                    listOf("worlds", "plugins", "configs").forEach { key ->
                        assertEquals(allowed.getBoolean(key), reasons[key] == null, "$from -> $to ($key, node=$node)")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------- the request's keep

    @Test
    fun `a missing keep takes the defaults and a partial one fills the rest from them`() {
        val defaults = keep(true, true, false)

        assertNull(SoftwareChangeKeep.parse(null, defaults))
        assertEquals(keep(true, false, false), SoftwareChangeKeep.parse(JsonObject().put("plugins", false), defaults))
        assertEquals(keep(false, true, true), SoftwareChangeKeep.parse(JsonObject().put("worlds", false).put("configs", true), defaults))
    }

    @Test
    fun `asking for plugins across families is named as the incompatible keep`() {
        val allowed = SoftwareChangeKeep.allowed(BUKKIT, VELOCITY)

        assertEquals(listOf("worlds", "plugins"), keep(true, true, false).disallowedBy(allowed))
        assertEquals(emptyList<String>(), keep(false, false, false).disallowedBy(allowed))
        assertEquals(keep(false, false, false), keep(true, true, true).clampTo(allowed))
    }

    @Test
    fun `the refusals carry the codes the panel reads`() {
        assertEquals("KEEP_INCOMPATIBLE", KeepIncompatible().getErrorCode())
        assertEquals("NETWORK_ROLE_CONFLICT", NetworkRoleConflict().getErrorCode())

        val encoded = JsonObject(KeepIncompatible(extras = mapOf("keep" to listOf("plugins"))).encode(emptyMap()))

        assertEquals("KEEP_INCOMPATIBLE", encoded.getString("error"))
        assertEquals(listOf("plugins"), encoded.getJsonArray("keep").list)
    }

    @Test
    fun `a network member may not swap between proxy and backend, and nothing is a member today`() {
        assertTrue(SoftwareChangeSteps.networkRoleConflict(inNetwork = true, from = BUKKIT, to = VELOCITY))
        assertTrue(SoftwareChangeSteps.networkRoleConflict(inNetwork = true, from = BUNGEE, to = FABRIC))
        assertFalse(SoftwareChangeSteps.networkRoleConflict(inNetwork = true, from = BUKKIT, to = FABRIC))
        assertFalse(SoftwareChangeSteps.networkRoleConflict(inNetwork = true, from = VELOCITY, to = BUNGEE))
        assertFalse(SoftwareChangeSteps.networkRoleConflict(inNetwork = false, from = BUKKIT, to = VELOCITY))
    }

    // ----------------------------------------------------------------------- request defaults

    @Test
    fun `startAfter defaults to whether the server was running, backupFirst to yes`() {
        assertTrue(SoftwareChangeSteps.startAfter(null, running = true))
        assertFalse(SoftwareChangeSteps.startAfter(null, running = false))
        assertFalse(SoftwareChangeSteps.startAfter(false, running = true))
        assertTrue(SoftwareChangeSteps.startAfter(true, running = false))

        assertTrue(SoftwareChangeSteps.backupFirst(null))
        assertFalse(SoftwareChangeSteps.backupFirst(false))
    }

    @Test
    fun `the version stays only when the software stays`() = runBlocking {
        val recommended: suspend (String) -> String? = { "recommended-$it" }

        assertEquals("1.21.8", SoftwareChangeSteps.targetVersion(null, "paper", "paper", "1.21.8", recommended))
        assertEquals("recommended-bungeecord", SoftwareChangeSteps.targetVersion(null, "bungeecord", "paper", "1.21.8", recommended))
        assertEquals("1.21.4", SoftwareChangeSteps.targetVersion("1.21.4", "purpur", "paper", "1.21.8", recommended))
        assertEquals("recommended-paper", SoftwareChangeSteps.targetVersion(" ", "paper", "paper", null, recommended))
    }

    @Test
    fun `the backup is named before-old-to-new-date`() {
        // 2026-09-24T18:30:00Z
        val at = 1790274600000L

        assertEquals(
            "before-paper-1.21.8-to-purpur-1.21.8-2026-09-24-1830",
            SoftwareChangeSteps.backupName("paper", "1.21.8", "purpur", "1.21.8", at)
        )
        assertEquals(
            "before-unknown-to-velocity-3.4.0-2026-09-24-1830",
            SoftwareChangeSteps.backupName(null, null, "velocity", "3.4.0", at)
        )
    }

    // ------------------------------------------------------------------------------- the steps

    private class RecordingActions(
        private val stops: Boolean = true,
        private val backupError: String? = null,
        private val reinstallThrows: Boolean = false
    ) : SoftwareChangeSteps.Actions {
        val log = mutableListOf<String>()

        override suspend fun progress(message: String) {
            log += "progress:$message"
        }

        override suspend fun stopAndWait(): Boolean {
            log += "stop"

            return stops
        }

        override suspend fun backup(name: String): String? {
            log += "backup:$name"

            return backupError
        }

        override suspend fun reinstall() {
            log += "reinstall"

            if (reinstallThrows) {
                throw IllegalStateException("NodeOffline")
            }
        }

        override suspend fun fail(error: String) {
            log += "fail:$error"
        }
    }

    private val plan = SoftwareChangeSteps.Plan(running = true, backupFirst = true, backupName = "b")

    @Test
    fun `a running server is stopped, backed up, then reinstalled, in that order`() = runBlocking {
        val actions = RecordingActions()

        assertEquals(SoftwareChangeSteps.Outcome.HANDED_TO_NODE, SoftwareChangeSteps(actions).run(plan))
        assertEquals(
            listOf(
                "progress:${SoftwareChangeSteps.MESSAGE_STOPPING}",
                "stop",
                "progress:Backing up the server (b)",
                "backup:b",
                "progress:${SoftwareChangeSteps.MESSAGE_REINSTALLING}",
                "reinstall"
            ),
            actions.log
        )
    }

    @Test
    fun `a failed backup ends the change before anything is reinstalled`() = runBlocking {
        val actions = RecordingActions(backupError = "disk full")

        assertEquals(SoftwareChangeSteps.Outcome.BACKUP_FAILED, SoftwareChangeSteps(actions).run(plan))
        assertFalse(actions.log.contains("reinstall"))
        assertEquals("fail:${SoftwareChangeSteps.ERROR_BACKUP_PREFIX}disk full", actions.log.last())
    }

    @Test
    fun `a server that does not stop is neither backed up nor reinstalled`() = runBlocking {
        val actions = RecordingActions(stops = false)

        assertEquals(SoftwareChangeSteps.Outcome.STOP_FAILED, SoftwareChangeSteps(actions).run(plan))
        assertEquals(listOf("progress:${SoftwareChangeSteps.MESSAGE_STOPPING}", "stop", "fail:${SoftwareChangeSteps.ERROR_STOP}"), actions.log)
    }

    @Test
    fun `a stopped server is not stopped, and no backup is taken when none was asked for`() = runBlocking {
        val actions = RecordingActions()

        SoftwareChangeSteps(actions).run(plan.copy(running = false, backupFirst = false))

        assertEquals(listOf("progress:${SoftwareChangeSteps.MESSAGE_REINSTALLING}", "reinstall"), actions.log)
    }

    @Test
    fun `a reinstall the node could not be handed fails the task`() = runBlocking {
        val actions = RecordingActions(reinstallThrows = true)

        assertEquals(SoftwareChangeSteps.Outcome.REINSTALL_FAILED, SoftwareChangeSteps(actions).run(plan))
        assertEquals("fail:NodeOffline", actions.log.last())
    }

    // ------------------------------------------------------------------------------- the wire

    @Test
    fun `a reinstall goes out as REINSTALL_SERVER with its keep list`() {
        val message = ReinstallServerMessage(
            serverUuid = "u",
            taskId = "t",
            spec = InstallServerSpec(
                name = "s",
                software = "purpur",
                version = "1.21.8",
                javaMajor = null,
                memoryMb = 2048,
                jvmArgs = emptyList(),
                port = 25565,
                acceptEula = true,
                properties = emptyMap(),
                downloadUrl = "https://example.invalid/purpur.jar",
                keep = ReinstallKeepSpec(worlds = true, plugins = true, configs = false)
            )
        )

        val encoded = JsonObject(message.encode())

        assertEquals("REINSTALL_SERVER", encoded.getString("event"))
        assertEquals(
            JsonObject().put("worlds", true).put("plugins", true).put("configs", false),
            encoded.getJsonObject("spec").getJsonObject("keep")
        )
    }

    @Test
    fun `REINSTALL is not a task a node can open by itself`() {
        assertFalse(ServerTaskKind.REINSTALL.mayBeOpenedByNode)
    }
}
