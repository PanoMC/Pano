package com.panomc.platform.webhook

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `X-Pano-Signature` (00 section 8.6, 08 section 15.3): `t=<unix seconds>,v1=<lower-case hex of
 * HMAC-SHA256(secret UTF-8, "<t>.<body>")>`. [t] is the time of the attempt, never of the event, so a retry carries a
 * fresh value while the body bytes stay identical.
 */
object WebhookSigner {
    const val HEADER = "X-Pano-Signature"
    const val DEFAULT_TOLERANCE_SECONDS = 300L

    /** The `v1` part: lower-case hex. */
    fun signature(secret: String, t: Long, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("$t.".toByteArray(Charsets.UTF_8))
        mac.update(body.toByteArray(Charsets.UTF_8))
        return hex(mac.doFinal())
    }

    /** The complete header value. */
    fun header(secret: String, t: Long, body: String): String = "t=$t,v1=${signature(secret, t, body)}"

    /**
     * What a receiver does (used by the tests and by the documentation sample): parses [header], rejects a timestamp
     * further than [toleranceSeconds] from [nowSeconds] and compares in constant time.
     */
    fun verify(header: String, secret: String, body: String, nowSeconds: Long, toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS): Boolean {
        val parts = header.split(',').map { it.trim() }
        val t = parts.firstOrNull { it.startsWith("t=") }?.removePrefix("t=")?.toLongOrNull() ?: return false
        val v1 = parts.firstOrNull { it.startsWith("v1=") }?.removePrefix("v1=") ?: return false
        if (Math.abs(nowSeconds - t) > toleranceSeconds) return false
        return MessageDigest.isEqual(
            signature(secret, t, body).toByteArray(Charsets.UTF_8),
            v1.lowercase().toByteArray(Charsets.UTF_8)
        )
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(digits[(b.toInt() shr 4) and 0xf]).append(digits[b.toInt() and 0xf])
        }
        return out.toString()
    }
}
