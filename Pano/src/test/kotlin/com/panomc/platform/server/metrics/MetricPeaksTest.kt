package com.panomc.platform.server.metrics

import com.panomc.platform.server.dto.ServerMetricSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MetricPeaksTest {
    private fun sample(cpu: Double?, rx: Long? = null, tx: Long? = null) = ServerMetricSample(
        t = 1,
        tps = null,
        mspt = null,
        memUsed = 0,
        memMax = 0,
        cpu = cpu,
        playerCount = 0,
        maxPlayerCount = 0,
        players = emptyList(),
        netRx = rx,
        netTx = tx
    )

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
