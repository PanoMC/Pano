package com.panomc.platform.webhook

import com.panomc.platform.api.webhook.DirectWebhook
import com.panomc.platform.api.webhook.RenderedBody
import com.panomc.platform.api.webhook.WebhookDiscordRenderer
import com.panomc.platform.db.dao.WebhookDeliveryDao
import com.panomc.platform.db.dao.WebhookEndpointDao
import com.panomc.platform.db.model.WebhookDelivery
import com.panomc.platform.db.model.WebhookDeliveryStatus
import com.panomc.platform.db.model.WebhookEndpoint
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.platform.util.SecretCipher
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.random.Random

/** The site identity of the envelope (`site` object). */
class SiteInfo(val name: String, val url: String)

/** What the panel's test button returns (the response body is never exposed). */
class WebhookTestResult(val statusCode: Int?, val durationMs: Int?, val error: String?)

enum class RedeliverResult { OK, NOT_FOUND, IN_FLIGHT }

/**
 * Core webhooks (doc 06 §4): the outbox writer ([publish], [publishCore], [enqueueDirect]) used inside business
 * transactions and the queue operations of [WebhookDispatcher] ([claimDue], [process]).
 *
 * - The writers run on the caller's connection (its transaction) or, with `null`, in a transaction of their own: the rows
 *   commit or roll back together with the business change, a replay of the same transition inserts nothing (`eventId`
 *   is deterministic, `uq_eventId`). They never send anything and never throw because of an endpoint's content (a body
 *   that cannot be rendered becomes a `DEAD` row in the log).
 * - [claimDue] moves due rows to `SENDING` with a 60 s claim (compare and set, safe with several workers) and turns
 *   expired claims back into retries.
 * - [process] sends one claimed row and stores the outcome (row, endpoint counters, auto-disable and the plugin's
 *   outcome listener) in **one** transaction. No transaction is open during the HTTP call.
 */
class WebhookService(
    private val pool: suspend () -> Pool,
    private val endpoints: WebhookEndpointDao,
    private val deliveries: WebhookDeliveryDao,
    private val registry: WebhookRegistry,
    private val cipher: () -> SecretCipher,
    private val sender: WebhookSender,
    private val site: () -> SiteInfo,
    /** Namespaces of the plugins that run now: a direct row of any other source is not claimed. */
    private val activeSources: () -> Set<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val disableAfter: Int = WebhookEndpointDao.DEFAULT_DISABLE_AFTER
) {
    // ----- publish ---------------------------------------------------------------------------------------------------

    /**
     * Inserts one `PENDING` row per enabled endpoint whose subscription matches `<source>.<name>`; returns how many rows
     * were new. [subjectKey] makes the event id deterministic. [data] is the `data` object of the envelope.
     */
    suspend fun publish(
        source: String, name: String, subjectKey: String, data: JsonObject,
        sqlClient: SqlClient? = null, subjectRef: String? = null, extra: JsonObject? = null
    ): Int {
        require(source != WebhookEvents.CORE) { "a plugin cannot emit core events" }

        return emit(source, name, subjectKey, data, sqlClient, subjectRef, extra)
    }

    /** As [publish] for a `core.*` event: [event] is the full name (`core.user.registered`). */
    suspend fun publishCore(
        event: String, subjectKey: String, data: JsonObject,
        sqlClient: SqlClient? = null, subjectRef: String? = null, extra: JsonObject? = null
    ): Int {
        require(event.startsWith("${WebhookEvents.CORE}.")) { "not a core event: $event" }

        return emit(WebhookEvents.CORE, event.removePrefix("${WebhookEvents.CORE}."), subjectKey, data, sqlClient, subjectRef, extra)
    }

    /** `true` when an enabled endpoint would receive `<source>.<name>`. */
    suspend fun hasListeners(source: String, name: String, sqlClient: SqlClient? = null): Boolean {
        require(WebhookEvents.isValidSource(source)) { "invalid webhook source: $source" }
        require(WebhookEvents.isValidName(name)) { "invalid webhook event name: $name" }

        val event = WebhookEvents.full(source, name)

        return targets(sqlClient ?: pool(), event).isNotEmpty()
    }

    /** Queues a delivery to a URL a plugin owns. The row id, or `null` when [DirectWebhook.eventId] was queued before. */
    suspend fun enqueueDirect(source: String, direct: DirectWebhook, sqlClient: SqlClient? = null): Long? {
        require(source != WebhookEvents.CORE && WebhookEvents.isValidSource(source)) { "invalid webhook source: $source" }
        require(WebhookEvents.isValidName(direct.event)) { "invalid webhook event name: ${direct.event}" }
        require(direct.url.isNotBlank() && direct.url.length <= Limits.URL) { "a direct webhook needs a URL of at most ${Limits.URL} characters" }
        require(direct.eventId.isNotBlank() && direct.eventId.length <= Limits.EVENT_ID) { "a direct webhook needs an event id of at most ${Limits.EVENT_ID} characters" }
        require(direct.ownerRef.length <= Limits.REF) { "ownerRef is longer than ${Limits.REF} characters" }
        require(direct.signing != WebhookSigning.HMAC_SHA256 || !direct.secret.isNullOrEmpty()) { "HMAC_SHA256 needs a secret" }

        registry.ensure(source, direct.event)

        val at = now()
        val row = WebhookDelivery(
            endpointId = 0, source = source, ownerRef = direct.ownerRef, eventId = direct.eventId,
            event = WebhookEvents.full(source, direct.event), url = direct.url, format = WebhookFormat.JSON,
            signing = direct.signing, secret = direct.secret?.takeIf { it.isNotEmpty() }?.let { cipher().encrypt(it) },
            body = direct.body, status = WebhookDeliveryStatus.PENDING, attempts = 0,
            maxAttempts = direct.maxAttempts.coerceIn(1, MAX_ATTEMPTS), nextAttemptAt = at, createdAt = at, updatedAt = at
        )

        return deliveries.add(row, sqlClient ?: pool())
    }

    private object Limits {
        const val URL = 1024
        const val EVENT_ID = 64
        const val REF = 128
    }

    private suspend fun emit(
        source: String, name: String, subjectKey: String, data: JsonObject,
        sqlClient: SqlClient?, subjectRef: String?, extra: JsonObject?
    ): Int {
        require(WebhookEvents.isValidSource(source)) { "invalid webhook source: $source" }
        require(WebhookEvents.isValidName(name)) { "invalid webhook event name: $name" }
        require(subjectKey.isNotEmpty()) { "subjectKey is empty" }
        require(subjectRef == null || subjectRef.length <= Limits.REF) { "subjectRef is longer than ${Limits.REF} characters" }

        if (extra != null) {
            val shadowed = extra.fieldNames().filter { it in BASE_KEYS }

            require(shadowed.isEmpty()) { "extra may not shadow the envelope keys: $shadowed" }
        }

        registry.ensure(source, name)

        return if (sqlClient != null) {
            insertRows(sqlClient, source, name, subjectKey, data, subjectRef, extra)
        } else {
            tx { insertRows(it, source, name, subjectKey, data, subjectRef, extra) }
        }
    }

    private suspend fun targets(client: SqlClient, event: String): List<WebhookEndpoint> {
        val subscribable = registry.isSubscribable(event)

        return endpoints.getAll(client).filter { it.enabled && WebhookEvents.matches(it.events, event, subscribable) }
    }

    private suspend fun insertRows(
        client: SqlClient, source: String, name: String, subjectKey: String, data: JsonObject,
        subjectRef: String?, extra: JsonObject?
    ): Int {
        val event = WebhookEvents.full(source, name)
        val targets = targets(client, event)

        if (targets.isEmpty()) return 0

        val at = now()
        val info = site()
        var inserted = 0

        for (endpoint in targets) {
            val eventId = WebhookEvents.eventId(event, subjectKey, endpoint.id)
            val envelope = envelope(eventId, event, source, at, info, data, extra)
            val rendered = render(endpoint.format, source, name, endpoint.template, envelope)

            val row = WebhookDelivery(
                endpointId = endpoint.id, source = source, subjectRef = subjectRef, eventId = eventId, event = event,
                url = endpoint.url, format = endpoint.format, signing = endpoint.signing, secret = endpoint.secret,
                body = rendered.body,
                status = if (rendered.error == null) WebhookDeliveryStatus.PENDING else WebhookDeliveryStatus.DEAD,
                attempts = 0, maxAttempts = endpoint.maxAttempts,
                nextAttemptAt = if (rendered.error == null) at else null,
                lastError = rendered.error ?: rendered.warning,
                createdAt = at, updatedAt = at
            )

            if (deliveries.add(row, client) != null) inserted++
        }

        return inserted
    }

    private class Rendered(val body: String, val error: String?, val warning: String? = null)

    private suspend fun render(format: WebhookFormat, source: String, name: String, template: String?, envelope: JsonObject): Rendered = try {
        when (format) {
            WebhookFormat.JSON -> Rendered(envelope.encode(), null)
            WebhookFormat.DISCORD -> {
                val renderer = registry.discord(source) ?: GenericDiscordRenderer

                renderer.render(name, envelope, template).let { Rendered(it.body, null, it.warning) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never break the business transaction over one endpoint's template: the row is in the log as DEAD.
        Rendered(envelope.encode(), "RENDER_FAILED")
    }

    // ----- claim -----------------------------------------------------------------------------------------------------

    /**
     * Up to [limit] due rows (`PENDING` / `FAILED` with `nextAttemptAt <= now`, oldest id first), each moved to `SENDING`
     * with `claimedUntil = now + 60 s` and `attempts + 1`. The returned rows carry the new attempt number. A direct row
     * is only claimed while its plugin runs.
     */
    suspend fun claimDue(limit: Int = CLAIM_BATCH): List<WebhookDelivery> = tx { conn ->
        val at = now()

        reclaimStale(conn, at)

        val due = deliveries.getDueIds(at, limit, activeSources(), conn)
        val claimed = ArrayList<WebhookDelivery>(due.size)

        for (id in due) {
            if (deliveries.claim(id, at, at + CLAIM_MS, conn)) deliveries.getById(id, conn)?.let { claimed += it }
        }

        claimed
    }

    /** `SENDING` rows whose claim ran out (the worker died): the attempt is counted; a row out of attempts ends `DEAD`. */
    private suspend fun reclaimStale(conn: SqlConnection, at: Long) {
        for (id in deliveries.getStaleClaimIds(at, STALE_BATCH, conn)) {
            val row = deliveries.getById(id, conn) ?: continue

            if (row.status != WebhookDeliveryStatus.SENDING) continue

            if (row.attempts >= row.maxAttempts) {
                val decision = Decision(WebhookDeliveryStatus.DEAD, null, "CLAIM_EXPIRED", success = false, deliveredAt = null)

                finish(conn, row, Attempt(), decision, endpointCounts = false)
            } else {
                deliveries.markResult(
                    id, WebhookDeliveryStatus.SENDING, WebhookDeliveryStatus.FAILED, row.attempts, at,
                    row.lastStatusCode, "CLAIM_EXPIRED", row.lastResponse, row.durationMs, null, at, conn
                )
            }
        }
    }

    // ----- send ------------------------------------------------------------------------------------------------------

    /**
     * Sends the claimed [row] and stores the outcome. Returns the decision, or `null` when the row was taken away while
     * the request was in flight (endpoint deleted or disabled, redelivered): that result is dropped.
     */
    suspend fun process(row: WebhookDelivery): Decision? {
        var endpoint: WebhookEndpoint? = null

        if (row.endpointId != 0L) {
            endpoint = tx { conn -> endpoints.getById(row.endpointId, conn) }

            if (endpoint == null) return finishWithoutSending(row, "ENDPOINT_DELETED")
            if (!endpoint.enabled) return finishWithoutSending(row, "ENDPOINT_DISABLED")
        }

        val attempt = sender.send(row, endpoint)

        return complete(row, attempt, endpointCounts = row.endpointId != 0L)
    }

    private suspend fun finishWithoutSending(row: WebhookDelivery, error: String): Decision? {
        val decision = Decision(WebhookDeliveryStatus.DEAD, null, error, success = false, deliveredAt = null)

        return tx { conn ->
            if (finish(conn, row, Attempt(), decision, endpointCounts = false)) decision else null
        }
    }

    private suspend fun complete(row: WebhookDelivery, attempt: Attempt, endpointCounts: Boolean): Decision? {
        val decision = WebhookOutcome.decide(attempt, row.attempts, row.maxAttempts, now(), random)

        return tx { conn ->
            if (finish(conn, row, attempt, decision, endpointCounts)) decision else null
        }
    }

    /**
     * The one place a row leaves `SENDING`: row, endpoint counters / auto-disable, the plugin's outcome listener.
     * `false` = the row was no longer ours.
     */
    private suspend fun finish(conn: SqlConnection, row: WebhookDelivery, attempt: Attempt, decision: Decision, endpointCounts: Boolean): Boolean {
        val at = now()

        val applied = deliveries.markResult(
            row.id, WebhookDeliveryStatus.SENDING, decision.status, row.attempts, decision.nextAttemptAt,
            attempt.statusCode, decision.lastError?.take(MAX_ERROR), attempt.response?.take(MAX_RESPONSE), attempt.durationMs,
            decision.deliveredAt, at, conn
        )

        if (!applied) return false

        if (endpointCounts && row.endpointId != 0L) {
            endpoints.recordOutcome(row.endpointId, decision.success, attempt.statusCode, at, disableAfter, conn)

            if (!decision.success) {
                val endpoint = endpoints.getById(row.endpointId, conn)

                if (endpoint != null && !endpoint.enabled) deadenOpenRows(conn, endpoint.id, "ENDPOINT_DISABLED")
            }
        }

        if (row.endpointId == 0L && (decision.status == WebhookDeliveryStatus.SUCCEEDED || decision.status == WebhookDeliveryStatus.DEAD)) {
            registry.outcomes(row.source)?.onOutcome(conn, row, decision)
        }

        return true
    }

    // ----- operations used by the panel and the dispatcher -----------------------------------------------------------

    /**
     * Ends every row of [endpointId] that is still `PENDING`, `FAILED` or `SENDING` as `DEAD` with `lastError = reason`
     * (`ENDPOINT_DELETED` / `ENDPOINT_DISABLED`); returns the number of rows. A request that is in flight finds its
     * row gone and its result is dropped.
     */
    suspend fun deadenOpenRows(conn: SqlClient, endpointId: Long, reason: String): Int =
        deliveries.deadenOpenRows(endpointId, reason, now(), conn)

    /**
     * Puts a `SUCCEEDED`, `FAILED` or `DEAD` row back to `PENDING` with `attempts = 0` on the **same** row (same
     * `eventId`). A row that is `SENDING` is refused ([RedeliverResult.IN_FLIGHT]); one that is already `PENDING` is
     * left alone.
     */
    suspend fun redeliver(id: Long): RedeliverResult = tx { conn ->
        if (deliveries.requeue(id, now(), conn)) return@tx RedeliverResult.OK

        when (deliveries.getById(id, conn)?.status) {
            null -> RedeliverResult.NOT_FOUND
            WebhookDeliveryStatus.SENDING -> RedeliverResult.IN_FLIGHT
            else -> RedeliverResult.OK
        }
    }

    /**
     * The panel's test button: inserts a `core.test.ping` row (`maxAttempts = 1`), sends it right away outside any
     * transaction and returns what happened. Works on a disabled endpoint; the endpoint's failure counter is not
     * touched. `null` when the endpoint does not exist.
     */
    suspend fun sendTestPing(endpointId: Long): WebhookTestResult? {
        val endpoint = tx { conn -> endpoints.getById(endpointId, conn) } ?: return null
        val at = now()
        val eventId = UUID.randomUUID().toString()
        val data = JsonObject().put("message", "ping")
        val envelope = envelope(eventId, WebhookEvents.TEST_PING, WebhookEvents.CORE, at, site(), data, null)
        val rendered = render(endpoint.format, WebhookEvents.CORE, WebhookEvents.nameOf(WebhookEvents.TEST_PING), endpoint.template, envelope)

        if (rendered.error != null) return WebhookTestResult(null, null, rendered.error)

        val row = tx { conn ->
            val id = deliveries.add(
                WebhookDelivery(
                    endpointId = endpoint.id, source = WebhookEvents.CORE, eventId = eventId, event = WebhookEvents.TEST_PING,
                    url = endpoint.url, format = endpoint.format, signing = endpoint.signing, secret = endpoint.secret,
                    body = rendered.body, status = WebhookDeliveryStatus.SENDING, attempts = 1, maxAttempts = 1,
                    nextAttemptAt = null, claimedUntil = at + CLAIM_MS, createdAt = at, updatedAt = at
                ), conn
            ) ?: throw IllegalStateException("duplicate test event id")

            deliveries.getById(id, conn)!!
        }

        val attempt = sender.send(row, endpoint)
        val decision = complete(row, attempt, endpointCounts = false)

        return WebhookTestResult(attempt.statusCode, attempt.durationMs, decision?.lastError ?: attempt.error)
    }

    /** Deletes `SUCCEEDED` and `DEAD` rows that last changed more than 30 days ago; returns the number of rows. */
    suspend fun purgeOld(): Int = deliveries.purgeFinishedBefore(now() - RETENTION_MS, pool())

    // ----- transactions ----------------------------------------------------------------------------------------------

    /**
     * Runs [block] on one pooled connection inside BEGIN / COMMIT at READ COMMITTED. A deadlock (1213) or lock wait
     * timeout (1205) re-runs [block] from the start, [TX_ATTEMPTS] attempts in all.
     */
    private suspend fun <T> tx(block: suspend (SqlConnection) -> T): T {
        var attempt = 0

        while (true) {
            attempt++

            try {
                return runOnce(block)
            } catch (e: MySQLException) {
                if ((e.errorCode != DEADLOCK && e.errorCode != LOCK_WAIT_TIMEOUT) || attempt >= TX_ATTEMPTS) throw e

                if (e.errorCode == DEADLOCK) delay(Random.nextLong(5, 30))
            }
        }
    }

    private suspend fun <T> runOnce(block: suspend (SqlConnection) -> T): T {
        val conn = pool().connection.coAwait()

        try {
            // Applies to the next transaction only; the connection goes back to the pool unchanged.
            conn.query("SET TRANSACTION ISOLATION LEVEL READ COMMITTED").execute().coAwait()

            val transaction = conn.begin().coAwait()

            try {
                val result = block(conn)

                transaction.commit().coAwait()

                return result
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { transaction.rollback().coAwait() } }

                throw e
            }
        } finally {
            withContext(NonCancellable) { runCatching { conn.close().coAwait() } }
        }
    }

    companion object {
        const val CLAIM_BATCH = 20
        const val CLAIM_MS = 60_000L
        const val MAX_ATTEMPTS = 20
        const val RETENTION_MS = 30L * 24 * 3_600_000L

        private const val STALE_BATCH = 100
        private const val MAX_ERROR = 512
        private const val MAX_RESPONSE = 2048
        private const val TX_ATTEMPTS = 3
        private const val DEADLOCK = 1213
        private const val LOCK_WAIT_TIMEOUT = 1205

        private val BASE_KEYS = setOf("id", "event", "source", "createdAt", "apiVersion", "site", "data")

        /** The envelope: `{ id, event, source, createdAt, apiVersion, site: { name, url }, data, ...extra }`. */
        fun envelope(
            eventId: String, event: String, source: String, createdAtMs: Long, site: SiteInfo, data: JsonObject, extra: JsonObject?
        ): JsonObject {
            val envelope = JsonObject()
                .put("id", eventId)
                .put("event", event)
                .put("source", source)
                .put("createdAt", createdAtMs)
                .put("apiVersion", WebhookEvents.API_VERSION)
                .put("site", JsonObject().put("name", site.name).put("url", site.url))
                .put("data", data)

            extra?.fieldNames()?.forEach { if (it !in BASE_KEYS) envelope.put(it, extra.getValue(it)) }

            return envelope
        }
    }
}

/**
 * The Discord body of an event whose plugin brought no renderer: one embed with the event name, the site and the
 * first 1 000 characters of `data`.
 */
object GenericDiscordRenderer : WebhookDiscordRenderer {
    const val DATA_LIMIT = 1000

    override suspend fun render(event: String, envelope: JsonObject, template: String?): RenderedBody {
        val full = envelope.getString("event") ?: event
        val site = envelope.getJsonObject("site")
        val siteName = site?.getString("name")?.takeIf { it.isNotBlank() } ?: site?.getString("url") ?: "Pano"
        val data = (envelope.getValue("data")?.let { if (it is JsonObject) it.encodePrettily() else it.toString() } ?: "{}")
            .take(DATA_LIMIT)

        val embed = JsonObject()
            .put("title", full.take(256))
            .put("description", "```json\n$data\n```")
            .put("color", 0x5865F2)
            .put("footer", JsonObject().put("text", siteName.take(2048)))

        return RenderedBody(JsonObject().put("embeds", io.vertx.core.json.JsonArray().add(embed)).encode())
    }
}
