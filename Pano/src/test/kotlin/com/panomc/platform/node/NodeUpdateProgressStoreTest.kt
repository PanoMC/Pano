package com.panomc.platform.node

import com.panomc.platform.node.NodeUpdateProgressStore.Companion.DONE_KEEP_MS
import com.panomc.platform.node.NodeUpdateProgressStore.Companion.FAILED_KEEP_MS
import com.panomc.platform.node.NodeUpdateProgressStore.Companion.MAX_IDLE_MS
import com.panomc.platform.node.NodeUpdateProgressStore.Companion.STAGED_RESTART_WAIT_MS
import com.panomc.platform.node.NodeUpdateProgressStore.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A daemon update's progress (SM-77): start, the node's SELF_UPDATE frames, the disconnect of its
 * restart, the next hello, and the clock that clears what ended. Pure, with the time passed in.
 */
class NodeUpdateProgressStoreTest {
    private val sha = "a".repeat(64)
    private val oldSha = "b".repeat(64)

    private fun started(store: NodeUpdateProgressStore = NodeUpdateProgressStore(), now: Long = 1_000L) =
        store.also { it.start(nodeId = 7, nodeName = "eu-1", agent = false, version = "1.2.0", sha256 = sha, now = now) }

    private fun NodeUpdateProgressStore.frame(status: String, percent: Int? = null, message: String? = null, error: String? = null, now: Long) =
        onFrame(7, "eu-1", false, "1.2.0", status, percent, message, error, now)

    @Test
    fun `an update runs from 0 through the frames to staged, restarts, and is done on the next hello`() {
        val store = started()

        assertEquals(Status.RUNNING, store.get(7)!!.status)
        assertEquals(0, store.get(7)!!.percent)
        assertNull(store.get(8))

        store.frame("RUNNING", 5, "Downloading 1.2.0", now = 1_100)
        store.frame("RUNNING", 45, "Downloading 1.2.0", now = 1_200)

        val downloading = store.get(7)!!

        assertEquals(Status.RUNNING, downloading.status)
        assertEquals(45, downloading.percent)
        assertEquals("Downloading 1.2.0", downloading.message)
        assertEquals(1_200L, downloading.updatedAt)
        assertEquals(1_000L, downloading.startedAt)

        // Never backwards, and a frame without a message keeps the last one.
        store.frame("RUNNING", 30, null, now = 1_300)

        assertEquals(45, store.get(7)!!.percent)
        assertEquals("Downloading 1.2.0", store.get(7)!!.message)

        val staged = store.frame("DONE", 100, "Staged 1.2.0", now = 1_400)!!

        assertEquals(Status.RUNNING, staged.status, "staged is still running: the restart is next")
        assertTrue(staged.staged)
        assertEquals(100, staged.percent)
        assertNull(store.frame("RUNNING", 50, now = 1_450), "a RUNNING after staged is a stray")

        val restarting = store.onDisconnect(7, now = 1_500)!!

        assertEquals(Status.RESTARTING, restarting.status)
        assertNull(store.onDisconnect(7, now = 1_600), "a second close changes nothing")
        assertNull(store.frame("RUNNING", 60, now = 1_650), "no frame moves a restart")

        val done = store.onHello(7, "1.2.0", sha, now = 5_000)!!

        assertEquals(Status.DONE, done.status)
        assertEquals(100, done.percent)
        assertEquals("1.2.0", done.version)
        assertNull(store.onHello(7, "1.2.0", sha, now = 5_100), "done is done")
    }

    @Test
    fun `done stays ten seconds, failed a minute, and the sweep says which nodes changed`() {
        val store = started()

        store.onDisconnect(7, now = 2_000)
        store.onHello(7, "1.2.0", sha, now = 3_000)

        assertEquals(emptySet<Long>(), store.sweep(3_000 + DONE_KEEP_MS - 1))
        assertNotNull(store.get(7))

        assertEquals(setOf(7L), store.sweep(3_000 + DONE_KEEP_MS))
        assertNull(store.get(7))
        assertTrue(store.isEmpty())

        started(store, now = 10_000)

        val failed = store.frame("FAILED", error = "The downloaded jar does not match the published checksum.", now = 11_000)!!

        assertEquals(Status.FAILED, failed.status)
        assertEquals("The downloaded jar does not match the published checksum.", failed.message)
        assertNull(store.onDisconnect(7, now = 11_500), "a failed update does not restart")
        assertNull(store.onHello(7, "1.1.0", oldSha, now = 12_000))

        assertEquals(emptySet<Long>(), store.sweep(11_000 + FAILED_KEEP_MS - 1))
        assertEquals(Status.FAILED, store.get(7)!!.status)
        assertEquals(setOf(7L), store.sweep(11_000 + FAILED_KEEP_MS))
        assertNull(store.get(7))
    }

    @Test
    fun `a failed frame without an error keeps the message, and a new start replaces a failure`() {
        val store = started()

        store.frame("FAILED", message = "This node was not started from a jar", now = 1_100)

        assertEquals("This node was not started from a jar", store.get(7)!!.message)

        started(store, now = 2_000)

        val again = store.get(7)!!

        assertEquals(Status.RUNNING, again.status)
        assertEquals(0, again.percent)
        assertNull(again.message)
        assertEquals(2_000L, again.startedAt)
    }

    @Test
    fun `a disconnect mid-download is a restart too, and the hello after it ends the update`() {
        val store = started()

        store.frame("RUNNING", 40, "Downloading 1.2.0", now = 1_100)

        val restarting = store.onDisconnect(7, now = 1_200)!!

        assertEquals(Status.RESTARTING, restarting.status)
        assertEquals(40, restarting.percent)
        assertNull(restarting.message)

        // A node too old to report a checksum: any hello after the restart is taken as done.
        assertEquals(Status.DONE, store.onHello(7, "1.1.0", null, now = 3_000)!!.status)
    }

    @Test
    fun `a hello proving the old daemon came back fails the update, anything less is done`() {
        val store = started()

        store.onDisconnect(7, now = 2_000)

        val failed = store.onHello(7, "1.1.0", oldSha, now = 3_000)!!

        assertEquals(Status.FAILED, failed.status)
        assertEquals(NodeUpdateProgressStore.OLD_DAEMON_MESSAGE, failed.message)

        // Development builds share one version: a different checksum alone proves nothing.
        started(store, now = 4_000)
        store.onDisconnect(7, now = 4_100)

        assertEquals(Status.DONE, store.onHello(7, "1.2.0", oldSha, now = 4_200)!!.status)
    }

    @Test
    fun `a hello while running ends it only when it comes from the jar that was sent`() {
        val store = started()

        store.frame("RUNNING", 20, now = 1_100)

        assertNull(store.onHello(7, "1.1.0", oldSha, now = 1_200), "a reconnect of the old daemon mid-download")
        assertEquals(Status.RUNNING, store.get(7)!!.status)

        assertEquals(Status.DONE, store.onHello(7, "1.2.0", sha.uppercase(), now = 1_300)!!.status)
    }

    @Test
    fun `a staged update that never restarts is done after the wait, and nothing lives past ten idle minutes`() {
        val store = started()

        store.frame("DONE", 100, "Already up to date", now = 1_000)

        assertEquals(emptySet<Long>(), store.sweep(1_000 + STAGED_RESTART_WAIT_MS - 1))
        assertEquals(setOf(7L), store.sweep(1_000 + STAGED_RESTART_WAIT_MS))
        assertEquals(Status.DONE, store.get(7)!!.status)

        val idle = NodeUpdateProgressStore()

        started(idle, now = 0)
        idle.onDisconnect(7, now = 0)

        assertEquals(emptySet<Long>(), idle.sweep(MAX_IDLE_MS - 1), "restarting is kept while it may come back")
        assertEquals(setOf(7L), idle.sweep(MAX_IDLE_MS))
        assertNull(idle.get(7))

        started(idle, now = 0)

        assertEquals(setOf(7L), idle.sweep(MAX_IDLE_MS), "a RUNNING nothing reported on for ten minutes goes too")
    }

    @Test
    fun `a frame for an update this Pano did not start opens one, and unknown statuses change nothing`() {
        val store = NodeUpdateProgressStore()

        assertNull(store.onFrame(9, "agent-9", true, "1.2.0", "SOMETHING", 10, null, null, now = 100))
        assertNull(store.get(9))

        val opened = store.onFrame(9, "agent-9", true, "1.2.0", "RUNNING", 250, "Downloading 1.2.0", null, now = 100)!!

        assertEquals(Status.RUNNING, opened.status)
        assertEquals(100, opened.percent, "clamped")
        assertEquals("1.2.0", opened.version)
        assertTrue(opened.agent)

        assertNull(store.onDisconnect(10, now = 200), "a node that is not updating")
        assertNull(store.onHello(10, "1.2.0", sha, now = 200))

        store.forget(9)

        assertNull(store.get(9))
    }

    @Test
    fun `the node and server JSON carry exactly the agreed fields`() {
        val store = started()

        store.frame("RUNNING", 35, "Downloading 1.2.0", now = 1_100)

        val progress = store.get(7)!!

        val node = progress.toNodeJsonObject()

        assertEquals(setOf("version", "status", "percent", "message"), node.fieldNames())
        assertEquals("1.2.0", node.getString("version"))
        assertEquals("RUNNING", node.getString("status"))
        assertEquals(35, node.getInteger("percent"))
        assertEquals("Downloading 1.2.0", node.getString("message"))

        val server = progress.toServerJsonObject()

        assertEquals(setOf("kind", "nodeId", "nodeName", "version", "status", "percent", "message"), server.fieldNames())
        assertEquals("node", server.getString("kind"))
        assertEquals(7L, server.getLong("nodeId"))
        assertEquals("eu-1", server.getString("nodeName"))

        store.start(8, "smp", agent = true, version = "1.2.0", sha256 = null, now = 0)

        assertEquals("agent", store.get(8)!!.toServerJsonObject().getString("kind"))
        assertFalse(store.get(8)!!.staged)
    }
}
