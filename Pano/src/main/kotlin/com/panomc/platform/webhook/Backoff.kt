package com.panomc.platform.webhook

import kotlin.random.Random

/**
 * Retry delay for jobs (00 section 6 / 08 section 6): `min(cap, base * factor^(attempt - 1))`, then a random
 * factor in `[1 - jitter, 1 + jitter]`. The jitter is applied after the cap, so a capped delay still varies
 * by up to `jitter`. `attempt` counts from 1 (the first retry); 0 or less behaves like 1.
 *
 * Defaults are the delivery values: base 30 s, factor 2, cap 1 h, jitter 20 %. Webhooks pass their own base and cap.
 */
class Backoff(
    val baseMs: Long = 30_000L,
    val factor: Double = 2.0,
    val capMs: Long = 3_600_000L,
    val jitter: Double = 0.2,
) {
    init {
        require(baseMs > 0) { "baseMs must be positive" }
        require(factor >= 1.0) { "factor must be >= 1" }
        require(capMs >= baseMs) { "capMs must be >= baseMs" }
        require(jitter >= 0.0 && jitter < 1.0) { "jitter must be in [0, 1)" }
    }

    /** The delay without jitter. Non-decreasing in `attempt`, never above [capMs]. */
    fun baseDelayMs(attempt: Int): Long {
        val n = if (attempt < 1) 1 else attempt
        val raw = baseMs * Math.pow(factor, (n - 1).toDouble())
        return if (raw >= capMs.toDouble()) capMs else raw.toLong()
    }

    /** The delay with jitter drawn from [random]. */
    fun delayMs(attempt: Int, random: Random = Random.Default): Long {
        val d = baseDelayMs(attempt)
        if (jitter == 0.0) return d
        val f = 1.0 - jitter + 2.0 * jitter * random.nextDouble()
        return Math.round(d * f).coerceAtLeast(1L)
    }

    /** Lower and upper bound of [delayMs] for an attempt. */
    fun bounds(attempt: Int): LongRange {
        val d = baseDelayMs(attempt)
        return Math.round(d * (1.0 - jitter)).coerceAtLeast(1L)..Math.round(d * (1.0 + jitter))
    }
}
