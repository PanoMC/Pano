package com.panomc.platform.server.metrics

import com.panomc.platform.server.dto.ServerMetricSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MetricPeaksTest {
    private fun sample(cpu: Double?, rx: Long? = null, tx: Long? = null, processCpu: Double? = null) = ServerMetricSample(
        t = 1,
        tps = null,
        mspt = null,
        memUsed = 0,
        memMax = 0,
        cpu = cpu,
        playerCount = 0,
        maxPlayerCount = 0,
        players = emptyList(),
        processCpu = processCpu,
        netRx = rx,
        netTx = tx
    )

    @Test
    fun `a server with no plugin peaks on the node's view of the process`() {
        val peaks = MetricPeaks().with(sample(null, processCpu = 12.0)).with(sample(null, processCpu = 40.0))

        assertEquals(40.0, peaks.cpu)
    }

    @Test
    fun `the plugin's reading outranks the node's in the same sample`() {
        assertEquals(3.0, MetricPeaks().with(sample(3.0, processCpu = 9.0)).cpu)
    }

    @Test
    fun `a spike between quiet samples is what the minute keeps`() {
        val peaks = listOf(sample(0.4), sample(31.0), sample(0.3))
            .fold(MetricPeaks()) { acc, next -> acc.with(next) }

        assertEquals(31.0, peaks.cpu)
    }

    @Test
    fun `a sample without a value leaves that peak alone`() {
        val peaks = MetricPeaks().with(sample(2.0, rx = 500, tx = 10)).with(sample(null))

        assertEquals(2.0, peaks.cpu)
        assertEquals(500L, peaks.netRx)
        assertEquals(10L, peaks.netTx)
    }

    @Test
    fun `nothing measured stays nothing`() {
        val peaks = MetricPeaks().with(sample(null))

        assertNull(peaks.cpu)
        assertNull(peaks.netRx)
    }
}
