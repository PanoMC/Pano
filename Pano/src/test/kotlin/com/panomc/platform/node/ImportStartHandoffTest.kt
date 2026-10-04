package com.panomc.platform.node

import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.node.message.InstallPanoPluginMessage
import com.panomc.platform.node.message.ManagedPluginConfig
import com.panomc.platform.node.message.ManagedPluginSpec
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Who starts an imported server ([ImportStartHandoff]): the import's DONE, or the Pano plugin
 * install that follows it.
 *
 * The bug this pins down: DONE sent START while the node was still downloading the plugin into the
 * server, and a remote server booted first and ran unlinked. An import whose install goes out
 * hands its start to that install; everything else starts exactly as it did.
 */
class ImportStartHandoffTest {
    private fun task(kind: ServerTaskKind, status: ServerTaskStatus = ServerTaskStatus.RUNNING) =
        ServerTask(uuid = "7c1f0e6a-1d2b-4c3d-8e9f-0a1b2c3d4e5f", serverId = 1, nodeId = 1, kind = kind, status = status, createdBy = 1)

    // ---------------------------------------------------------------- what the install carries

    @Test
    fun `an import still under way hands the row's auto-start to its plugin install`() {
        assertEquals(true, ImportStartHandoff.startAfterFor(task(ServerTaskKind.IMPORT), null, autoStart = true))
        assertEquals(false, ImportStartHandoff.startAfterFor(task(ServerTaskKind.IMPORT), null, autoStart = false))

        // Pano's own PENDING, before the node's first frame, is just as much under way.
        assertEquals(
            true,
            ImportStartHandoff.startAfterFor(task(ServerTaskKind.IMPORT, ServerTaskStatus.PENDING), null, autoStart = true)
        )
    }

    @Test
    fun `a start decided up front for the task wins over the row, as it does on DONE`() {
        assertEquals(false, ImportStartHandoff.startAfterFor(task(ServerTaskKind.IMPORT), false, autoStart = true))
        assertEquals(true, ImportStartHandoff.startAfterFor(task(ServerTaskKind.IMPORT), true, autoStart = false))
    }

    @Test
    fun `an import that already ended hands nothing over`() {
        listOf(ServerTaskStatus.DONE, ServerTaskStatus.FAILED).forEach { status ->
            assertNull(ImportStartHandoff.startAfterFor(task(ServerTaskKind.IMPORT, status), null, autoStart = true), status.name)
        }
    }

    @Test
    fun `a link that follows no import carries no start`() {
        // A Pano plugin update's first link, or an IMPORT_RESULT without a task.
        assertNull(ImportStartHandoff.startAfterFor(null, null, autoStart = true))

        ServerTaskKind.entries.filter { it != ServerTaskKind.IMPORT }.forEach { kind ->
            assertNull(ImportStartHandoff.startAfterFor(task(kind), true, autoStart = true), kind.name)
        }
    }

    // --------------------------------------------------------------------- what DONE then does

    @Test
    fun `DONE starts the server itself only when no install carried the start`() {
        // Not carried is also every task that is not an import, and an import that got no install
        // (no plugin for its software, no build to be had, the link threw): the old start.
        assertTrue(ImportStartHandoff.startsOnDone(start = true, carriedByLink = false))
        assertFalse(ImportStartHandoff.startsOnDone(start = true, carriedByLink = true))
        assertFalse(ImportStartHandoff.startsOnDone(start = false, carriedByLink = false))
        assertFalse(ImportStartHandoff.startsOnDone(start = false, carriedByLink = true))
    }

    // ------------------------------------------------------------------------------- the wire

    private val spec = ManagedPluginSpec(
        jarUrl = "/api/node/plugin-jars/spigot",
        targetDir = "plugins",
        configPath = "plugins/Pano/config.conf",
        config = ManagedPluginConfig(host = "127.0.0.1", port = 8088, ssl = false, token = "a.jwt", encryptionKey = "a2V5")
    )

    @Test
    fun `the install carries startAfter to the node`() {
        val json = JsonObject(InstallPanoPluginMessage("abc", "task", spec, startAfter = true).encode())

        assertEquals("INSTALL_PANO_PLUGIN", json.getString("event"))
        assertEquals("abc", json.getString("serverUuid"))
        assertEquals("task", json.getString("taskId"))
        assertEquals(true, json.getBoolean("startAfter"))
    }

    @Test
    fun `an install with nothing to start says nothing`() {
        val json = JsonObject(InstallPanoPluginMessage("abc", "task", spec).encode())

        assertNull(json.getValue("startAfter"))
    }
}
