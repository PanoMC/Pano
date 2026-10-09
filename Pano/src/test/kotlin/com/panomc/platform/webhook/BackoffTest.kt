package com.panomc.platform.webhook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class BackoffTest {
    @Test
    fun `default sequence is 30 s doubling up to the 1 h cap`() {
        val b = Backoff()
        val seconds = (1..10).map { b.baseDelayMs(it) / 1000 }
        assertEquals(listOf(30L, 60, 120, 240, 480, 960, 1920, 3600, 3600, 3600), seconds)
    }

    @Test
    fun `attempt 0 and negatives behave like the first attempt`() {
        val b = Backoff()
        assertEquals(30_000L, b.baseDelayMs(0))
        assertEquals(30_000L, b.baseDelayMs(-5))
        assertEquals(b.baseDelayMs(1), b.baseDelayMs(0))
    }

    @Test
    fun `attempts 0 to 20 are monotone and stop at the cap`() {
        for (cap in listOf(60_000L, 3_600_000L, 6L * 3_600_000L)) {
            val b = Backoff(baseMs = 30_000, capMs = cap)
            var prev = 0L
            for (n in 0..20) {
                val d = b.baseDelayMs(n)
                assertTrue(d >= prev, "attempt $n: $d < $prev")
                assertTrue(d <= cap, "attempt $n: $d > cap $cap")
                prev = d
            }
            assertEquals(cap, b.baseDelayMs(20))
        }
    }

    @Test
    fun `custom base and factor`() {
        val b = Backoff(baseMs = 60_000, factor = 3.0, capMs = 6L * 3_600_000L)
        assertEquals(listOf(60_000L, 180_000L, 540_000L), (1..3).map { b.baseDelayMs(it) })
    }

    @Test
    fun `huge attempt numbers do not overflow`() {
        val b = Backoff()
        assertEquals(b.capMs, b.baseDelayMs(10_000))
        assertEquals(b.capMs, b.baseDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun `jitter stays within plus or minus 20 percent for a seeded random`() {
        val b = Backoff()
        val r = Random(20251009)
        for (n in 0..20) {
            val range = b.bounds(n)
            repeat(500) {
                val d = b.delayMs(n, r)
                assertTrue(d in range, "attempt $n: $d not in $range")
            }
        }
        assertEquals(24_000L..36_000L, b.bounds(1))
    }

    @Test
    fun `jitter is deterministic for a seed and actually varies`() {
        val b = Backoff()
        val a = (1..20).map { b.delayMs(5, Random(7 + it)) }
        val again = (1..20).map { b.delayMs(5, Random(7 + it)) }
        assertEquals(a, again)
        assertTrue(a.toSet().size > 10, "jitter produced ${a.toSet().size} distinct values")
    }

    @Test
    fun `zero jitter returns the plain delay`() {
        val b = Backoff(jitter = 0.0)
        for (n in 1..10) assertEquals(b.baseDelayMs(n), b.delayMs(n, Random(1)))
    }

    @Test
    fun `invalid configuration is refused`() {
        assertThrows<IllegalArgumentException> { Backoff(baseMs = 0) }
        assertThrows<IllegalArgumentException> { Backoff(factor = 0.5) }
        assertThrows<IllegalArgumentException> { Backoff(baseMs = 10_000, capMs = 5_000) }
        assertThrows<IllegalArgumentException> { Backoff(jitter = 1.0) }
        assertThrows<IllegalArgumentException> { Backoff(jitter = -0.1) }
    }
}
