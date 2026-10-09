package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/** `webhook_delivery.status`: `FAILED` will retry, `DEAD` will not. */
enum class WebhookDeliveryStatus { PENDING, SENDING, SUCCEEDED, FAILED, DEAD }

/**
 * `webhook_delivery`: the outbound queue and log. [endpointId] 0 = a direct delivery a plugin queued to a URL it
 * owns (`WebhookPublisher.enqueueDirect`). [source] is the event source (`core` or a plugin namespace),
 * [ownerRef] a reference the owning plugin chose for a direct delivery, [subjectRef] one the publisher chose for
 * the subject of an event (`order:12`). [eventId] is unique. [secret] is an encrypted column.
 */
open class WebhookDelivery(
    val id: Long = -1,
    val endpointId: Long = 0,
    val source: String = "",
    val ownerRef: String? = null,
    val subjectRef: String? = null,
    val eventId: String = "",
    val event: String = "",
    val url: String = "",
    val format: WebhookFormat = WebhookFormat.JSON,
    val signing: WebhookSigning = WebhookSigning.NONE,
    val secret: String? = null,
    val body: String = "",
    val status: WebhookDeliveryStatus = WebhookDeliveryStatus.PENDING,
    val attempts: Int = 0,
    val maxAttempts: Int = 8,
    val nextAttemptAt: Long? = null,
    val claimedUntil: Long? = null,
    val lastStatusCode: Int? = null,
    val lastError: String? = null,
    val lastResponse: String? = null,
    val durationMs: Int? = null,
    val deliveredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
