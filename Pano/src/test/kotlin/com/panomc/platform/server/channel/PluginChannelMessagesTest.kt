package com.panomc.platform.server.channel

import com.panomc.platform.node.ServerTaskStatus
import com.panomc.platform.node.ServerTaskTimeout
import com.panomc.platform.node.message.BackupCreateMessage
import com.panomc.platform.node.message.BackupRestoreMessage
import com.panomc.platform.node.message.FileListMessage
import com.panomc.platform.node.message.InstallPluginMessage
import com.panomc.platform.node.message.SyncScheduleEntry
import com.panomc.platform.node.message.SyncSchedulesMessage
import com.panomc.platform.server.message.RelayServerMessage
import com.panomc.platform.server.message.RelayServerRequestMessage
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a node message looks like once it is sent down a plugin socket instead (§2.4.17 C).
 *
 * The agent-lite contract is that the plugin answers the node's shapes *verbatim*, so this is the
 * test that proves the relay changes nothing but the two fields it is supposed to: the correlation
 * id, which the sender assigns, and `serverUuid`, which a socket that belongs to one server has no
 * use for.
 */
class PluginChannelMessagesTest {
    @Test
    fun `a file request keeps its name and its whole payload`() {
        val payload = PluginSideChannel.payloadOf(FileListMessage("abc", "plugins"))

        assertEquals("plugins", payload.getString("path"))
        assertFalse(payload.containsKey("serverUuid"))
        assertFalse(payload.containsKey("eventId"))
    }

    @Test
    fun `the relay writes the event name the node would have used`() {
        val message = RelayServerRequestMessage(
            FileListMessage("abc", "plugins").getResponseName(),
            PluginSideChannel.payloadOf(FileListMessage("abc", "plugins"))
        )

        message.eventId = "id-1"

        val json = JsonObject(message.encode())

        assertEquals("FILE_LIST", json.getString("event"))
        assertEquals("id-1", json.getString("eventId"))
        assertEquals("plugins", json.getString("path"))
    }

    @Test
    fun `a push carries no correlation id, because nobody is waiting for one`() {
        val json = JsonObject(
            RelayServerMessage(
                "BACKUP_CREATE",
                PluginSideChannel.payloadOf(BackupCreateMessage("abc", "task-1", "backup-1", "nightly"))
            ).encode()
        )

        assertEquals("BACKUP_CREATE", json.getString("event"))
        assertEquals("task-1", json.getString("taskId"))
        assertEquals("backup-1", json.getString("backupId"))
        assertEquals("nightly", json.getString("name"))
        assertFalse(json.containsKey("eventId"))
        assertFalse(json.containsKey("serverUuid"))
    }

    @Test
    fun `a restore asks rather than tells, so the next-start answer can come back`() {
        val message = BackupRestoreMessage("abc", "backup-1", "task-1", "notch")

        assertEquals("BACKUP_RESTORE", message.getResponseName())

        val payload = PluginSideChannel.payloadOf(message)

        assertEquals("backup-1", payload.getString("backupId"))
        assertEquals("task-1", payload.getString("taskId"))
        assertEquals("notch", payload.getString("requestedBy"))
    }

    @Test
    fun `an install keeps every hash the source published`() {
        val payload = PluginSideChannel.payloadOf(
            InstallPluginMessage(
                serverUuid = "abc",
                taskId = "task-1",
                downloadUrl = "https://example.invalid/x.jar",
                filename = "x.jar",
                targetDir = "plugins",
                sha512 = "a",
                sha1 = "b",
                sha256 = "c",
                replaceFilename = "old.jar"
            )
        )

        assertEquals("x.jar", payload.getString("filename"))
        assertEquals("plugins", payload.getString("targetDir"))
        assertEquals("a", payload.getString("sha512"))
        assertEquals("old.jar", payload.getString("replaceFilename"))
        assertFalse(payload.containsKey("serverUuid"))
    }

    @Test
    fun `a schedule sync keeps the whole set and loses only the uuid of the server`() {
        val payload = PluginSideChannel.payloadOf(
            SyncSchedulesMessage(
                "abc",
                listOf(
                    SyncScheduleEntry(
                        uuid = "s-1",
                        name = "nightly",
                        cron = "0 4 * * *",
                        timezone = "UTC",
                        enabled = true,
                        warnMinutes = 5,
                        tasks = emptyList()
                    )
                )
            )
        )

        assertFalse(payload.containsKey("serverUuid"))
        assertEquals(1, payload.getJsonArray("schedules").size())
        assertEquals("s-1", payload.getJsonArray("schedules").getJsonObject(0).getString("uuid"))
    }

    @Test
    fun `a restore waiting for a restart is neither finished nor overdue`() {
        assertFalse(ServerTaskStatus.PENDING_RESTART.isTerminal)
        assertTrue(ServerTaskStatus.PENDING_RESTART.isWaiting)
        assertNull(ServerTaskTimeout.timeoutMsFor(ServerTaskStatus.PENDING_RESTART))
        assertFalse(
            ServerTaskTimeout.hasTimedOut(ServerTaskStatus.PENDING_RESTART, 0, Long.MAX_VALUE / 2)
        )
    }

    @Test
    fun `the wire value survives a round trip through the stored name`() {
        assertEquals(ServerTaskStatus.PENDING_RESTART, ServerTaskStatus.fromId("PENDING_RESTART"))
        assertEquals(ServerTaskStatus.PENDING_RESTART, ServerTaskStatus.fromId("pending_restart"))
    }
}
