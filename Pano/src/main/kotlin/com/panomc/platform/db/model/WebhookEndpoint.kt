package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/** `webhook_endpoint.format` and `webhook_delivery.format`. */
enum class WebhookFormat { JSON, DISCORD }

/** `webhook_endpoint.signing` and `webhook_delivery.signing`. */
enum class WebhookSigning { NONE, HMAC_SHA256 }

/**
 * `webhook_endpoint` (open front-end plan, doc 06 §4): a webhook the site owner configured in the panel.
 * [events] is a JSON list of event names (`market.order.paid`), `<source>.*` or `*`. [secret] and [headers] are
 * encrypted columns (the `v1:...` text of `SecretCipher`, stored verbatim). An endpoint is auto-disabled after
 * 50 consecutive failures ([disabledReason]).
 */
open class WebhookEndpoint(
    val id: Long = -1,
    val name: String = "",
    val url: String = "",
    val events: String = "[\"*\"]",
    val format: WebhookFormat = WebhookFormat.JSON,
    val signing: WebhookSigning = WebhookSigning.NONE,
    val secret: String? = null,
    val headers: String? = null,
    val template: String? = null,
    val enabled: Boolean = true,
    val maxAttempts: Int = 8,
    val failureCount: Int = 0,
    val lastStatusCode: Int? = null,
    val lastDeliveryAt: Long? = null,
    val disabledReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
