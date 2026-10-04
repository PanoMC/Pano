package com.panomc.platform.server.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import io.vertx.core.json.JsonArray
import org.junit.jupiter.api.Test

/**
 * Which servers are asked to report faster, and what they are told (§2.4.23 A).
 */
class MetricsRatesTest {
    private val rates = MetricsRates()

    @Test
    fun `a subscribe frame's interval is clamped, and anything that is not a number is the default`() {
        assertEquals(1_000L, MetricsRates.clamp(1_000))
        assertEquals(500L, MetricsRates.clamp(500))
        assertEquals(500L, MetricsRates.clamp(10))
        assertEquals(60_000L, MetricsRates.clamp(3_600_000))
        assertEquals(2_500L, MetricsRates.clamp(2_500.7))
        assertEquals(MetricsRates.DEFAULT_INTERVAL_MS, MetricsRates.clamp(null))
        assertEquals(MetricsRates.DEFAULT_INTERVAL_MS, MetricsRates.clamp("1000"))
        assertEquals(MetricsRates.DEFAULT_INTERVAL_MS, MetricsRates.clamp(Double.NaN))
    }

    @Test
    fun `the fastest watcher wins, but nobody slows a server below the default`() {
        assertEquals(1_000L, MetricsRates.effective(listOf(5_000, 1_000, 60_000)))
        assertEquals(MetricsRates.DEFAULT_INTERVAL_MS, MetricsRates.effective(listOf(30_000, 60_000)))
        assertEquals(MetricsRates.DEFAULT_INTERVAL_MS, MetricsRates.effective(emptyList()))
    }

    @Test
    fun `a new fast watcher is one command, and the same picture again is none`() {
        assertEquals(mapOf(7L to 1_000L), rates.reconcile(mapOf(7L to 1_000L)))
        assertTrue(rates.reconcile(mapOf(7L to 1_000L)).isEmpty())
    }

    @Test
    fun `a change in the fastest watcher is sent`() {
        rates.reconcile(mapOf(7L to 1_000L))

        assertEquals(mapOf(7L to 5_000L), rates.reconcile(mapOf(7L to 5_000L)))
    }

    @Test
    fun `the last fast watcher leaving sends the server back to the default at once`() {
        rates.reconcile(mapOf(7L to 1_000L, 8L to 2_000L))

        assertEquals(mapOf(7L to MetricsRates.DEFAULT_INTERVAL_MS), rates.reconcile(mapOf(8L to 2_000L)))

        // And a watcher that only wants the default is no watcher as far as the source goes.
        assertEquals(mapOf(8L to MetricsRates.DEFAULT_INTERVAL_MS), rates.reconcile(mapOf(8L to 30_000L)))
        assertTrue(rates.renewals().isEmpty())
    }

    @Test
    fun `a slow watcher never commands anything`() {
        assertTrue(rates.reconcile(mapOf(7L to 30_000L, 8L to 10_000L)).isEmpty())
    }

    @Test
    fun `the lease re-sends every fast server and nothing else`() {
        rates.reconcile(mapOf(7L to 1_000L, 8L to 2_000L, 9L to 60_000L))

        assertEquals(mapOf(7L to 1_000L, 8L to 2_000L), rates.renewals())
    }

    @Test
    fun `the lease the source enforces outlives the re-send`() {
        assertTrue(MetricsRates.LEASE_MS > MetricsRates.RENEW_MS)
    }

    @Test
    fun `a subscribe frame's server ids are numbers, each once, at most a hundred`() {
        assertEquals(listOf(3L, 1L, 2L), MetricsRates.serverIds(JsonArray(listOf(3, 1, 3, "x", null, 2.0, 2))))
        assertEquals(emptyList<Long>(), MetricsRates.serverIds(null))
        assertEquals(emptyList<Long>(), MetricsRates.serverIds(7))
        assertEquals(emptyList<Long>(), MetricsRates.serverIds(JsonArray()))

        val many = MetricsRates.serverIds(JsonArray((1..250).toList()))

        assertEquals(MetricsRates.MAX_SUBSCRIBED_SERVERS, many.size)
        assertEquals((1L..100L).toList(), many)
    }

    @Test
    fun `a many-server watcher folds in like single-server watchers, fastest wins`() {
        val wanted = MetricsRates.wanted(
            listOf(
                listOf(1L, 2L, 3L) to 1_000L,
                listOf(2L) to 500L,
                listOf(3L, 4L) to 5_000L,
                emptyList<Long>() to 500L
            )
        )

        assertEquals(mapOf(1L to 1_000L, 2L to 500L, 3L to 1_000L, 4L to 5_000L), wanted)
    }

    @Test
    fun `servers go back to the default when the many-server watcher leaves`() {
        val modal = listOf(1L, 2L, 3L) to 1_000L
        val single = listOf(2L) to 500L

        assertEquals(
            mapOf(1L to 1_000L, 2L to 500L, 3L to 1_000L),
            rates.reconcile(MetricsRates.wanted(listOf(modal, single)))
        )

        // The modal closes: its servers slow down, the one still watched on its own keeps its rate.
        assertEquals(
            mapOf(1L to MetricsRates.DEFAULT_INTERVAL_MS, 3L to MetricsRates.DEFAULT_INTERVAL_MS),
            rates.reconcile(MetricsRates.wanted(listOf(single)))
        )

        assertEquals(mapOf(2L to MetricsRates.DEFAULT_INTERVAL_MS), rates.reconcile(MetricsRates.wanted(emptyList())))
        assertTrue(rates.renewals().isEmpty())
    }
}
