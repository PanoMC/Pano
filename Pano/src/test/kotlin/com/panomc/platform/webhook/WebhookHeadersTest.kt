package com.panomc.platform.webhook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebhookHeadersTest {
    @Test
    fun `names follow the rules of 08 section 15_2`() {
        assertTrue(WebhookHeaders.isAllowedName("Authorization"))
        assertTrue(WebhookHeaders.isAllowedName("X-Api-Key"))
        for (bad in listOf("Host", "host", "Content-Length", "Content-Type", "Transfer-Encoding", "Connection", "User-Agent", "X-Pano-Event", "x-pano-anything", "Bad Name", "A:B", "", "a".repeat(65))) {
            assertFalse(WebhookHeaders.isAllowedName(bad), bad)
        }
    }

    @Test
    fun `values are printable ascii up to 512 without line breaks`() {
        assertTrue(WebhookHeaders.isAllowedValue("Bearer abc.def-123"))
        assertTrue(WebhookHeaders.isAllowedValue("a".repeat(512)))
        assertFalse(WebhookHeaders.isAllowedValue("a".repeat(513)))
        assertFalse(WebhookHeaders.isAllowedValue(""))
        assertFalse(WebhookHeaders.isAllowedValue("a\r\nX-Injected: 1"))
        assertFalse(WebhookHeaders.isAllowedValue("a\nb"))
        assertFalse(WebhookHeaders.isAllowedValue("çay"))
    }

    @Test
    fun `validate reports the field of each problem`() {
        assertTrue(WebhookHeaders.validate(mapOf("Authorization" to "Bearer x")).isEmpty())
        val errors = WebhookHeaders.validate(mapOf("Host" to "evil", "X-Ok" to "a\nb", "Good" to "v", "good" to "w"))
        assertEquals("INVALID_NAME", errors["headers.Host"])
        assertEquals("INVALID_VALUE", errors["headers.X-Ok"])
        assertEquals("DUPLICATE", errors["headers.good"])
        assertEquals("TOO_MANY", WebhookHeaders.validate((1..11).associate { "H$it" to "v" })["headers"])
    }

    @Test
    fun `sendable drops what no longer passes and caps the count`() {
        val out = WebhookHeaders.sendable(linkedMapOf("Authorization" to "Bearer x", "X-Pano-Event" to "forged", "Host" to "evil", "X-Ok" to "line\nbreak"))
        assertEquals(listOf("Authorization" to "Bearer x"), out)
        assertEquals(10, WebhookHeaders.sendable((1..15).associate { "H$it" to "v" }).size)
    }
}
