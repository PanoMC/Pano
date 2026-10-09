package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.WebhookEndpoint
import io.vertx.sqlclient.SqlClient

/** Webhooks the site owner configured. `secret` and `headers` are encrypted columns stored verbatim. */
abstract class WebhookEndpointDao : Dao<WebhookEndpoint>(WebhookEndpoint::class.java) {
    abstract suspend fun add(endpoint: WebhookEndpoint, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): WebhookEndpoint?

    /** Every endpoint, oldest first. */
    abstract suspend fun getAll(sqlClient: SqlClient): List<WebhookEndpoint>

    abstract suspend fun count(sqlClient: SqlClient): Int

    /** Replaces the editable columns of [endpoint] (not the failure counters). Returns `false` when the id is unknown. */
    abstract suspend fun update(endpoint: WebhookEndpoint, now: Long, sqlClient: SqlClient): Boolean

    abstract suspend fun delete(id: Long, sqlClient: SqlClient): Boolean

    /**
     * Records the outcome of one delivery in one statement: a success resets `failureCount`; a failure increments it and,
     * on reaching [disableAfter] consecutive failures, disables the endpoint with `disabledReason`
     * [AUTO_DISABLED_REASON]. Always stores `lastStatusCode` and `lastDeliveryAt`.
     */
    abstract suspend fun recordOutcome(
        id: Long, success: Boolean, statusCode: Int?, now: Long, disableAfter: Int, sqlClient: SqlClient
    ): Boolean

    companion object {
        const val AUTO_DISABLED_REASON = "AUTO_DISABLED_FAILURES"
        const val DEFAULT_DISABLE_AFTER = 50
    }
}
