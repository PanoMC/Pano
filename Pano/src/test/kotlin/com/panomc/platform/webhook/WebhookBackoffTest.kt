package com.panomc.platform.webhook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** `delay = min(6 h, 30 s x 2^(attempts - 1))` x jitter [0.8, 1.2]; `Retry-After` on 429 / 503 (08 section 15.5). */
class WebhookBackoffTest {
    private val now = 1_760_000_000_000L

    @Test
    fun `base delays double from 30 s and stop at 6 h`() {
        val seconds = (1..14).map { WebhookBackoff.backoff.baseDelayMs(it) / 1000 }
        assertEquals(listOf(30L, 60, 120, 240, 480, 960, 1920, 3840, 7680, 15360, 21600, 21600, 21600, 21600), seconds)
    }

    @Test
    fun `with eight attempts the last retry is about 64 minutes after the first`() {
        // retries happen after attempts 1..7: 30 + 60 + 120 + 240 + 480 + 960 + 1920 s
        val total = (1..7).sumOf { WebhookBackoff.backoff.baseDelayMs(it) } / 1000
        assertEquals(3810L, total)
    }

    @Test
    fun `jitter stays within 20 percent for a seeded random, for attempts 0 to 20`() {
        val random = Random(42)
        for (attempt in 0..20) {
            val base = WebhookBackoff.backoff.baseDelayMs(attempt)
            repeat(200) {
                val d = WebhookBackoff.delayMs(attempt, 500, null, now, random)
                assertTrue(d >= Math.round(base * 0.8) && d <= Math.round(base * 1.2), "attempt $attempt delay $d base $base")
            }
        }
    }

    @Test
    fun `the delay never decreases from one attempt to the next at the base`() {
        var prev = 0L
        for (n in 0..20) {
            val d = WebhookBackoff.backoff.baseDelayMs(n)
            assertTrue(d >= prev)
            prev = d
        }
    }

    @Test
    fun `retry-after replaces the delay on 429 and 503 when larger, capped at one hour`() {
        val fixed = Random(1)
        val computed = WebhookBackoff.delayMs(1, 500, null, now, Random(1))
        assertTrue(computed in 24_000..36_000)

        assertEquals(120_000L, WebhookBackoff.delayMs(1, 429, "120", now, fixed))
        assertEquals(120_000L, WebhookBackoff.delayMs(1, 503, "120", now, Random(1)))
        assertEquals(3_600_000L, WebhookBackoff.delayMs(1, 429, "99999", now, Random(1)))
        // smaller than the computed backoff: ignored
        assertEquals(computed, WebhookBackoff.delayMs(1, 429, "5", now, Random(1)))
        // other statuses ignore the header
        assertEquals(computed, WebhookBackoff.delayMs(1, 500, "120", now, Random(1)))
    }

    @Test
    fun `retry-after accepts seconds and an http date`() {
        assertEquals(30_000L, WebhookBackoff.parseRetryAfterMs("30", now))
        assertEquals(0L, WebhookBackoff.parseRetryAfterMs("0", now))
        assertNull(WebhookBackoff.parseRetryAfterMs("-5", now))
        assertNull(WebhookBackoff.parseRetryAfterMs("soon", now))
        assertNull(WebhookBackoff.parseRetryAfterMs(null, now))
        assertNull(WebhookBackoff.parseRetryAfterMs("  ", now))

        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(now + 90_000).atZone(java.time.ZoneOffset.UTC)
        )
        val parsed = WebhookBackoff.parseRetryAfterMs(date, now)!!
        assertTrue(parsed in 89_000..90_000, "parsed $parsed")
        val past = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(now - 90_000).atZone(java.time.ZoneOffset.UTC)
        )
        assertEquals(0L, WebhookBackoff.parseRetryAfterMs(past, now))
    }
}
