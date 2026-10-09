package com.panomc.platform.webhook

import com.panomc.platform.db.model.WebhookDelivery
import com.panomc.platform.db.model.WebhookEndpoint
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.platform.util.SecretCipher
import io.vertx.core.json.JsonObject

/** Sends one stored delivery row: headers, signature, the guarded request ([OutboundHttp]). Never throws. */
class WebhookSender(
    private val http: OutboundHttp,
    private val cipher: () -> SecretCipher,
    private val now: () -> Long,
    private val version: String,
    /** The effective `webhooks.allow-private-targets` (false when hosted), read at every send. */
    private val allowPrivate: () -> Boolean
) {
    suspend fun send(row: WebhookDelivery, endpoint: WebhookEndpoint?): Attempt {
        val headers = ArrayList<Pair<String, String>>()
        headers += "Content-Type" to "application/json; charset=utf-8"
        headers += "User-Agent" to "Pano/$version"
        headers += WebhookHeaders.EVENT to row.event
        headers += WebhookHeaders.EVENT_ID to row.eventId
        headers += WebhookHeaders.DELIVERY to row.id.toString()
        headers += WebhookHeaders.ATTEMPT to row.attempts.toString()

        if (row.signing == WebhookSigning.HMAC_SHA256) {
            // Fail closed: an HMAC endpoint never receives an unsigned request.
            val secret = row.secret?.let { cipher().decrypt(it) }?.takeIf { it.isNotEmpty() }
                ?: return Attempt(error = "SECRET_UNREADABLE", retryable = false)

            headers += WebhookSigner.HEADER to WebhookSigner.header(secret, now() / 1000L, row.body)
        }

        val stored = endpoint?.headers

        if (!stored.isNullOrEmpty()) {
            val extra = cipher().decrypt(stored)?.let { parseHeaders(it) }
                ?: return Attempt(error = "HEADERS_UNREADABLE", retryable = false)

            headers += WebhookHeaders.sendable(extra)
        }

        return http.post(
            row.url, headers, row.body.toByteArray(Charsets.UTF_8), allowPrivate(), discord = row.format == WebhookFormat.DISCORD
        )
    }

    private fun parseHeaders(json: String): Map<String, String>? {
        return try {
            val obj = JsonObject(json)
            val out = LinkedHashMap<String, String>()

            for (name in obj.fieldNames()) out[name] = obj.getValue(name)?.toString() ?: return null

            out
        } catch (e: Exception) {
            null
        }
    }
}
