package com.panomc.platform.server

import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `activeTask` on the server JSON (SM-68): what is kept, what clears it, which one wins. */
class ServerActiveTaskStoreTest {
    private fun task(
        uuid: String,
        status: ServerTaskStatus,
        serverId: Long? = 1,
        kind: ServerTaskKind = ServerTaskKind.INSTALL,
        percent: Int = 0,
        createdAt: Long = 100,
        id: Long = 1
    ) = ServerTask(
        id = id,
        uuid = uuid,
        serverId = serverId,
        nodeId = 7,
        kind = kind,
        status = status,
        percent = percent,
        message = "Compiling",
        createdBy = 3,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    @Test
    fun `a running task is kept with its progress and cleared when it ends`() {
        val store = ServerActiveTaskStore()

        store.onTask(task("a", ServerTaskStatus.PENDING))
        store.onTask(task("a", ServerTaskStatus.RUNNING, percent = 42))

        val json = store.get(1)!!.toJsonObject()

        assertEquals("a", json.getString("uuid"))
        assertEquals("INSTALL", json.getString("kind"))
        assertEquals("RUNNING", json.getString("status"))
        assertEquals(42, json.getInteger("percent"))
        assertEquals("Compiling", json.getString("message"))
        assertEquals(100L, json.getLong("startedAt"))

        store.onTask(task("a", ServerTaskStatus.DONE, percent = 100))
        assertNull(store.get(1))

        store.onTask(task("b", ServerTaskStatus.RUNNING))
        store.onTask(task("b", ServerTaskStatus.FAILED))
        assertNull(store.get(1))
    }

    @Test
    fun `the newest of overlapping tasks wins and the older one shows again when it ends`() {
        val store = ServerActiveTaskStore()

        store.onTask(task("backup", ServerTaskStatus.RUNNING, kind = ServerTaskKind.BACKUP, createdAt = 100, id = 1))
        store.onTask(task("java", ServerTaskStatus.RUNNING, kind = ServerTaskKind.JAVA_INSTALL, createdAt = 200, id = 2))

        assertEquals("java", store.get(1)?.uuid)

        store.onTask(task("java", ServerTaskStatus.DONE, kind = ServerTaskKind.JAVA_INSTALL, createdAt = 200, id = 2))

        assertEquals("backup", store.get(1)?.uuid)
    }

    @Test
    fun `a Pano plugin update is flagged from its first push to its end, and after a restart by its message`() {
        val store = ServerActiveTaskStore()

        store.markPanoPluginUpdate("p")

        assertTrue(store.isPanoPluginUpdate("p"))

        store.onTask(task("p", ServerTaskStatus.PENDING, kind = ServerTaskKind.PLUGIN_INSTALL))

        assertEquals(true, store.get(1)!!.toJsonObject().getBoolean("panoPluginUpdate"))

        // The node's own message replaces the one the update opened with; the flag stays.
        store.onTask(task("p", ServerTaskStatus.RUNNING, kind = ServerTaskKind.PLUGIN_INSTALL, percent = 40))

        assertTrue(store.get(1)!!.panoPluginUpdate)

        store.onTask(task("p", ServerTaskStatus.DONE, kind = ServerTaskKind.PLUGIN_INSTALL, percent = 100))

        assertNull(store.get(1))
        assertFalse(store.isPanoPluginUpdate("p"), "forgotten with its task")

        // Any other plugin install is not one.
        store.onTask(task("other", ServerTaskStatus.RUNNING, kind = ServerTaskKind.PLUGIN_INSTALL))

        assertEquals(false, store.get(1)!!.toJsonObject().getBoolean("panoPluginUpdate"))

        // After a restart the mark is gone; the row's opening message still tells.
        val restarted = ServerActiveTaskStore()

        restarted.seed(
            listOf(
                task("q", ServerTaskStatus.PENDING, kind = ServerTaskKind.PLUGIN_INSTALL, serverId = 2).copy(
                    message = "${ServerActiveTaskStore.PANO_PLUGIN_UPDATE_MESSAGE_PREFIX} to 1.2.0"
                )
            )
        )

        assertTrue(restarted.get(2)!!.panoPluginUpdate)

        restarted.onTask(task("q", ServerTaskStatus.RUNNING, kind = ServerTaskKind.PLUGIN_INSTALL, serverId = 2, percent = 10))

        assertTrue(restarted.get(2)!!.panoPluginUpdate, "sticky once recognised")
    }

    @Test
    fun `tasks without a server and restores waiting for a restart are not shown`() {
        val store = ServerActiveTaskStore()

        store.onTask(task("node", ServerTaskStatus.RUNNING, serverId = null, kind = ServerTaskKind.SELF_UPDATE))
        store.onTask(task("r", ServerTaskStatus.PENDING_RESTART, kind = ServerTaskKind.RESTORE))

        assertNull(store.get(1))
    }

    @Test
    fun `a seed never overwrites a frame nor resurrects a task that ended`() {
        val store = ServerActiveTaskStore()

        store.onTask(task("a", ServerTaskStatus.RUNNING, percent = 80))
        store.onTask(task("b", ServerTaskStatus.RUNNING, serverId = 2))
        store.onTask(task("b", ServerTaskStatus.DONE, serverId = 2))

        store.seed(
            listOf(
                task("a", ServerTaskStatus.RUNNING, percent = 10),
                task("b", ServerTaskStatus.RUNNING, serverId = 2),
                task("c", ServerTaskStatus.PENDING, serverId = 3)
            )
        )

        assertEquals(80, store.get(1)?.percent)
        assertNull(store.get(2))
        assertEquals("c", store.get(3)?.uuid)

        store.remove(3)
        assertNull(store.get(3))
    }
}
