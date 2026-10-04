package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Reading the runtime a node announces in `NODE_HELLO`.
 *
 * The distinction that matters is between "PROCESS" and "nothing usable": the first is a node
 * saying what it runs, the second is a node that said nothing Pano understands, and only the
 * first may overwrite what is already on the row.
 */
class NodeRuntimeTest {
    @Test
    fun `a runtime a node announces is taken as it is`() {
        assertEquals(NodeRuntime.DOCKER, NodeRuntime.fromIdOrNull("DOCKER"))
        assertEquals(NodeRuntime.PROCESS, NodeRuntime.fromIdOrNull("PROCESS"))
    }

    @Test
    fun `case and padding are the node's business, not Pano's`() {
        assertEquals(NodeRuntime.DOCKER, NodeRuntime.fromIdOrNull("docker"))
        assertEquals(NodeRuntime.DOCKER, NodeRuntime.fromIdOrNull("  Docker "))
    }

    @Test
    fun `anything Pano does not know is no answer at all`() {
        assertNull(NodeRuntime.fromIdOrNull(null))
        assertNull(NodeRuntime.fromIdOrNull(""))
        assertNull(NodeRuntime.fromIdOrNull("   "))
        assertNull(NodeRuntime.fromIdOrNull("PODMAN"))
        assertNull(NodeRuntime.fromIdOrNull("kubernetes"))
    }

    @Test
    fun `the old reader still answers every question with a runtime`() {
        assertEquals(NodeRuntime.PROCESS, NodeRuntime.fromId(null))
        assertEquals(NodeRuntime.PROCESS, NodeRuntime.fromId("PODMAN"))
        assertEquals(NodeRuntime.DOCKER, NodeRuntime.fromId("docker"))
    }
}
