package com.panomc.platform.node

import com.panomc.platform.node.event.NodeMetricsEvent
import com.panomc.platform.node.message.SetNodeMetricsIntervalMessage
import com.panomc.platform.server.metrics.MetricsRates
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The nodes page's refresh rate (SM-65, §2.4.30): which node is asked for what, the message it is
 * asked with, and the row writes a fast feed must not multiply.
 */
class NodeMetricsRateTest {
    @Test
    fun `the fastest watcher of a node wins, and "all nodes" means every connected one`() {
        val connected = listOf(1L, 2L, 3L)

        val wanted = MetricsRates.wanted(
            listOf(
                connected to 1_000L, // the nodes page, every node
                setOf(2L) to 500L, // node 2's detail page
                connected to 60_000L // a slow nodes page changes nothing
            )
        )

        assertEquals(mapOf(1L to 1_000L, 2L to 500L, 3L to 1_000L), wanted)

        val rates = MetricsRates()

        assertEquals(wanted, rates.reconcile(wanted))

        // Nothing changed, nothing sent.
        assertTrue(rates.reconcile(wanted).isEmpty())

        // The watcher left: every node is sent back to the default at once.
        assertEquals(
            mapOf(1L to MetricsRates.DEFAULT_INTERVAL_MS, 2L to MetricsRates.DEFAULT_INTERVAL_MS, 3L to MetricsRates.DEFAULT_INTERVAL_MS),
            rates.reconcile(emptyMap())
        )
    }

    @Test
    fun `a node that reconnects is told its rate again`() {
        val rates = MetricsRates()

        rates.reconcile(mapOf(7L to 1_000L))

        assertTrue(rates.reconcile(mapOf(7L to 1_000L)).isEmpty())

        rates.forget(7L)

        assertEquals(mapOf(7L to 1_000L), rates.reconcile(mapOf(7L to 1_000L)))
    }

    @Test
    fun `the interval is clamped like the server metrics one`() {
        assertEquals(500L, MetricsRates.clamp(10))
        assertEquals(60_000L, MetricsRates.clamp(3_600_000))
        assertEquals(MetricsRates.DEFAULT_INTERVAL_MS, MetricsRates.clamp(null))
        assertEquals(MetricsRates.DEFAULT_INTERVAL_MS, MetricsRates.clamp("fast"))
    }

    @Test
    fun `the message is SET_NODE_METRICS_INTERVAL and only protocol 4 nodes get it`() {
        val encoded = JsonObject(SetNodeMetricsIntervalMessage(1_000).encode())

        assertEquals("SET_NODE_METRICS_INTERVAL", encoded.getString("event"))
        assertEquals(1_000L, encoded.getLong("intervalMs"))
        assertEquals(4, NodeProtocol.NODE_METRICS_INTERVAL_VERSION)
    }

    @Test
    fun `a fast feed touches the node row at the default cadence only`() {
        assertTrue(NodeMetricsEvent.isDue(lastSeen = 0, now = 10_000))
        assertFalse(NodeMetricsEvent.isDue(lastSeen = 10_000, now = 10_500))
        assertFalse(NodeMetricsEvent.isDue(lastSeen = 10_000, now = 18_999))
        assertTrue(NodeMetricsEvent.isDue(lastSeen = 10_000, now = 19_000))
        // A clock that went backwards is not a reason to stop writing.
        assertTrue(NodeMetricsEvent.isDue(lastSeen = 50_000, now = 10_000))
    }
}
