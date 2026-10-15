package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.WebhookDelivery
import com.panomc.platform.db.model.WebhookDeliveryStatus
import io.vertx.sqlclient.SqlClient

/** The outbound webhook queue and log. */
abstract class WebhookDeliveryDao : Dao<WebhookDelivery>(WebhookDelivery::class.java) {
    /** The new id, or `null` when the event id exists already (`uq_eventId`). */
    abstract suspend fun add(delivery: WebhookDelivery, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): WebhookDelivery?

    abstract suspend fun getByEventId(eventId: String, sqlClient: SqlClient): WebhookDelivery?

    /** The newest [limit] rows of an endpoint, newest first (`idx_endpoint`). */
    abstract suspend fun getByEndpointId(endpointId: Long, limit: Int, sqlClient: SqlClient): List<WebhookDelivery>

    /**
     * One page of the log, newest first, for the panel. Every filter that is `null` is not applied. [offset] is
     * `(page - 1) * limit`.
     */
    abstract suspend fun getPage(
        source: String?, status: WebhookDeliveryStatus?, endpointId: Long?, limit: Int, offset: Int, sqlClient: SqlClient
    ): List<WebhookDelivery>

    /** The number of rows [getPage] pages over. */
    abstract suspend fun countFiltered(
        source: String?, status: WebhookDeliveryStatus?, endpointId: Long?, sqlClient: SqlClient
    ): Long

    abstract suspend fun countBySubjectRef(source: String, subjectRef: String, sqlClient: SqlClient): Long

    /**
     * Ids of rows in `PENDING` / `FAILED` whose `nextAttemptAt` is at or before [now], oldest id first. A row without
     * an endpoint (a direct delivery) is only returned while its `source` is in [activeSources]: a stopped plugin's
     * rows are left alone.
     */
    abstract suspend fun getDueIds(now: Long, limit: Int, activeSources: Collection<String>, sqlClient: SqlClient): List<Long>

    /** Moves a due row to `SENDING` with a claim until [claimedUntil] and `attempts + 1`. `true` = this caller won the row. */
    abstract suspend fun claim(id: Long, now: Long, claimedUntil: Long, sqlClient: SqlClient): Boolean

    /** Ids of `SENDING` rows whose claim ran out before [now] (the worker died). */
    abstract suspend fun getStaleClaimIds(now: Long, limit: Int, sqlClient: SqlClient): List<Long>

    /**
     * Stores the outcome of one attempt on a row still in [from] (compare and set): the new status, the attempt count,
     * the next schedule and what the receiver answered. Returns `true` when the row was updated.
     */
    abstract suspend fun markResult(
        id: Long, from: WebhookDeliveryStatus, to: WebhookDeliveryStatus, attempts: Int, nextAttemptAt: Long?,
        statusCode: Int?, error: String?, response: String?, durationMs: Int?, deliveredAt: Long?, now: Long, sqlClient: SqlClient
    ): Boolean

    /** Ends every open row (`PENDING`, `FAILED`, `SENDING`) of [endpointId] as `DEAD` with `lastError = reason`. */
    abstract suspend fun deadenOpenRows(endpointId: Long, reason: String, now: Long, sqlClient: SqlClient): Int

    /** `SUCCEEDED` / `FAILED` / `DEAD` back to `PENDING` with `attempts = 0`. `false` = no such row in such a state. */
    abstract suspend fun requeue(id: Long, now: Long, sqlClient: SqlClient): Boolean

    /** Deletes `SUCCEEDED` and `DEAD` rows last updated before [cutoff]; returns the number of rows. */
    abstract suspend fun purgeFinishedBefore(cutoff: Long, sqlClient: SqlClient): Int
}
