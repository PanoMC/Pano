package com.panomc.platform.server.metrics

import com.panomc.platform.node.dto.NodeMetricSample
import com.panomc.platform.server.dto.ServerMetricSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one figure the metrics endpoints add to a live sample: the memory of the host the server
 * runs on, which is what a node-sourced RAM reading has to be divided by (§2.4.18 B).
 */
class ServerLatestMetricsTest {
    @Test
    fun `the node's host memory rides along with the sample`() {
        val json = ServerLatestMetrics.latestJson(sample(), 67_179_872_256)

        assertEquals(67_179_872_256L, json?.getLong("hostMemTotal"))
        // Everything the sample already said is still there.
        assertEquals(1_073_741_824L, json?.getLong("memUsed"))
        assertEquals("node", json?.getString("source"))
    }

    @Test
    fun `a server with no node says so rather than guessing a denominator`() {
        val json = ServerLatestMetrics.latestJson(sample(), null)

        // Present and null: the panel draws an empty ring with the value in the centre, which is
        // the honest answer for a linked server nobody can measure a host for.
        assertTrue(json!!.containsKey("hostMemTotal"))
        assertNull(json.getLong("hostMemTotal"))
    }

    @Test
    fun `a server that has reported nothing has no latest sample at all`() {
        assertNull(ServerLatestMetrics.latestJson(null, 67_179_872_256))
    }

    @Test
    fun `the host memory comes from the node's own frame`() {
        assertEquals(67_179_872_256L, ServerLatestMetrics.hostMemTotal(nodeMetrics(67_179_872_256)))
    }

    @Test
    fun `an offline node or one that never reported has none`() {
        // NodeManager drops a node's sample the moment it disconnects, so "no sample" is exactly
        // "this node is not there right now".
        assertNull(ServerLatestMetrics.hostMemTotal(null))
    }

    @Test
    fun `a host whose memory could not be read is not a host with no memory`() {
        // The node metric sample coerces an unreadable figure to zero; dividing by it would be a
        // gauge pinned at infinity.
        assertNull(ServerLatestMetrics.hostMemTotal(nodeMetrics(0)))
    }

    private fun sample() = ServerMetricSample(
        t = 1,
        tps = null,
        mspt = null,
        memUsed = 1_073_741_824,
        memMax = 4_294_967_296,
        cpu = null,
        playerCount = 0,
        maxPlayerCount = 20,
        players = emptyList(),
        processCpu = 3.5,
        memRss = 1_073_741_824,
        source = ServerMetricSample.SOURCE_NODE
    )

    private fun nodeMetrics(memTotal: Long) = NodeMetricSample(
        t = 1,
        cpu = 5.0,
        memUsed = 8_589_934_592,
        memTotal = memTotal,
        diskUsed = 100,
        diskTotal = 500
    )
}
