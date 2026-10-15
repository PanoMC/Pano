package com.panomc.platform.webhook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `X-Pano-Signature` (00 section 8.6, 17 section 11.1). */
class WebhookSignerTest {
    private val secret = "whsec_test_0123456789abcdef"
    private val t = 1_760_000_000L
    private val body = """{"event":"test.ping","id":"00000000-0000-4000-8000-000000000001"}"""
    private val vector = "6cbe603cc169c634da93ba7396d69a0c8752129b674bd2ace2e503e30fb31f96"

    @Test
    fun `the published vector signs to the expected hex`() {
        assertEquals(vector, WebhookSigner.signature(secret, t, body))
        assertEquals("t=$t,v1=$vector", WebhookSigner.header(secret, t, body))
    }

    @Test
    fun `the signature is lower-case hex of 64 characters`() {
        val s = WebhookSigner.signature(secret, t, body)
        assertEquals(64, s.length)
        assertTrue(s.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `a different timestamp, secret or body gives a different signature`() {
        assertTrue(WebhookSigner.signature(secret, t + 1, body) != vector)
        assertTrue(WebhookSigner.signature(secret + "x", t, body) != vector)
        assertTrue(WebhookSigner.signature(secret, t, "$body ") != vector)
    }

    @Test
    fun `a multi-byte body is signed as UTF-8 bytes`() {
        val b = """{"name":"Çağrı ünlü 🎮"}"""
        val expected = javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            doFinal("$t.$b".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        assertEquals(expected, WebhookSigner.signature(secret, t, b))
    }

    @Test
    fun `verify accepts the fresh header and rejects tampering and stale timestamps`() {
        val header = WebhookSigner.header(secret, t, body)
        assertTrue(WebhookSigner.verify(header, secret, body, nowSeconds = t))
        assertTrue(WebhookSigner.verify(header, secret, body, nowSeconds = t + 300))
        assertFalse(WebhookSigner.verify(header, secret, body, nowSeconds = t + 301))
        assertFalse(WebhookSigner.verify(header, secret, body, nowSeconds = t - 301))
        assertFalse(WebhookSigner.verify(header, secret, "$body ", nowSeconds = t))
        assertFalse(WebhookSigner.verify(header, "other", body, nowSeconds = t))
        assertFalse(WebhookSigner.verify("t=$t", secret, body, nowSeconds = t))
        assertFalse(WebhookSigner.verify("garbage", secret, body, nowSeconds = t))
        assertFalse(WebhookSigner.verify("t=$t,v1=${vector.replaceFirst(vector[0], if (vector[0] == '0') '1' else '0')}", secret, body, nowSeconds = t))
    }
}
