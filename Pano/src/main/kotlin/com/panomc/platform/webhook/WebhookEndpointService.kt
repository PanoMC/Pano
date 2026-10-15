package com.panomc.platform.webhook

import com.panomc.platform.Main
import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PluginManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.WebhookDeliveryDao
import com.panomc.platform.db.dao.WebhookEndpointDao
import com.panomc.platform.db.model.WebhookDelivery
import com.panomc.platform.db.model.WebhookDeliveryStatus
import com.panomc.platform.db.model.WebhookEndpoint
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.hosted.HostedEnvConfig
import com.panomc.platform.model.Error
import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import com.panomc.platform.plugin.PluginNamespace
import com.panomc.platform.util.SecretCipher
import com.panomc.platform.webhook.guard.TargetPolicy
import com.panomc.platform.webhook.guard.UrlGuard
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import java.security.SecureRandom
import java.util.Base64

// ----- panel error codes (doc 06 section 4.2, 4.5) --------------------------------------------------------------------

/** The URL of an endpoint is refused by the outbound guard; `fields.url` is the reason (`SCHEME`, `PRIVATE_ADDRESS`, `DNS`, ...). */
class WebhookUrlRefused(reason: String = "MALFORMED") : Error("WEBHOOK_URL_REFUSED", 400, fields = mapOf("url" to reason))

/** The site already has [WebhookEndpointService.MAX_ENDPOINTS] endpoints. */
class WebhookLimit(limit: Int = WebhookEndpointService.MAX_ENDPOINTS) :
    Error("WEBHOOK_LIMIT", 400, extras = mapOf("limit" to limit))

/** A subscription names an event no running plugin or core declared (or one that cannot be subscribed to). */
class WebhookUnknownEvent(events: List<String> = emptyList()) :
    Error("WEBHOOK_UNKNOWN_EVENT", 400, extras = mapOf("events" to events), fields = mapOf("events" to "UNKNOWN_EVENT"))

/** An extra header is refused; `fields` holds `headers.<name>` (or `headers` for the count) with the reason. */
class WebhookHeadersInvalid(fields: Map<String, String> = emptyMap()) : Error("WEBHOOK_HEADERS_INVALID", 400, fields = fields)

/** No such endpoint or delivery. */
class WebhookNotFound : Error("WEBHOOK_NOT_FOUND", 404)

/** The delivery is being sent right now and cannot be redelivered. */
class WebhookInFlight : Error("WEBHOOK_IN_FLIGHT", 409)

// ----- storage seam ----------------------------------------------------------------------------------------------------

/** Where [WebhookEndpointService] runs its queries: a plain read on the pool, or a write inside one transaction. */
interface WebhookStore {
    suspend fun <T> read(block: suspend (SqlClient) -> T): T

    suspend fun <T> write(block: suspend (SqlClient) -> T): T
}

/** [WebhookStore] on the application's pool. */
class PoolWebhookStore(private val pool: suspend () -> Pool) : WebhookStore {
    override suspend fun <T> read(block: suspend (SqlClient) -> T): T = block(pool())

    override suspend fun <T> write(block: suspend (SqlClient) -> T): T {
        val conn = pool().connection.coAwait()

        try {
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
}

/** What a create or update answers: the endpoint id and, when a signing secret was generated, that secret (shown once). */
class WebhookSaved(val id: Long, val secret: String?)

// ----- the service -----------------------------------------------------------------------------------------------------

/**
 * The panel side of core webhooks (doc 06 section 4.5): endpoint CRUD with the field rules, the one-time secret reveal,
 * encrypted header values, the URL guard on save, the events catalogue, the test ping, the delivery log and redelivery,
 * and [importEndpoint] for a plugin that brings its own endpoints along (market's import).
 *
 * - **Secrets** are stored encrypted and read back masked ([MASK]); a generated `whsec_...` secret leaves the service
 *   once, in the answer of the call that created it. `DISCORD` never signs: its signing is normalised to `NONE`.
 * - **Headers** are validated by [WebhookHeaders], stored as one encrypted JSON object and read back with every value
 *   masked. On update a value equal to [MASK] keeps the stored value of that name.
 * - **Events** must be in the catalogue (exact name, `<source>.*` of a known source, or `*`); a name the endpoint already
 *   subscribes to is kept even if its plugin is stopped now.
 * - Disabling or deleting an endpoint ends its open rows as `DEAD` (`ENDPOINT_DISABLED` / `ENDPOINT_DELETED`);
 *   enabling resets `failureCount` and `disabledReason`.
 *
 * Every answer is a plain `Map` / `List` tree ready for `Successful`.
 */
class WebhookEndpointService(
    private val store: WebhookStore,
    private val endpoints: WebhookEndpointDao,
    private val deliveries: WebhookDeliveryDao,
    private val webhooks: WebhookService,
    private val registry: WebhookRegistry,
    private val cipher: () -> SecretCipher,
    /** The refusal of the outbound guard for a URL (syntax, scheme, resolution, address class), or `null` when it is fine. */
    private val checkUrl: suspend (url: String, discord: Boolean) -> UrlGuard.Reason?,
    /** Sets `failureCount` to 0 for an endpoint that is being enabled again (the DAO's update does not touch counters). */
    private val resetFailures: suspend (client: SqlClient, id: Long) -> Unit,
    /** Makes sure the delivery timer runs (a redelivered or newly created endpoint must not wait for the next publish). */
    private val arm: () -> Unit = {},
    private val titleOf: (source: String) -> String = { if (it == WebhookEvents.CORE) "Pano" else it },
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) {
    // ----- read ------------------------------------------------------------------------------------------------------

    /** `GET /webhooks`: `{ items: [endpoint], limit }`. */
    suspend fun list(): Map<String, Any?> {
        val rows = store.read { endpoints.getAll(it) }

        return linkedMapOf("items" to rows.map { view(it) }, "limit" to MAX_ENDPOINTS)
    }

    /** `GET /webhooks/events`: `{ items: [{ source, title, events: [{ name, subscribable, sample }] }] }`. */
    fun events(): Map<String, Any?> = linkedMapOf(
        "items" to registry.catalogue().map { source ->
            linkedMapOf(
                "source" to source.source,
                "title" to titleOf(source.source),
                "events" to source.events.map {
                    linkedMapOf("name" to it.name, "subscribable" to it.subscribable, "sample" to it.sample?.map)
                }
            )
        }
    )

    /** One endpoint as the panel sees it: `secret` is [MASK] or `null`, header values are [MASK]. */
    fun view(e: WebhookEndpoint): Map<String, Any?> = linkedMapOf(
        "id" to e.id,
        "name" to e.name,
        "url" to e.url,
        "events" to eventsOf(e),
        "format" to e.format.name,
        "signing" to e.signing.name,
        "secret" to if (e.secret.isNullOrEmpty()) null else MASK,
        "headers" to storedHeaders(e).keys.associateWith { MASK },
        "template" to e.template,
        "enabled" to e.enabled,
        "maxAttempts" to e.maxAttempts,
        "failureCount" to e.failureCount,
        "lastStatusCode" to e.lastStatusCode,
        "lastDeliveryAt" to e.lastDeliveryAt,
        "disabledReason" to e.disabledReason,
        "createdAt" to e.createdAt,
        "updatedAt" to e.updatedAt
    )

    private fun eventsOf(e: WebhookEndpoint): List<String> =
        runCatching { JsonArray(e.events).list.map { it.toString() } }.getOrDefault(emptyList())

    private fun storedHeaders(e: WebhookEndpoint): Map<String, String> {
        val encrypted = e.headers?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        val json = cipher().decrypt(encrypted) ?: return emptyMap()

        return runCatching {
            val obj = JsonObject(json)

            obj.fieldNames().associateWith { obj.getValue(it)?.toString().orEmpty() }
        }.getOrDefault(emptyMap())
    }

    // ----- create / update / delete ------------------------------------------------------------------------------------

    suspend fun create(body: JsonObject): WebhookSaved {
        val parsed = parse(body, null)

        checkUrlOf(parsed)

        val at = now()
        val saved = store.write { client ->
            if (endpoints.count(client) >= MAX_ENDPOINTS) throw WebhookLimit()

            val id = endpoints.add(
                WebhookEndpoint(
                    name = parsed.name, url = parsed.url, events = parsed.events.encode(), format = parsed.format,
                    signing = parsed.signing, secret = parsed.storedSecret, headers = parsed.storedHeaders,
                    template = parsed.template, enabled = parsed.enabled, maxAttempts = parsed.maxAttempts,
                    createdAt = at, updatedAt = at
                ),
                client
            )

            WebhookSaved(id, parsed.generatedSecret)
        }

        arm()

        return saved
    }

    suspend fun update(id: Long, body: JsonObject): WebhookSaved {
        val existing = store.read { endpoints.getById(id, it) } ?: throw WebhookNotFound()
        val parsed = parse(body, existing)

        if (parsed.url != existing.url || parsed.format != existing.format) checkUrlOf(parsed)

        val at = now()
        val saved = store.write { client ->
            val fresh = endpoints.getById(id, client) ?: throw WebhookNotFound()
            val enabling = parsed.enabled && !fresh.enabled
            val disabling = !parsed.enabled && fresh.enabled

            val ok = endpoints.update(
                WebhookEndpoint(
                    id = id, name = parsed.name, url = parsed.url, events = parsed.events.encode(), format = parsed.format,
                    signing = parsed.signing, secret = parsed.storedSecret, headers = parsed.storedHeaders,
                    template = parsed.template, enabled = parsed.enabled, maxAttempts = parsed.maxAttempts,
                    disabledReason = when {
                        parsed.enabled -> null
                        disabling -> MANUAL_DISABLED_REASON
                        else -> fresh.disabledReason
                    }
                ),
                at, client
            )

            if (!ok) throw WebhookNotFound()

            if (enabling) resetFailures(client, id)

            if (disabling) webhooks.deadenOpenRows(client, id, "ENDPOINT_DISABLED")

            WebhookSaved(id, parsed.generatedSecret)
        }

        arm()

        return saved
    }

    /** The name of an endpoint for the activity log, null when there is none with this id. */
    suspend fun nameOf(id: Long): String? = store.read { endpoints.getById(id, it) }?.name

    /** The endpoint name of a delivery for the activity log (the event name for a delivery without an endpoint). */
    suspend fun nameOfDelivery(id: Long): String? = store.read { client ->
        val row = deliveries.getById(id, client) ?: return@read null

        if (row.endpointId != 0L) endpoints.getById(row.endpointId, client)?.name ?: row.event else row.event
    }

    /** A hard delete; the rows still open become `DEAD (ENDPOINT_DELETED)` first. */
    suspend fun delete(id: Long) {
        store.write { client ->
            endpoints.getById(id, client) ?: throw WebhookNotFound()
            webhooks.deadenOpenRows(client, id, "ENDPOINT_DELETED")
            endpoints.delete(id, client)
        }
    }

    private suspend fun checkUrlOf(parsed: Parsed) {
        checkUrl(parsed.url, parsed.format == WebhookFormat.DISCORD)?.let { throw WebhookUrlRefused(it.name) }
    }

    // ----- import (a plugin brings its endpoints along) ----------------------------------------------------------------

    /**
     * Stores an endpoint that already exists elsewhere (market's `market_webhook_endpoint`): the plain [plainSecret] and
     * [plainHeaders] are encrypted with core's key. Meant for a trusted caller: the 50 endpoint limit and the DNS check
     * are not applied (the URL is guarded again before every send), `events` are taken as given. Returns the new id.
     */
    suspend fun importEndpoint(
        name: String, url: String, events: List<String>, format: WebhookFormat, signing: WebhookSigning,
        plainSecret: String?, plainHeaders: Map<String, String>?, template: String?, enabled: Boolean, maxAttempts: Int
    ): Long {
        require(url.isNotBlank() && url.length <= MAX_URL) { "an endpoint needs a URL of at most $MAX_URL characters" }

        val effectiveSigning = if (format == WebhookFormat.DISCORD) WebhookSigning.NONE else signing

        require(effectiveSigning != WebhookSigning.HMAC_SHA256 || !plainSecret.isNullOrEmpty()) { "HMAC_SHA256 needs a secret" }

        val at = now()
        val id = store.write { client ->
            endpoints.add(
                WebhookEndpoint(
                    name = name.trim().take(MAX_NAME).ifEmpty { "Webhook" }, url = url.trim(),
                    events = JsonArray(events.distinct()).encode(), format = format, signing = effectiveSigning,
                    secret = plainSecret?.takeIf { effectiveSigning == WebhookSigning.HMAC_SHA256 }?.let { cipher().encrypt(it) },
                    headers = plainHeaders?.takeIf { it.isNotEmpty() }?.let { cipher().encrypt(JsonObject(it.toMutableMap<String, Any?>()).encode()) },
                    template = template?.takeIf { it.isNotBlank() && format == WebhookFormat.DISCORD },
                    enabled = enabled, maxAttempts = maxAttempts.coerceIn(1, MAX_ATTEMPTS), createdAt = at, updatedAt = at
                ),
                client
            )
        }

        arm()

        return id
    }

    // ----- field rules -------------------------------------------------------------------------------------------------

    private class Parsed(
        val name: String, val url: String, val events: JsonArray, val format: WebhookFormat, val signing: WebhookSigning,
        /** The value of the `secret` column (encrypted), or `null`. */
        val storedSecret: String?, val generatedSecret: String?,
        /** The value of the `headers` column (encrypted JSON), or `null`. */
        val storedHeaders: String?, val template: String?, val enabled: Boolean, val maxAttempts: Int
    )

    private fun parse(body: JsonObject, existing: WebhookEndpoint?): Parsed {
        val errors = LinkedHashMap<String, String>()

        fun has(key: String) = body.containsKey(key) && body.getValue(key) != null

        // name
        val rawName = if (has("name")) body.getValue("name") else existing?.name
        val name = (rawName as? String)?.trim().orEmpty()

        when {
            rawName != null && rawName !is String -> errors["name"] = "INVALID"
            name.isEmpty() -> errors["name"] = "REQUIRED"
            name.length > MAX_NAME -> errors["name"] = "TOO_LONG"
        }

        // url
        val rawUrl = if (has("url")) body.getValue("url") else existing?.url
        val url = (rawUrl as? String)?.trim().orEmpty()

        when {
            rawUrl != null && rawUrl !is String -> errors["url"] = "INVALID"
            url.isEmpty() -> errors["url"] = "REQUIRED"
        }

        // format and signing
        val format = enumOf(WebhookFormat.entries, if (has("format")) body.getValue("format") else existing?.format?.name ?: "JSON", "format", errors, WebhookFormat.JSON)
        var signing = enumOf(WebhookSigning.entries, if (has("signing")) body.getValue("signing") else existing?.signing?.name ?: "NONE", "signing", errors, WebhookSigning.NONE)

        // DISCORD never signs
        if (format == WebhookFormat.DISCORD) signing = WebhookSigning.NONE

        // template
        val rawTemplate = if (body.containsKey("template")) body.getValue("template") else existing?.template
        val template = (rawTemplate as? String)?.takeIf { it.isNotBlank() }

        if (rawTemplate != null && rawTemplate !is String) {
            errors["template"] = "INVALID"
        } else if (template != null) {
            if (format != WebhookFormat.DISCORD) errors["template"] = "NOT_ALLOWED"
            else if (template.length > MAX_TEMPLATE) errors["template"] = "TOO_LONG"
        }

        // maxAttempts
        val rawAttempts = if (has("maxAttempts")) body.getValue("maxAttempts") else existing?.maxAttempts ?: DEFAULT_ATTEMPTS
        val maxAttempts = if (rawAttempts is Number && rawAttempts.toDouble() == rawAttempts.toLong().toDouble()) {
            rawAttempts.toInt().also { if (rawAttempts.toLong() !in 1L..MAX_ATTEMPTS.toLong()) errors["maxAttempts"] = "OUT_OF_RANGE" }
        } else {
            errors["maxAttempts"] = "INVALID"

            DEFAULT_ATTEMPTS
        }

        // enabled
        val rawEnabled = if (has("enabled")) body.getValue("enabled") else existing?.enabled ?: true
        val enabled = if (rawEnabled is Boolean) {
            rawEnabled
        } else {
            errors["enabled"] = "INVALID"

            true
        }

        // regenerateSecret
        val rawRegenerate = if (has("regenerateSecret")) body.getValue("regenerateSecret") else false
        val regenerate = if (rawRegenerate is Boolean) {
            rawRegenerate
        } else {
            errors["regenerateSecret"] = "INVALID"

            false
        }

        // events (the subscription list)
        val events = parseEvents(if (has("events")) body.getValue("events") else existing?.let { eventsOf(it) }?.let { JsonArray(it) }, existing, errors)

        // headers
        val headers = parseHeaders(body, existing, errors)

        if (errors.isNotEmpty()) throw InvalidFields(errors)

        if (events.second.isNotEmpty()) throw WebhookUnknownEvent(events.second)

        headers.second.takeIf { it.isNotEmpty() }?.let { throw WebhookHeadersInvalid(it) }

        if (url.length > MAX_URL) throw WebhookUrlRefused(UrlGuard.Reason.MALFORMED.name)

        // the signing secret: generated, never typed in
        var storedSecret: String? = null
        var generated: String? = null

        if (signing == WebhookSigning.HMAC_SHA256) {
            val kept = existing?.takeIf { it.signing == WebhookSigning.HMAC_SHA256 && !it.secret.isNullOrEmpty() && !regenerate }?.secret

            if (kept != null && cipher().decrypt(kept) != null) {
                storedSecret = kept
            } else {
                generated = newSecret()
                storedSecret = cipher().encrypt(generated)
            }
        }

        return Parsed(name, url, events.first, format, signing, storedSecret, generated, headers.first, template, enabled, maxAttempts)
    }

    private fun <E : Enum<E>> enumOf(values: List<E>, raw: Any?, field: String, errors: MutableMap<String, String>, default: E): E {
        val name = raw as? String

        return values.firstOrNull { it.name == name } ?: run {
            errors[field] = "INVALID"

            default
        }
    }

    /** The cleaned subscription list and the names the catalogue does not know. */
    private fun parseEvents(raw: Any?, existing: WebhookEndpoint?, errors: MutableMap<String, String>): Pair<JsonArray, List<String>> {
        val array = raw as? JsonArray

        if (array == null || array.isEmpty) {
            errors["events"] = "REQUIRED"

            return JsonArray() to emptyList()
        }

        if (array.size() > MAX_EVENTS) {
            errors["events"] = "TOO_MANY"

            return JsonArray() to emptyList()
        }

        val kept = existing?.let { eventsOf(it) }.orEmpty().toSet()
        val names = LinkedHashSet<String>()
        val unknown = ArrayList<String>()
        val knownSources = registry.catalogue().map { it.source }.toSet()

        for (value in array) {
            val name = value as? String

            if (name == null) {
                errors["events"] = "INVALID"

                return JsonArray() to emptyList()
            }

            names += name

            val known = when {
                name == WebhookEvents.WILDCARD -> true
                name in kept -> true
                name.endsWith(".*") -> name.removeSuffix(".*") in knownSources
                else -> registry.isKnown(name) && registry.isSubscribable(name)
            }

            if (!known) unknown += name
        }

        return JsonArray(names.toList()) to unknown
    }

    /** The plain headers to store (encrypted) and the per-field errors. */
    private fun parseHeaders(body: JsonObject, existing: WebhookEndpoint?, errors: MutableMap<String, String>): Pair<String?, Map<String, String>> {
        val kept = existing?.let { storedHeaders(it) }.orEmpty()

        fun encrypted(plain: Map<String, String>): String? =
            if (plain.isEmpty()) null else cipher().encrypt(JsonObject(plain.toMutableMap<String, Any?>()).encode())

        if (!body.containsKey("headers")) return encrypted(kept) to emptyMap()

        val raw = body.getValue("headers") ?: return null to emptyMap()
        val obj = raw as? JsonObject ?: run {
            errors["headers"] = "INVALID"

            return null to emptyMap()
        }

        val plain = LinkedHashMap<String, String>()
        val problems = LinkedHashMap<String, String>()

        for (key in obj.fieldNames()) {
            val value = obj.getValue(key) as? String

            if (value == null) {
                problems["headers.$key"] = "INVALID_VALUE"
            } else if (value == MASK) {
                // the masked read-back keeps the stored value of that header
                val old = kept[key]

                if (old == null) problems["headers.$key"] = "INVALID_VALUE" else plain[key] = old
            } else {
                plain[key] = value
            }
        }

        problems += WebhookHeaders.validate(plain)

        return encrypted(plain) to problems
    }

    private fun newSecret(): String {
        val raw = ByteArray(32)

        random.nextBytes(raw)

        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    // ----- test, log, redeliver ------------------------------------------------------------------------------------------

    /** `POST /webhooks/:id/test`: sends `core.test.ping` now; `{ statusCode, durationMs, error }`. */
    suspend fun test(id: Long): Map<String, Any?> {
        val result = webhooks.sendTestPing(id) ?: throw WebhookNotFound()

        return linkedMapOf("statusCode" to result.statusCode, "durationMs" to result.durationMs, "error" to result.error)
    }

    /**
     * `GET /webhooks/:id/deliveries` ([endpointId] set) and `GET /webhook-deliveries`: newest first, filtered by [source]
     * and [status]; the `{ items, page }` shape.
     */
    suspend fun deliveries(endpointId: Long?, source: String?, status: String?, page: PageRequest): Map<String, Any?> {
        val parsedStatus = if (status.isNullOrBlank()) null else WebhookDeliveryStatus.entries.firstOrNull { it.name == status }
            ?: throw InvalidFields(mapOf("status" to "UNKNOWN_VALUE"))
        val sourceFilter = source?.takeIf { it.isNotBlank() }

        val (rows, total) = store.read { client ->
            if (endpointId != null) endpoints.getById(endpointId, client) ?: throw WebhookNotFound()

            val total = deliveries.countFiltered(sourceFilter, parsedStatus, endpointId, client)

            Paging.requireInRange(page, total)

            deliveries.getPage(sourceFilter, parsedStatus, endpointId, page.limit, page.offset.toInt(), client) to total
        }

        return Paging.response(rows.map { listView(it) }, total, page)
    }

    /** A delivery in the list: no body, no response, no URL. */
    fun listView(d: WebhookDelivery): Map<String, Any?> = linkedMapOf(
        "id" to d.id,
        "endpointId" to d.endpointId,
        "source" to d.source,
        "event" to d.event,
        "eventId" to d.eventId,
        "subjectRef" to d.subjectRef,
        "ownerRef" to d.ownerRef,
        "status" to d.status.name,
        "attempts" to d.attempts,
        "maxAttempts" to d.maxAttempts,
        "nextAttemptAt" to d.nextAttemptAt,
        "lastStatusCode" to d.lastStatusCode,
        "lastError" to d.lastError,
        "durationMs" to d.durationMs,
        "createdAt" to d.createdAt,
        "deliveredAt" to d.deliveredAt
    )

    /** `GET /webhook-deliveries/:id`: the list shape plus the stored `body`, `lastResponse`, `url`, `format`, `signing`. The secret is never in it. */
    suspend fun delivery(id: Long): Map<String, Any?> {
        val d = store.read { deliveries.getById(id, it) } ?: throw WebhookNotFound()

        return LinkedHashMap(listView(d)).apply {
            put("url", d.url)
            put("format", d.format.name)
            put("signing", d.signing.name)
            put("body", d.body)
            put("lastResponse", d.lastResponse)
        }
    }

    /** `POST /webhook-deliveries/:id/redeliver`: the same row back to `PENDING`; `WEBHOOK_IN_FLIGHT` while it is `SENDING`. */
    suspend fun redeliver(id: Long) {
        when (webhooks.redeliver(id)) {
            RedeliverResult.OK -> arm()
            RedeliverResult.NOT_FOUND -> throw WebhookNotFound()
            RedeliverResult.IN_FLIGHT -> throw WebhookInFlight()
        }
    }

    companion object {
        /** What a stored secret and every header value read as. */
        const val MASK = "********"

        const val MAX_ENDPOINTS = 50
        const val MAX_NAME = 128
        const val MAX_URL = 1024
        const val MAX_ATTEMPTS = 20
        const val DEFAULT_ATTEMPTS = 8
        const val MAX_EVENTS = 200
        const val MAX_TEMPLATE = 20_000
        const val MANUAL_DISABLED_REASON = "MANUAL"

        fun current(): WebhookEndpointService = Main.applicationContext.getBean(WebhookEndpointService::class.java)
    }
}

/** Wires [WebhookEndpointService]; lazy like the rest of the webhook beans. */
@Configuration
open class WebhookEndpointBeans {
    @Bean
    @Lazy
    open fun webhookEndpointService(
        vertx: Vertx,
        databaseManager: DatabaseManager,
        configManager: ConfigManager,
        pluginManager: PluginManager,
        endpoints: WebhookEndpointDao,
        deliveries: WebhookDeliveryDao,
        webhookService: WebhookService,
        registry: WebhookRegistry,
        webhookSecretCipher: SecretCipher,
        dispatcher: WebhookDispatcher
    ): WebhookEndpointService {
        val allowPrivate = {
            TargetPolicy.effectiveAllowPrivate(
                configManager.config.effectiveWebhooks.allowPrivateTargets, HostedEnvConfig.current.isHosted
            )
        }
        val version = runCatching { Main.VERSION }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" } ?: "dev"
        val outbound by lazy { OutboundHttp.create(vertx, version) }

        return WebhookEndpointService(
            store = PoolWebhookStore { databaseManager.getSqlClient() as Pool },
            endpoints = endpoints,
            deliveries = deliveries,
            webhooks = webhookService,
            registry = registry,
            cipher = { webhookSecretCipher },
            checkUrl = { url, discord -> outbound.check(url, allowPrivate(), discord).refusal },
            resetFailures = { client, id ->
                client.preparedQuery("UPDATE `${databaseManager.getTablePrefix()}webhook_endpoint` SET `failureCount` = 0 WHERE `id` = ?")
                    .execute(Tuple.of(id)).coAwait()
            },
            arm = { dispatcher.start() },
            titleOf = { source -> titleOfSource(pluginManager, source) }
        )
    }

    private fun titleOfSource(pluginManager: PluginManager, source: String): String {
        if (source == WebhookEvents.CORE) return "Pano"

        return runCatching {
            pluginManager.getActivePanoPlugins()
                .firstOrNull { PluginNamespace.of(it) == source }
                ?.let { plugin -> (pluginManager.getPlugin(plugin.pluginId)?.descriptor as? PanoPluginDescriptor)?.name }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: source
    }
}
