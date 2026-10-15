package com.panomc.platform.webhook

import com.panomc.platform.db.model.WebhookDeliveryStatus
import kotlin.random.Random

/**
 * What one HTTP attempt produced. [statusCode] is null when no response arrived; [error] then (or for a 3xx) names why
 * (`TIMEOUT`, `IO:<class>`, `URL_GUARD:<reason>`, `REDIRECT_NOT_FOLLOWED`, `SECRET_UNREADABLE`, ...). [retryable] is false
 * for a failure that another attempt cannot fix (a refused URL, an unreadable secret).
 */
class Attempt(
    val statusCode: Int? = null,
    val error: String? = null,
    val response: String? = null,
    val durationMs: Int? = null,
    val retryAfter: String? = null,
    val retryable: Boolean = true
)

/** The new state of a delivery row after an [Attempt] (08 section 15.5). */
class Decision(
    val status: WebhookDeliveryStatus,
    val nextAttemptAt: Long?,
    val lastError: String?,
    /** `true` only for a 2xx: it resets the endpoint's `failureCount`; every other outcome adds one. */
    val success: Boolean,
    val deliveredAt: Long?
)

/** The pure result rules of `WebhookJob` (08 section 15.5). */
object WebhookOutcome {
    /**
     * [attempts] counts the attempt just made (the claim already incremented it); [nowMs] is the time it finished.
     * 2xx = `SUCCEEDED`; 410 = `DEAD (GONE)`; a non-retryable failure = `DEAD`; anything else = `FAILED` with a backoff
     * delay, or `DEAD` when [attempts] reached [maxAttempts].
     */
    fun decide(attempt: Attempt, attempts: Int, maxAttempts: Int, nowMs: Long, random: Random = Random.Default): Decision {
        val code = attempt.statusCode

        if (code != null && code in 200..299) {
            return Decision(WebhookDeliveryStatus.SUCCEEDED, null, null, success = true, deliveredAt = nowMs)
        }
        if (code == 410) return Decision(WebhookDeliveryStatus.DEAD, null, "GONE", success = false, deliveredAt = null)

        val error = attempt.error ?: when {
            code == null -> "NO_RESPONSE"
            code in 300..399 -> "REDIRECT_NOT_FOLLOWED"
            else -> "HTTP_$code"
        }

        if (!attempt.retryable || attempts >= maxAttempts) {
            return Decision(WebhookDeliveryStatus.DEAD, null, error, success = false, deliveredAt = null)
        }

        val delay = WebhookBackoff.delayMs(attempts, code, attempt.retryAfter, nowMs, random)

        return Decision(WebhookDeliveryStatus.FAILED, nowMs + delay, error, success = false, deliveredAt = null)
    }
}
