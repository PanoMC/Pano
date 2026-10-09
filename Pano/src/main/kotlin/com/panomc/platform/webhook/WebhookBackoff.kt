package com.panomc.platform.webhook

import com.panomc.platform.webhook.Backoff
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Retry delay of webhooks (08 section 15.5): `min(6 h, 30 s x 2^(attempts - 1))` times a jitter in
 * `[0.8, 1.2]`; a `Retry-After` header (seconds or HTTP date) on 429 / 503 replaces it when larger, capped at 1 h.
 */
object WebhookBackoff {
    const val RETRY_AFTER_CAP_MS = 3_600_000L

    val backoff = Backoff(baseMs = 30_000L, factor = 2.0, capMs = 6L * 3_600_000L, jitter = 0.2)

    /** [attempts] is the number of attempts made so far, this one included (1 after the first failure). */
    fun delayMs(attempts: Int, statusCode: Int?, retryAfterHeader: String?, nowMs: Long, random: Random = Random.Default): Long {
        val computed = backoff.delayMs(attempts, random)
        if (statusCode != 429 && statusCode != 503) return computed
        val advised = parseRetryAfterMs(retryAfterHeader, nowMs) ?: return computed
        return if (advised > computed) advised.coerceAtMost(RETRY_AFTER_CAP_MS) else computed
    }

    /** Milliseconds from [nowMs] the header asks to wait, `null` when absent or unreadable. Never negative. */
    fun parseRetryAfterMs(header: String?, nowMs: Long): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return if (it < 0) null else minOf(it, 100_000L) * 1000L }
        return try {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (Instant.ofEpochMilli(nowMs).until(at, java.time.temporal.ChronoUnit.MILLIS)).coerceAtLeast(0L)
        } catch (e: Exception) {
            null
        }
    }
}
