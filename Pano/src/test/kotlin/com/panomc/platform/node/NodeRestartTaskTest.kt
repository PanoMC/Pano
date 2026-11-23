package com.panomc.platform.node

import com.panomc.platform.db.model.ServerTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tasks and node restarts: which tasks a reconnecting node dropped ([ServerTaskAbandonment]) and
 * which hold an automatic update ([NodeDaemonUpdateService.busyTasks]).
 *
 * The bug: koyluler.tr, 1.5.0-alpha.2. The automatic update went out, a plugin update started
 * three seconds later, the node restarted under it, and the task stayed RUNNING for ten minutes.
 */
class NodeRestartTaskTest {
    private val helloAt = 1_000_000L

    private fun task(
        uuid: String = "t1",
        nodeId: Long? = 1,
        status: ServerTaskStatus = ServerTaskStatus.RUNNING,
        updatedAt: Long = helloAt - 5_000
    ) = ServerTask(
        uuid = uuid,
        serverId = 7,
        nodeId = nodeId,
        kind = ServerTaskKind.PLUGIN_INSTALL,
        status = status,
        createdBy = 1,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    @Test
    fun `a running task a freshly started node does not list is abandoned`() {
        assertTrue(ServerTaskAbandonment.isAbandoned(task(), 1, emptySet(), helloAt))
    }

    @Test
    fun `a task the node still lists is not abandoned`() {
        // A network blip, not a restart: the daemon is still downloading.
        assertFalse(ServerTaskAbandonment.isAbandoned(task(), 1, setOf("t1"), helloAt))
    }

    @Test
    fun `another node's task is left alone`() {
        assertFalse(ServerTaskAbandonment.isAbandoned(task(nodeId = 2), 1, emptySet(), helloAt))
        assertFalse(ServerTaskAbandonment.isAbandoned(task(nodeId = null), 1, emptySet(), helloAt))
    }

    @Test
    fun `pending, armed and finished tasks are left alone`() {
        listOf(
            ServerTaskStatus.PENDING,
            ServerTaskStatus.PENDING_RESTART,
            ServerTaskStatus.DONE,
            ServerTaskStatus.FAILED
        ).forEach { status ->
            assertFalse(ServerTaskAbandonment.isAbandoned(task(status = status), 1, emptySet(), helloAt), status.name)
        }
    }

    @Test
    fun `a task that reported after the hello was built is not abandoned`() {
        // Its first RUNNING frame was handled while the hello still was.
        assertFalse(ServerTaskAbandonment.isAbandoned(task(updatedAt = helloAt), 1, emptySet(), helloAt))
        assertFalse(ServerTaskAbandonment.isAbandoned(task(updatedAt = helloAt + 10), 1, emptySet(), helloAt))
    }

    @Test
    fun `pending and running tasks of the node hold its automatic update, an armed restore does not`() {
        val tasks = listOf(
            task(uuid = "running", status = ServerTaskStatus.RUNNING),
            task(uuid = "pending", status = ServerTaskStatus.PENDING),
            task(uuid = "armed", status = ServerTaskStatus.PENDING_RESTART),
            task(uuid = "other-node", nodeId = 2),
            task(uuid = "done", status = ServerTaskStatus.DONE)
        )

        assertEquals(listOf("running", "pending"), NodeDaemonUpdateService.busyTasks(1, tasks).map { it.uuid })
        assertTrue(NodeDaemonUpdateService.busyTasks(3, tasks).isEmpty())
    }
}
