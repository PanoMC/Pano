package com.panomc.platform.node

import com.panomc.platform.node.NodeServerStateReconciler.Change
import com.panomc.platform.node.NodeServerStateReconciler.Known
import com.panomc.platform.node.NodeServerStateReconciler.Reported
import com.panomc.platform.server.ServerProcessState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeServerStateReconcilerTest {
    @Test
    fun `reports nothing when the node agrees with the database`() {
        val known = listOf(Known(1, "a", ServerProcessState.RUNNING))

        assertTrue(NodeServerStateReconciler.reconcile(known, mapOf("a" to Reported("RUNNING"))).isEmpty())
    }

    @Test
    fun `believes the node over the database`() {
        val known = listOf(Known(1, "a", ServerProcessState.RUNNING))

        assertEquals(
            listOf(Change(1, ServerProcessState.STOPPED)),
            NodeServerStateReconciler.reconcile(known, mapOf("a" to Reported("STOPPED")))
        )
    }

    @Test
    fun `accepts a state for a server the database has no state for yet`() {
        val known = listOf(Known(1, "a", null))

        assertEquals(
            listOf(Change(1, ServerProcessState.RUNNING)),
            NodeServerStateReconciler.reconcile(known, mapOf("a" to Reported("RUNNING")))
        )
    }

    @Test
    fun `knocks an unreported running server down to stopped`() {
        val known = listOf(
            Known(1, "a", ServerProcessState.RUNNING),
            Known(2, "b", ServerProcessState.STARTING),
            Known(3, "c", ServerProcessState.STOPPING)
        )

        assertEquals(
            listOf(
                Change(1, ServerProcessState.STOPPED),
                Change(2, ServerProcessState.STOPPED),
                Change(3, ServerProcessState.STOPPED)
            ),
            NodeServerStateReconciler.reconcile(known, emptyMap())
        )
    }

    @Test
    fun `leaves an unreported install or crash alone`() {
        val known = listOf(
            Known(1, "a", ServerProcessState.INSTALLING),
            Known(2, "b", ServerProcessState.CRASHED),
            Known(3, "c", ServerProcessState.STOPPED),
            Known(4, "d", null)
        )

        assertTrue(NodeServerStateReconciler.reconcile(known, emptyMap()).isEmpty())
    }

    @Test
    fun `treats an unreadable state like no state at all`() {
        val known = listOf(Known(1, "a", ServerProcessState.RUNNING))

        assertEquals(
            listOf(Change(1, ServerProcessState.STOPPED)),
            NodeServerStateReconciler.reconcile(known, mapOf("a" to Reported("TELEPORTING")))
        )
    }

    @Test
    fun `ignores uuids that belong to no known server`() {
        val known = listOf(Known(1, "a", ServerProcessState.STOPPED))

        assertTrue(NodeServerStateReconciler.reconcile(known, mapOf("somebody-elses" to Reported("RUNNING"))).isEmpty())
    }

    @Test
    fun `reads a state case insensitively`() {
        val known = listOf(Known(1, "a", ServerProcessState.STOPPED))

        assertEquals(
            listOf(Change(1, ServerProcessState.RUNNING)),
            NodeServerStateReconciler.reconcile(known, mapOf("a" to Reported("running")))
        )
    }

    @Test
    fun `keeps an adopted running server running and records that it is adopted`() {
        val known = listOf(Known(1, "a", ServerProcessState.RUNNING))

        // The bug SM-51 fixes lived exactly here: the node reports a process it inherited, and
        // reconciliation used to be the thing that decided it was not running after all.
        assertEquals(
            listOf(Change(1, ServerProcessState.RUNNING, adopted = true, stdinAvailable = false)),
            NodeServerStateReconciler.reconcile(
                known,
                mapOf("a" to Reported("RUNNING", adopted = true, stdinAvailable = false))
            )
        )
    }

    @Test
    fun `reports nothing when the adoption flags already match`() {
        val known = listOf(Known(1, "a", ServerProcessState.RUNNING, adopted = true, stdinAvailable = false))

        assertTrue(
            NodeServerStateReconciler.reconcile(
                known,
                mapOf("a" to Reported("RUNNING", adopted = true, stdinAvailable = false))
            ).isEmpty()
        )
    }

    @Test
    fun `clears the adoption flags once the server was started properly again`() {
        val known = listOf(Known(1, "a", ServerProcessState.RUNNING, adopted = true, stdinAvailable = false))

        assertEquals(
            listOf(Change(1, ServerProcessState.RUNNING, adopted = false, stdinAvailable = true)),
            NodeServerStateReconciler.reconcile(known, mapOf("a" to Reported("RUNNING")))
        )
    }

    @Test
    fun `clears the adoption flags for a server the node no longer runs`() {
        val known = listOf(Known(1, "a", ServerProcessState.RUNNING, adopted = true, stdinAvailable = false))

        assertEquals(
            listOf(Change(1, ServerProcessState.STOPPED, adopted = false, stdinAvailable = true)),
            NodeServerStateReconciler.reconcile(known, emptyMap())
        )
    }

    @Test
    fun `reports nothing for a node that runs nothing`() {
        assertTrue(NodeServerStateReconciler.reconcile(emptyList(), mapOf("a" to Reported("RUNNING"))).isEmpty())
    }

    @Test
    fun `carries the exit code the node booked while it was down`() {
        val known = listOf(Known(1, "a", ServerProcessState.RUNNING), Known(2, "b", ServerProcessState.RUNNING))

        assertEquals(
            listOf(
                Change(1, ServerProcessState.STOPPED, exitCode = 0),
                Change(2, ServerProcessState.CRASHED, exitCode = 137)
            ),
            NodeServerStateReconciler.reconcile(
                known,
                mapOf("a" to Reported("STOPPED", exitCode = 0), "b" to Reported("CRASHED", exitCode = 137))
            )
        )
    }

    @Test
    fun `a new exit code alone is a change, a known one is not`() {
        val known = listOf(Known(1, "a", ServerProcessState.STOPPED, lastExitCode = null))

        assertEquals(
            listOf(Change(1, ServerProcessState.STOPPED, exitCode = 0)),
            NodeServerStateReconciler.reconcile(known, mapOf("a" to Reported("STOPPED", exitCode = 0)))
        )

        val booked = listOf(Known(1, "a", ServerProcessState.STOPPED, lastExitCode = 0))

        assertTrue(NodeServerStateReconciler.reconcile(booked, mapOf("a" to Reported("STOPPED", exitCode = 0))).isEmpty())
        // A node that does not know the code leaves Pano's alone.
        assertTrue(NodeServerStateReconciler.reconcile(booked, mapOf("a" to Reported("STOPPED"))).isEmpty())
    }

    @Test
    fun `a running server never takes an exit code`() {
        val known = listOf(Known(1, "a", ServerProcessState.STOPPED, lastExitCode = 1))

        assertEquals(
            listOf(Change(1, ServerProcessState.RUNNING)),
            NodeServerStateReconciler.reconcile(known, mapOf("a" to Reported("RUNNING", exitCode = 1)))
        )
    }

    @Test
    fun `a flag-only change keeps the exit code Pano has`() {
        val known = listOf(Known(1, "a", ServerProcessState.CRASHED, stdinAvailable = false, lastExitCode = 1))

        assertEquals(
            listOf(Change(1, ServerProcessState.CRASHED, exitCode = 1)),
            NodeServerStateReconciler.reconcile(known, mapOf("a" to Reported("CRASHED")))
        )
    }
}
