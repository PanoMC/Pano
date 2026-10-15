package com.panomc.platform.webhook

import com.panomc.platform.db.model.WebhookDeliveryStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** The result rules of `WebhookJob` (08 section 15.5). */
class WebhookOutcomeTest {
    private val now = 1_760_000_000_000L

    private fun decide(a: Attempt, attempts: Int = 1, max: Int = 8) = WebhookOutcome.decide(a, attempts, max, now, Random(7))

    @Test
    fun `every 2xx succeeds and resets the endpoint`() {
        for (code in listOf(200, 201, 204, 299)) {
            val d = decide(Attempt(statusCode = code))
            assertEquals(WebhookDeliveryStatus.SUCCEEDED, d.status, "$code")
            assertTrue(d.success)
            assertEquals(now, d.deliveredAt)
            assertNull(d.nextAttemptAt)
            assertNull(d.lastError)
        }
    }

    @Test
    fun `410 is dead at once with GONE`() {
        val d = decide(Attempt(statusCode = 410))
        assertEquals(WebhookDeliveryStatus.DEAD, d.status)
        assertEquals("GONE", d.lastError)
        assertFalse(d.success)
    }

    @Test
    fun `4xx, 5xx, 3xx and transport errors retry with a backoff until the attempts run out`() {
        for (a in listOf(Attempt(statusCode = 400), Attempt(statusCode = 404), Attempt(statusCode = 500), Attempt(statusCode = 302), Attempt(error = "TIMEOUT"), Attempt(error = "IO:ConnectException"))) {
            val d = decide(a, attempts = 2)
            assertEquals(WebhookDeliveryStatus.FAILED, d.status)
            assertFalse(d.success)
            assertNotNull(d.nextAttemptAt)
            assertTrue(d.nextAttemptAt!! > now)
            assertNull(d.deliveredAt)
        }
        assertEquals("HTTP_500", decide(Attempt(statusCode = 500)).lastError)
        assertEquals("REDIRECT_NOT_FOLLOWED", decide(Attempt(statusCode = 302)).lastError)
        assertEquals("TIMEOUT", decide(Attempt(error = "TIMEOUT")).lastError)
        assertEquals("NO_RESPONSE", decide(Attempt()).lastError)
    }

    @Test
    fun `the last allowed attempt that fails ends dead`() {
        val d = decide(Attempt(statusCode = 500), attempts = 8, max = 8)
        assertEquals(WebhookDeliveryStatus.DEAD, d.status)
        assertEquals("HTTP_500", d.lastError)
        assertNull(d.nextAttemptAt)
        assertEquals(WebhookDeliveryStatus.DEAD, decide(Attempt(statusCode = 500), attempts = 1, max = 1).status)
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, decide(Attempt(statusCode = 200), attempts = 8, max = 8).status)
    }

    @Test
    fun `a failure that cannot be fixed by retrying is dead at once`() {
        val d = decide(Attempt(error = "URL_GUARD:PRIVATE_ADDRESS", retryable = false), attempts = 1)
        assertEquals(WebhookDeliveryStatus.DEAD, d.status)
        assertEquals("URL_GUARD:PRIVATE_ADDRESS", d.lastError)
        assertEquals(WebhookDeliveryStatus.FAILED, decide(Attempt(error = "URL_GUARD:DNS")).status)
    }

    @Test
    fun `a 429 with retry-after waits at least that long`() {
        val d = decide(Attempt(statusCode = 429, retryAfter = "600"))
        assertEquals(now + 600_000, d.nextAttemptAt)
    }
}
