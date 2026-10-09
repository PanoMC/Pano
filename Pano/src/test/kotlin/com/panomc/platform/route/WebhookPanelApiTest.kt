package com.panomc.platform.route

import com.panomc.platform.Main
import com.panomc.platform.api.webhook.WebhookEventType
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.dao.WebhookDeliveryDao
import com.panomc.platform.db.dao.WebhookEndpointDao
import com.panomc.platform.db.model.PanelActivityLog
import com.panomc.platform.db.model.WebhookDelivery
import com.panomc.platform.db.model.WebhookDeliveryStatus
import com.panomc.platform.db.model.WebhookEndpoint
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.User
import com.panomc.platform.model.Api
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.route.api.panel.webhook.PanelCreateWebhookAPI
import com.panomc.platform.route.api.panel.webhook.PanelDeleteWebhookAPI
import com.panomc.platform.route.api.panel.webhook.PanelGetWebhookDeliveriesAPI
import com.panomc.platform.route.api.panel.webhook.PanelGetWebhookDeliveryAPI
import com.panomc.platform.route.api.panel.webhook.PanelGetWebhookEndpointDeliveriesAPI
import com.panomc.platform.route.api.panel.webhook.PanelGetWebhookEventsAPI
import com.panomc.platform.route.api.panel.webhook.PanelGetWebhooksAPI
import com.panomc.platform.route.api.panel.webhook.PanelRedeliverWebhookAPI
import com.panomc.platform.route.api.panel.webhook.PanelTestWebhookAPI
import com.panomc.platform.route.api.panel.webhook.PanelUpdateWebhookAPI
import com.panomc.platform.route.api.panel.webhook.WebhookActor
import com.panomc.platform.route.api.panel.webhook.WebhookPanelApi
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.util.PostStatus
import com.panomc.platform.webhook.PoolWebhookStore
import com.panomc.platform.webhook.RedeliverResult
import com.panomc.platform.webhook.SiteInfo
import com.panomc.platform.webhook.StubResolver
import com.panomc.platform.webhook.TestReceiver
import com.panomc.platform.webhook.ReceiverReply
import com.panomc.platform.webhook.WebhookCoreEvents
import com.panomc.platform.webhook.WebhookEndpointService
import com.panomc.platform.webhook.WebhookEvents
import com.panomc.platform.webhook.WebhookRegistry
import com.panomc.platform.webhook.WebhookSender
import com.panomc.platform.webhook.WebhookService
import com.panomc.platform.webhook.WebhookSigner
import com.panomc.platform.webhook.WebhookStore
import com.panomc.platform.webhook.WebhookTestSupport
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.Draft
import io.vertx.json.schema.JsonSchemaOptions
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * PF-27: the webhook panel API and the core events (doc 06 section 4.2 and 4.5).
 *
 * The real route classes run behind a real router (their own validation handler, the failure handler of `Api`), the
 * real [WebhookEndpointService] and the real [WebhookService] with a real HTTP sender; only the database is replaced by
 * in-memory DAOs and a pool that hands out do-nothing connections, so the test needs no MariaDB. The permission check
 * and the session are not part of the route harness; the test asserts that every route demands
 * `ManagePlatformSettingsPermission` instead.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookPanelApiTest {
    // ---- in-memory DAOs and a pool that does nothing ------------------------------------------------------------------

    private class MemoryEndpointDao : WebhookEndpointDao() {
        val rows = java.util.concurrent.ConcurrentSkipListMap<Long, WebhookEndpoint>()
        private val next = java.util.concurrent.atomic.AtomicLong(1L)

        override suspend fun init(sqlClient: SqlClient) = Unit

        override suspend fun add(endpoint: WebhookEndpoint, sqlClient: SqlClient): Long {
            val id = next.getAndIncrement()

            rows[id] = endpointOf(id, endpoint)

            return id
        }

        override suspend fun getById(id: Long, sqlClient: SqlClient): WebhookEndpoint? = rows[id]

        override suspend fun getAll(sqlClient: SqlClient): List<WebhookEndpoint> = rows.values.toList()

        override suspend fun count(sqlClient: SqlClient): Int = rows.size

        override suspend fun update(endpoint: WebhookEndpoint, now: Long, sqlClient: SqlClient): Boolean {
            val old = rows[endpoint.id] ?: return false

            rows[endpoint.id] = endpointOf(
                endpoint.id, endpoint, failureCount = old.failureCount, lastStatusCode = old.lastStatusCode,
                lastDeliveryAt = old.lastDeliveryAt, createdAt = old.createdAt, updatedAt = now
            )

            return true
        }

        override suspend fun delete(id: Long, sqlClient: SqlClient): Boolean = rows.remove(id) != null

        override suspend fun recordOutcome(
            id: Long, success: Boolean, statusCode: Int?, now: Long, disableAfter: Int, sqlClient: SqlClient
        ): Boolean {
            val old = rows[id] ?: return false
            val failures = if (success) 0 else old.failureCount + 1
            val disable = !success && failures >= disableAfter

            rows[id] = endpointOf(
                id, old, failureCount = failures, lastStatusCode = statusCode, lastDeliveryAt = now,
                enabled = old.enabled && !disable, disabledReason = if (disable) WebhookEndpointDao.AUTO_DISABLED_REASON else old.disabledReason
            )

            return true
        }

        fun setFailureCount(id: Long, failures: Int) {
            rows[id] = endpointOf(id, rows.getValue(id), failureCount = failures)
        }

        private fun endpointOf(
            id: Long, e: WebhookEndpoint, failureCount: Int = e.failureCount, lastStatusCode: Int? = e.lastStatusCode,
            lastDeliveryAt: Long? = e.lastDeliveryAt, createdAt: Long = e.createdAt, updatedAt: Long = e.updatedAt,
            enabled: Boolean = e.enabled, disabledReason: String? = e.disabledReason
        ) = WebhookEndpoint(
            id = id, name = e.name, url = e.url, events = e.events, format = e.format, signing = e.signing, secret = e.secret,
            headers = e.headers, template = e.template, enabled = enabled, maxAttempts = e.maxAttempts, failureCount = failureCount,
            lastStatusCode = lastStatusCode, lastDeliveryAt = lastDeliveryAt, disabledReason = disabledReason,
            createdAt = createdAt, updatedAt = updatedAt
        )
    }

    private class MemoryDeliveryDao : WebhookDeliveryDao() {
        val rows = java.util.concurrent.ConcurrentSkipListMap<Long, WebhookDelivery>()
        private val next = java.util.concurrent.atomic.AtomicLong(1L)

        override suspend fun init(sqlClient: SqlClient) = Unit

        override suspend fun add(delivery: WebhookDelivery, sqlClient: SqlClient): Long? {
            if (rows.values.any { it.eventId == delivery.eventId }) return null

            val id = next.getAndIncrement()

            rows[id] = deliveryOf(id, delivery)

            return id
        }

        override suspend fun getById(id: Long, sqlClient: SqlClient): WebhookDelivery? = rows[id]

        override suspend fun getByEventId(eventId: String, sqlClient: SqlClient): WebhookDelivery? =
            rows.values.firstOrNull { it.eventId == eventId }

        override suspend fun getByEndpointId(endpointId: Long, limit: Int, sqlClient: SqlClient): List<WebhookDelivery> =
            rows.values.filter { it.endpointId == endpointId }.sortedByDescending { it.id }.take(limit)

        private fun filtered(source: String?, status: WebhookDeliveryStatus?, endpointId: Long?) =
            rows.values.filter {
                (source == null || it.source == source) && (status == null || it.status == status) &&
                        (endpointId == null || it.endpointId == endpointId)
            }

        override suspend fun getPage(
            source: String?, status: WebhookDeliveryStatus?, endpointId: Long?, limit: Int, offset: Int, sqlClient: SqlClient
        ): List<WebhookDelivery> = filtered(source, status, endpointId).sortedByDescending { it.id }.drop(offset).take(limit)

        override suspend fun countFiltered(
            source: String?, status: WebhookDeliveryStatus?, endpointId: Long?, sqlClient: SqlClient
        ): Long = filtered(source, status, endpointId).size.toLong()

        override suspend fun countBySubjectRef(source: String, subjectRef: String, sqlClient: SqlClient): Long =
            rows.values.count { it.source == source && it.subjectRef == subjectRef }.toLong()

        override suspend fun getDueIds(now: Long, limit: Int, activeSources: Collection<String>, sqlClient: SqlClient): List<Long> =
            rows.values.filter {
                (it.status == WebhookDeliveryStatus.PENDING || it.status == WebhookDeliveryStatus.FAILED) &&
                        (it.nextAttemptAt ?: Long.MAX_VALUE) <= now
            }.take(limit).map { it.id }

        override suspend fun claim(id: Long, now: Long, claimedUntil: Long, sqlClient: SqlClient): Boolean {
            val row = rows[id] ?: return false

            if (row.status != WebhookDeliveryStatus.PENDING && row.status != WebhookDeliveryStatus.FAILED) return false

            rows[id] = deliveryOf(id, row, status = WebhookDeliveryStatus.SENDING, attempts = row.attempts + 1, claimedUntil = claimedUntil)

            return true
        }

        override suspend fun getStaleClaimIds(now: Long, limit: Int, sqlClient: SqlClient): List<Long> =
            rows.values.filter { it.status == WebhookDeliveryStatus.SENDING && (it.claimedUntil ?: 0L) < now }.take(limit).map { it.id }

        override suspend fun markResult(
            id: Long, from: WebhookDeliveryStatus, to: WebhookDeliveryStatus, attempts: Int, nextAttemptAt: Long?,
            statusCode: Int?, error: String?, response: String?, durationMs: Int?, deliveredAt: Long?, now: Long, sqlClient: SqlClient
        ): Boolean {
            val row = rows[id] ?: return false

            if (row.status != from) return false

            rows[id] = deliveryOf(
                id, row, status = to, attempts = attempts, nextAttemptAt = nextAttemptAt, claimedUntil = null,
                lastStatusCode = statusCode, lastError = error, lastResponse = response, durationMs = durationMs,
                deliveredAt = deliveredAt, updatedAt = now
            )

            return true
        }

        override suspend fun deadenOpenRows(endpointId: Long, reason: String, now: Long, sqlClient: SqlClient): Int {
            var changed = 0

            for (row in rows.values.toList()) {
                if (row.endpointId == endpointId && row.status in OPEN) {
                    rows[row.id] = deliveryOf(row.id, row, status = WebhookDeliveryStatus.DEAD, nextAttemptAt = null, claimedUntil = null, lastError = reason, updatedAt = now)
                    changed++
                }
            }

            return changed
        }

        override suspend fun requeue(id: Long, now: Long, sqlClient: SqlClient): Boolean {
            val row = rows[id] ?: return false

            if (row.status !in setOf(WebhookDeliveryStatus.SUCCEEDED, WebhookDeliveryStatus.FAILED, WebhookDeliveryStatus.DEAD)) return false

            rows[id] = deliveryOf(id, row, status = WebhookDeliveryStatus.PENDING, attempts = 0, nextAttemptAt = now, claimedUntil = null, updatedAt = now)

            return true
        }

        override suspend fun purgeFinishedBefore(cutoff: Long, sqlClient: SqlClient): Int = 0

        fun put(d: WebhookDelivery): Long = runBlocking { add(d, NO_CLIENT)!! }

        fun status(id: Long, status: WebhookDeliveryStatus) {
            rows[id] = deliveryOf(id, rows.getValue(id), status = status)
        }

        private fun deliveryOf(
            id: Long, d: WebhookDelivery, status: WebhookDeliveryStatus = d.status, attempts: Int = d.attempts,
            nextAttemptAt: Long? = d.nextAttemptAt, claimedUntil: Long? = d.claimedUntil, lastStatusCode: Int? = d.lastStatusCode,
            lastError: String? = d.lastError, lastResponse: String? = d.lastResponse, durationMs: Int? = d.durationMs,
            deliveredAt: Long? = d.deliveredAt, updatedAt: Long = d.updatedAt
        ) = WebhookDelivery(
            id = id, endpointId = d.endpointId, source = d.source, ownerRef = d.ownerRef, subjectRef = d.subjectRef,
            eventId = d.eventId, event = d.event, url = d.url, format = d.format, signing = d.signing, secret = d.secret,
            body = d.body, status = status, attempts = attempts, maxAttempts = d.maxAttempts, nextAttemptAt = nextAttemptAt,
            claimedUntil = claimedUntil, lastStatusCode = lastStatusCode, lastError = lastError, lastResponse = lastResponse,
            durationMs = durationMs, deliveredAt = deliveredAt, createdAt = d.createdAt, updatedAt = updatedAt
        )

        companion object {
            val OPEN = setOf(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.FAILED, WebhookDeliveryStatus.SENDING)
        }
    }

    // ---- fixture -------------------------------------------------------------------------------------------------------

    private lateinit var vertx: Vertx

    private val clock = AtomicLong(1_760_000_000_000L)
    private val cipher = WebhookTestSupport.cipher()
    private val resolver = StubResolver("hooks.example.test" to listOf("93.184.216.34"), "discord.com" to listOf("162.159.135.232"))
    private val receivers = ArrayList<TestReceiver>()

    /** `webhooks.allow-private-targets`: the ping test needs the loopback receiver, the URL tests need it off. */
    @Volatile
    private var allowPrivate = false

    private lateinit var endpointDao: MemoryEndpointDao
    private lateinit var deliveryDao: MemoryDeliveryDao
    private lateinit var registry: WebhookRegistry
    private lateinit var webhookService: WebhookService
    private lateinit var endpointService: WebhookEndpointService
    private val armed = AtomicInteger()
    private val activityLogs = CopyOnWriteArrayList<PanelActivityLog>()

    @Volatile
    private var actingUser = 7L
    private val failureResets = CopyOnWriteArrayList<Long>()
    private var previousContext: AnnotationConfigApplicationContext? = null
    private var port = 0

    /** Fresh route instances per test: a route keeps the service bean it looked up first. */
    private lateinit var routes: List<WebhookPanelApi>

    @BeforeAll
    fun startVertx() {
        WebhookTestSupport.installGson()
        vertx = Vertx.vertx()
    }

    @AfterAll
    fun stopVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    @BeforeEach
    fun setUp() {
        clock.set(1_760_000_000_000L)
        allowPrivate = false
        armed.set(0)
        failureResets.clear()
        resolver.set("hooks.example.test", "93.184.216.34")
        resolver.set("discord.com", "162.159.135.232")

        endpointDao = MemoryEndpointDao()
        deliveryDao = MemoryDeliveryDao()
        registry = WebhookRegistry(null)

        val pool = fakePool()
        val outbound = WebhookTestSupport.outbound(vertx, resolver)
        val sender = WebhookSender(outbound, { cipher }, { clock.get() / 1000 * 1000 }, "test", { allowPrivate })

        webhookService = WebhookService(
            pool = { pool }, endpoints = endpointDao, deliveries = deliveryDao, registry = registry, cipher = { cipher },
            sender = sender, site = { SiteInfo("Test site", "https://example.test") }, activeSources = { emptySet() },
            now = { clock.get() }, random = Random(1)
        )

        endpointService = WebhookEndpointService(
            store = object : WebhookStore {
                override suspend fun <T> read(block: suspend (SqlClient) -> T): T = block(NO_CLIENT)

                override suspend fun <T> write(block: suspend (SqlClient) -> T): T = block(NO_CLIENT)
            },
            endpoints = endpointDao, deliveries = deliveryDao, webhooks = webhookService, registry = registry,
            cipher = { cipher },
            checkUrl = { url, discord -> outbound.check(url, allowPrivate, discord).refusal },
            resetFailures = { _, id -> failureResets += id },
            arm = { armed.incrementAndGet() },
            titleOf = { if (it == WebhookEvents.CORE) "Pano" else it.replaceFirstChar { c -> c.uppercase() } },
            now = { clock.get() }
        )

        previousContext = runCatching { Main.applicationContext }.getOrNull()

        Main.applicationContext = AnnotationConfigApplicationContext().apply {
            registerBean(Logger::class.java, java.util.function.Supplier { LoggerFactory.getLogger("WebhookPanelApiTest") })
            registerBean(WebhookEndpointService::class.java, java.util.function.Supplier { endpointService })
            refresh()
        }

        activityLogs.clear()
        WebhookPanelApi.actorOverride = object : WebhookActor {
            override suspend fun userId(context: RoutingContext): Long = actingUser

            override suspend fun record(context: RoutingContext, build: (userId: Long, username: String) -> PanelActivityLog) {
                activityLogs += build(actingUser, "admin")
            }
        }

        routes = listOf(
            PanelGetWebhooksAPI(), PanelGetWebhookEventsAPI(), PanelCreateWebhookAPI(), PanelUpdateWebhookAPI(),
            PanelDeleteWebhookAPI(), PanelTestWebhookAPI(), PanelGetWebhookEndpointDeliveriesAPI(),
            PanelGetWebhookDeliveriesAPI(), PanelGetWebhookDeliveryAPI(), PanelRedeliverWebhookAPI()
        )

        port = startServer()
    }

    @AfterEach
    fun tearDown() {
        receivers.forEach { it.close() }
        receivers.clear()

        previousContext?.let { Main.applicationContext = it }
        WebhookPanelApi.actorOverride = null
    }

    private fun receiver(handler: (com.panomc.platform.webhook.ReceivedRequest) -> ReceiverReply = { ReceiverReply(200) }) =
        TestReceiver(vertx, handler).also { receivers += it }

    // ---- the router, built from the real route classes -----------------------------------------------------------------

    private class FailureApi : Api() {
        override val paths = listOf(Path("/x", RouteType.GET))

        override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
            ValidationHandlerBuilder.create(schemaRepository).build()

        override suspend fun onBeforeHandle(context: RoutingContext) = Unit

        override suspend fun handle(context: RoutingContext): Result = Successful()
    }

    private fun <T> Future<T>.blockingGet(): T = toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS)

    private fun startServer(): Int {
        val repository = SchemaRepository.create(JsonSchemaOptions().setBaseUri("https://panomc.com").setDraft(Draft.DRAFT7))
        val router = Router.router(vertx)
        val failure = FailureApi().getFailureHandler()

        for (route in routes) {
            for (path in route.paths) {
                val url = ApiPaths.resolve(path.url, route.mount, route.namespace, null)

                router.route(path.routeType.vertxHttpMethod, url)
                    .handler(BodyHandler.create(false))
                    .handler(route.getValidationHandler(repository))
                    .handler { context ->
                        CoroutineScope(context.vertx().dispatcher()).launch {
                            try {
                                val result = route.execute(context)

                                context.response().setStatusCode(result.getStatusCode())
                                    .putHeader("content-type", "application/json; charset=utf-8")
                                    .end(result.encode())
                            } catch (e: Throwable) {
                                context.fail(e)
                            }
                        }
                    }
                    .failureHandler(failure)
            }
        }

        return vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private class Answer(val status: Int, val json: JsonObject) {
        val errorCode: String? get() = json.getJsonObject("error")?.getString("code")
        val fields: Map<String, Any?> get() = json.getJsonObject("error")?.getJsonObject("fields")?.map ?: emptyMap()
    }

    private fun call(method: HttpMethod, path: String, body: JsonObject? = null): Answer {
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))

        try {
            return client.request(method, port, "127.0.0.1", "/api/v1/panel$path")
                .compose { request ->
                    if (body != null) {
                        request.putHeader("content-type", "application/json")
                        request.send(body.encode())
                    } else {
                        request.send()
                    }
                }
                .compose { response ->
                    response.body().map { buffer ->
                        val text = buffer.toString(Charsets.UTF_8)

                        Answer(response.statusCode(), if (text.isBlank()) JsonObject() else JsonObject(text))
                    }
                }
                .blockingGet()
        } finally {
            client.close()
        }
    }

    private fun get(path: String) = call(HttpMethod.GET, path)

    private fun post(path: String, body: JsonObject? = null) = call(HttpMethod.POST, path, body ?: JsonObject())

    private fun put(path: String, body: JsonObject) = call(HttpMethod.PUT, path, body)

    private fun delete(path: String) = call(HttpMethod.DELETE, path)

    private fun hook(vararg extra: Pair<String, Any?>): JsonObject {
        val body = JsonObject()
            .put("name", "Discord relay")
            .put("url", "https://hooks.example.test/in")
            .put("events", JsonArray().add("core.user.registered"))

        extra.forEach { (k, v) -> body.put(k, v) }

        return body
    }

    private fun created(vararg extra: Pair<String, Any?>): Pair<Long, String?> {
        val answer = post("/webhooks", hook(*extra))

        assertEquals(200, answer.status, answer.json.encode())

        return answer.json.getLong("id") to answer.json.getString("secret")
    }

    private fun deliveryRow(
        endpointId: Long, event: String = "core.user.registered", status: WebhookDeliveryStatus = WebhookDeliveryStatus.SUCCEEDED,
        eventId: String = java.util.UUID.randomUUID().toString(), source: String = WebhookEvents.sourceOf(event)
    ) = WebhookDelivery(
        endpointId = endpointId, source = source, eventId = eventId, event = event, url = "https://hooks.example.test/in",
        secret = cipher.encrypt("whsec_hidden"), signing = WebhookSigning.HMAC_SHA256, body = """{"id":"$eventId"}""",
        status = status, attempts = 1, lastStatusCode = 200, lastResponse = "ok", createdAt = clock.get(), updatedAt = clock.get()
    )

    // ---- every route demands the platform settings permission ----------------------------------------------------------

    @Test
    fun `the ten routes are mounted under panel webhooks and panel webhook-deliveries and demand the platform settings permission`() {
        val paths = routes.flatMap { r ->
            r.paths.map { "${it.routeType} ${ApiPaths.resolve(it.url, r.mount, r.namespace, null)}" }
        }.sorted()

        assertEquals(
            listOf(
                "DELETE /api/v1/panel/webhooks/:id",
                "GET /api/v1/panel/webhook-deliveries",
                "GET /api/v1/panel/webhook-deliveries/:id",
                "GET /api/v1/panel/webhooks",
                "GET /api/v1/panel/webhooks/:id/deliveries",
                "GET /api/v1/panel/webhooks/events",
                "POST /api/v1/panel/webhook-deliveries/:id/redeliver",
                "POST /api/v1/panel/webhooks",
                "POST /api/v1/panel/webhooks/:id/test",
                "PUT /api/v1/panel/webhooks/:id"
            ),
            paths
        )

        routes.forEach { assertEquals(ManagePlatformSettingsPermission::class.java, it.requiredPermission()::class.java, it.javaClass.simpleName) }
        routes.forEach { assertEquals(Mount.API, it.mount) }
    }

    // ---- GET /webhooks, GET /webhooks/events ----------------------------------------------------------------------------

    @Test
    fun `the list is items plus the limit and masks the secret and every header value`(): Unit = runBlocking {
        val (id, secret) = created("signing" to "HMAC_SHA256", "headers" to JsonObject().put("X-Api-Key", "k-123"))

        assertNotNull(secret)

        val answer = get("/webhooks")

        assertEquals(200, answer.status)
        assertEquals(setOf("items", "limit"), answer.json.fieldNames())
        assertEquals(50, answer.json.getInteger("limit"))

        val item = answer.json.getJsonArray("items").getJsonObject(0)

        assertEquals(id, item.getLong("id"))
        assertEquals("********", item.getString("secret"))
        assertEquals(JsonObject().put("X-Api-Key", "********"), item.getJsonObject("headers"))
        assertFalse(answer.json.encode().contains(secret!!), "the secret leaked into the list")
        assertFalse(answer.json.encode().contains("k-123"), "a header value leaked into the list")
    }

    @Test
    fun `an endpoint without a secret reads secret null`() {
        created()

        assertNull(get("/webhooks").json.getJsonArray("items").getJsonObject(0).getValue("secret"))
    }

    @Test
    fun `the events catalogue lists core first with the six events and shows plugin events once they are declared`() {
        registry.register(
            "market",
            listOf(
                WebhookEventType("order.paid", JsonObject().put("total", 10)),
                WebhookEventType("action.run", subscribable = false)
            ),
            null, null
        )

        val items = get("/webhooks/events").json.getJsonArray("items")
        val core = items.getJsonObject(0)

        assertEquals("core", core.getString("source"))
        assertEquals("Pano", core.getString("title"))
        assertEquals(
            listOf("core.post.published", "core.test.ping", "core.ticket.created", "core.ticket.replied", "core.user.deleted", "core.user.registered"),
            core.getJsonArray("events").map { (it as JsonObject).getString("name") }
        )

        val ping = core.getJsonArray("events").map { it as JsonObject }.first { it.getString("name") == "core.test.ping" }

        assertFalse(ping.getBoolean("subscribable"))
        assertEquals(JsonObject().put("message", "ping"), ping.getJsonObject("sample"))
        assertEquals(setOf("name", "subscribable", "sample"), ping.fieldNames())

        val market = items.getJsonObject(1)

        assertEquals("market", market.getString("source"))
        assertEquals("Market", market.getString("title"))
        assertEquals(
            listOf("market.action.run", "market.order.paid"),
            market.getJsonArray("events").map { (it as JsonObject).getString("name") }
        )
    }

    // ---- POST /webhooks, PUT /webhooks/:id -------------------------------------------------------------------------------

    @Test
    fun `creating an endpoint answers id and shows a generated hmac secret once`(): Unit = runBlocking {
        val answer = post("/webhooks", hook("signing" to "HMAC_SHA256", "maxAttempts" to 3))

        assertEquals(200, answer.status)
        assertEquals(setOf("id", "secret"), answer.json.fieldNames())
        assertTrue(answer.json.getString("secret").startsWith("whsec_"))

        val stored = endpointDao.rows.getValue(answer.json.getLong("id"))

        assertEquals(WebhookSigning.HMAC_SHA256, stored.signing)
        assertEquals(3, stored.maxAttempts)
        assertNotEquals(answer.json.getString("secret"), stored.secret, "the secret must be stored encrypted")
        assertEquals(answer.json.getString("secret"), cipher.decrypt(stored.secret!!))
        assertEquals(JsonArray().add("core.user.registered").encode(), stored.events)
        assertTrue(armed.get() > 0, "the delivery timer is armed")

        val without = post("/webhooks", hook())

        assertEquals(setOf("id"), without.json.fieldNames())
    }

    @Test
    fun `a discord endpoint never signs and takes a template`() {
        val answer = post("/webhooks", hook("url" to DISCORD_URL, "format" to "DISCORD", "signing" to "HMAC_SHA256", "template" to """{"content":"x"}"""))

        assertEquals(200, answer.status)
        assertEquals(setOf("id"), answer.json.fieldNames())
        assertEquals(WebhookSigning.NONE, endpointDao.rows.getValue(answer.json.getLong("id")).signing)

        val refused = post("/webhooks", hook("template" to "{}"))

        assertEquals(400, refused.status)
        assertEquals("INVALID_FIELDS", refused.errorCode)
        assertEquals("NOT_ALLOWED", refused.fields["template"])
    }

    @Test
    fun `field mistakes are INVALID_FIELDS with the field names`() {
        val answer = post("/webhooks", JsonObject().put("name", " ").put("events", JsonArray().add("*")).put("url", "").put("maxAttempts", 99).put("format", "XML").put("enabled", "yes"))

        assertEquals(400, answer.status)
        assertEquals("INVALID_FIELDS", answer.errorCode)
        assertEquals(
            mapOf("name" to "REQUIRED", "url" to "REQUIRED", "format" to "INVALID", "maxAttempts" to "OUT_OF_RANGE", "enabled" to "INVALID"),
            answer.fields
        )

        val empty = post("/webhooks", hook("events" to JsonArray()))

        assertEquals("INVALID_FIELDS", empty.errorCode)
        assertEquals("REQUIRED", empty.fields["events"])

        assertEquals(0, endpointDao.rows.size)
    }

    @Test
    fun `updating keeps what is not sent, keeps the secret and keeps a masked header`() {
        val (id, secret) = created("signing" to "HMAC_SHA256", "headers" to JsonObject().put("X-Api-Key", "k-123").put("X-Other", "o-1"))
        val before = endpointDao.rows.getValue(id)

        val answer = put(
            "/webhooks/$id",
            JsonObject().put("name", "Renamed").put("headers", JsonObject().put("X-Api-Key", "********").put("X-New", "n-1"))
        )

        assertEquals(200, answer.status)
        assertEquals(JsonObject().put("id", id), answer.json, "no new secret unless one was generated")

        val after = endpointDao.rows.getValue(id)

        assertEquals("Renamed", after.name)
        assertEquals(before.url, after.url)
        assertEquals(before.events, after.events)
        assertEquals(before.secret, after.secret, "the stored secret is untouched")
        assertEquals(secret, cipher.decrypt(after.secret!!))
        assertEquals(
            JsonObject().put("X-Api-Key", "k-123").put("X-New", "n-1").map,
            JsonObject(cipher.decrypt(after.headers!!)).map
        )
    }

    @Test
    fun `regenerateSecret answers a new secret once, and switching to hmac generates one`() {
        val (id, first) = created("signing" to "HMAC_SHA256")

        val regenerated = put("/webhooks/$id", JsonObject().put("regenerateSecret", true))

        assertEquals(200, regenerated.status)
        assertEquals(setOf("id", "secret"), regenerated.json.fieldNames())
        assertNotEquals(first, regenerated.json.getString("secret"))
        assertEquals(regenerated.json.getString("secret"), cipher.decrypt(endpointDao.rows.getValue(id).secret!!))

        val (plain, none) = created()

        assertNull(none)

        val switched = put("/webhooks/$plain", JsonObject().put("signing", "HMAC_SHA256"))

        assertTrue(switched.json.getString("secret").startsWith("whsec_"))

        val dropped = put("/webhooks/$plain", JsonObject().put("signing", "NONE"))

        assertEquals(setOf("id"), dropped.json.fieldNames())
        assertNull(endpointDao.rows.getValue(plain).secret)
    }

    @Test
    fun `disabling ends the open rows as dead, enabling resets the failure counter`(): Unit = runBlocking {
        val (id, _) = created()
        val open = deliveryDao.put(deliveryRow(id, status = WebhookDeliveryStatus.PENDING))
        val done = deliveryDao.put(deliveryRow(id, status = WebhookDeliveryStatus.SUCCEEDED))

        endpointDao.setFailureCount(id, 7)

        assertEquals(200, put("/webhooks/$id", JsonObject().put("enabled", false)).status)

        assertEquals(WebhookDeliveryStatus.DEAD, deliveryDao.rows.getValue(open).status)
        assertEquals("ENDPOINT_DISABLED", deliveryDao.rows.getValue(open).lastError)
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, deliveryDao.rows.getValue(done).status)
        assertEquals("MANUAL", endpointDao.rows.getValue(id).disabledReason)
        assertTrue(failureResets.isEmpty())

        assertEquals(200, put("/webhooks/$id", JsonObject().put("enabled", true)).status)

        assertEquals(listOf(id), failureResets.toList())
        assertNull(endpointDao.rows.getValue(id).disabledReason)
        assertTrue(endpointDao.rows.getValue(id).enabled)
    }

    // ---- the error codes -----------------------------------------------------------------------------------------------

    @Test
    fun `WEBHOOK_URL_REFUSED carries the reason in fields url`() {
        val cases = listOf(
            "ftp://hooks.example.test/in" to "SCHEME",
            "http://169.254.169.254/latest" to "PRIVATE_ADDRESS",
            "http://127.0.0.1/in" to "PRIVATE_ADDRESS",
            "https://hooks.example.test:9/in" to "PORT",
            "https://user:pw@hooks.example.test/in" to "USERINFO",
            "https://unknown.example.test/in" to "DNS",
            "not a url" to "MALFORMED",
            "https://hooks.example.test/" + "a".repeat(1100) to "MALFORMED"
        )

        for ((url, reason) in cases) {
            val answer = post("/webhooks", hook("url" to url))

            assertEquals(400, answer.status, url)
            assertEquals("WEBHOOK_URL_REFUSED", answer.errorCode, url)
            assertEquals(mapOf("url" to reason), answer.fields, url)
        }

        assertEquals(0, endpointDao.rows.size)

        val (id, _) = created()
        val update = put("/webhooks/$id", JsonObject().put("url", "http://10.0.0.1/in"))

        assertEquals("WEBHOOK_URL_REFUSED", update.errorCode)
        assertEquals("https://hooks.example.test/in", endpointDao.rows.getValue(id).url, "a refused update changes nothing")
    }

    @Test
    fun `a discord endpoint must point at a discord webhook url`() {
        val answer = post("/webhooks", hook("format" to "DISCORD"))

        assertEquals("WEBHOOK_URL_REFUSED", answer.errorCode)
        assertEquals("DISCORD_URL", answer.fields["url"])
    }

    @Test
    fun `WEBHOOK_LIMIT at fifty endpoints`(): Unit = runBlocking {
        repeat(50) {
            endpointDao.add(WebhookEndpoint(name = "e$it", url = "https://hooks.example.test/$it"), NO_CLIENT)
        }

        val answer = post("/webhooks", hook())

        assertEquals(400, answer.status)
        assertEquals("WEBHOOK_LIMIT", answer.errorCode)
        assertEquals(50, answer.json.getJsonObject("error").getJsonObject("details").getInteger("limit"))
        assertEquals(50, endpointDao.rows.size)
    }

    @Test
    fun `WEBHOOK_UNKNOWN_EVENT for a name nobody declares and for events that cannot be subscribed to`() {
        registry.register("market", listOf(WebhookEventType("order.paid"), WebhookEventType("action.run", subscribable = false)), null, null)

        for (events in listOf(listOf("nope.thing.happened"), listOf("core.test.ping"), listOf("market.action.run"), listOf("shop.*"), listOf("core.user.registered", "core.user.nope"))) {
            val answer = post("/webhooks", hook("events" to JsonArray(events)))

            assertEquals(400, answer.status, events.toString())
            assertEquals("WEBHOOK_UNKNOWN_EVENT", answer.errorCode, events.toString())
            assertEquals("UNKNOWN_EVENT", answer.fields["events"])
        }

        val named = post("/webhooks", hook("events" to JsonArray().add("core.user.registered").add("nope.x")))

        assertEquals(listOf("nope.x"), named.json.getJsonObject("error").getJsonObject("details").getJsonArray("events").list)

        // what is declared is accepted, including the wildcards
        for (events in listOf(listOf("market.order.paid"), listOf("market.*"), listOf("core.*"), listOf("*"), listOf("core.user.registered", "market.order.paid"))) {
            assertEquals(200, post("/webhooks", hook("events" to JsonArray(events))).status, events.toString())
        }
    }

    @Test
    fun `an event the endpoint already subscribes to is kept when its plugin is stopped`() {
        registry.register("market", listOf(WebhookEventType("order.paid")), null, null)

        val (id, _) = created("events" to JsonArray().add("market.order.paid"))

        registry.unregister("market")

        assertEquals(200, put("/webhooks/$id", JsonObject().put("name", "still fine")).status)
        assertEquals(200, put("/webhooks/$id", JsonObject().put("events", JsonArray().add("market.order.paid"))).status)
        assertEquals("WEBHOOK_UNKNOWN_EVENT", put("/webhooks/$id", JsonObject().put("events", JsonArray().add("market.order.refunded"))).errorCode)
    }

    @Test
    fun `WEBHOOK_HEADERS_INVALID names each header`() {
        val answer = post(
            "/webhooks",
            hook("headers" to JsonObject().put("X-Pano-Evil", "1").put("Host", "evil").put("Good", "ok").put("Bad Name", "x").put("Value", "café"))
        )

        assertEquals(400, answer.status)
        assertEquals("WEBHOOK_HEADERS_INVALID", answer.errorCode)
        assertEquals(
            mapOf(
                "headers.X-Pano-Evil" to "INVALID_NAME", "headers.Host" to "INVALID_NAME",
                "headers.Bad Name" to "INVALID_NAME", "headers.Value" to "INVALID_VALUE"
            ),
            answer.fields
        )

        val many = JsonObject().also { o -> repeat(11) { o.put("X-H$it", "v") } }

        assertEquals("TOO_MANY", post("/webhooks", hook("headers" to many)).fields["headers"])

        val unknownMask = post("/webhooks", hook("headers" to JsonObject().put("X-New", "********")))

        assertEquals("WEBHOOK_HEADERS_INVALID", unknownMask.errorCode)
        assertEquals(0, endpointDao.rows.size)
    }

    @Test
    fun `WEBHOOK_NOT_FOUND on every route that takes an endpoint or a delivery`() {
        val calls = listOf(
            put("/webhooks/404", JsonObject().put("name", "x")),
            delete("/webhooks/404"),
            post("/webhooks/404/test"),
            get("/webhooks/404/deliveries"),
            get("/webhook-deliveries/404"),
            post("/webhook-deliveries/404/redeliver")
        )

        calls.forEach {
            assertEquals(404, it.status, it.json.encode())
            assertEquals("WEBHOOK_NOT_FOUND", it.errorCode)
        }
    }

    @Test
    fun `WEBHOOK_IN_FLIGHT when a sending delivery is redelivered`(): Unit = runBlocking {
        val (id, _) = created()
        val sending = deliveryDao.put(deliveryRow(id, status = WebhookDeliveryStatus.SENDING))

        val answer = post("/webhook-deliveries/$sending/redeliver")

        assertEquals(409, answer.status)
        assertEquals("WEBHOOK_IN_FLIGHT", answer.errorCode)
        assertEquals(WebhookDeliveryStatus.SENDING, deliveryDao.rows.getValue(sending).status)
    }

    @Test
    fun `the six panel error classes keep their codes and statuses and build without arguments`() {
        val expected = listOf(
            Triple(com.panomc.platform.webhook.WebhookUrlRefused(), "WEBHOOK_URL_REFUSED", 400),
            Triple(com.panomc.platform.webhook.WebhookLimit(), "WEBHOOK_LIMIT", 400),
            Triple(com.panomc.platform.webhook.WebhookUnknownEvent(), "WEBHOOK_UNKNOWN_EVENT", 400),
            Triple(com.panomc.platform.webhook.WebhookHeadersInvalid(), "WEBHOOK_HEADERS_INVALID", 400),
            Triple(com.panomc.platform.webhook.WebhookNotFound(), "WEBHOOK_NOT_FOUND", 404),
            Triple(com.panomc.platform.webhook.WebhookInFlight(), "WEBHOOK_IN_FLIGHT", 409)
        )

        expected.forEach { (error, code, status) ->
            assertEquals(code, error.code)
            assertEquals(status, error.getStatusCode(), code)
            assertTrue(com.panomc.platform.model.Error.CODE_PATTERN.matches(code))
        }
    }

    // ---- DELETE ----------------------------------------------------------------------------------------------------------

    @Test
    fun `deleting removes the endpoint and ends its open rows as dead`(): Unit = runBlocking {
        val (id, _) = created()
        val (other, _) = created()
        val open = deliveryDao.put(deliveryRow(id, status = WebhookDeliveryStatus.FAILED))
        val sending = deliveryDao.put(deliveryRow(id, status = WebhookDeliveryStatus.SENDING))
        val otherOpen = deliveryDao.put(deliveryRow(other, status = WebhookDeliveryStatus.PENDING))

        val answer = delete("/webhooks/$id")

        assertEquals(200, answer.status)
        assertEquals(JsonObject(), answer.json)
        assertNull(endpointDao.rows[id])
        assertEquals(WebhookDeliveryStatus.DEAD, deliveryDao.rows.getValue(open).status)
        assertEquals(WebhookDeliveryStatus.DEAD, deliveryDao.rows.getValue(sending).status)
        assertEquals("ENDPOINT_DELETED", deliveryDao.rows.getValue(open).lastError)
        assertEquals(WebhookDeliveryStatus.PENDING, deliveryDao.rows.getValue(otherOpen).status)
    }

    // ---- POST /webhooks/:id/test: the ping, verified by a local receiver -------------------------------------------------

    @Test
    fun `the test ping reaches a local receiver with a signature that verifies`() {
        allowPrivate = true

        val r = receiver { ReceiverReply(200, "ok") }
        val answer0 = post("/webhooks", hook("url" to "${r.baseUrl}/hook", "signing" to "HMAC_SHA256"))

        assertEquals(200, answer0.status, answer0.json.encode())

        val id = answer0.json.getLong("id")
        val secret = answer0.json.getString("secret")

        val answer = post("/webhooks/$id/test")

        assertEquals(200, answer.status, answer.json.encode())
        assertEquals(setOf("statusCode", "durationMs", "error"), answer.json.fieldNames())
        assertEquals(200, answer.json.getInteger("statusCode"))
        assertNull(answer.json.getValue("error"))

        assertEquals(1, r.requests.size)

        val request = r.requests.first()
        val envelope = JsonObject(request.body)

        assertEquals("POST", request.method)
        assertEquals("/hook", request.path)
        assertEquals("core.test.ping", request.header("X-Pano-Event"))
        assertEquals(envelope.getString("id"), request.header("X-Pano-Event-Id"))
        assertEquals("core.test.ping", envelope.getString("event"))
        assertEquals("core", envelope.getString("source"))
        assertEquals(1, envelope.getInteger("apiVersion"))
        assertEquals(JsonObject().put("name", "Test site").put("url", "https://example.test"), envelope.getJsonObject("site"))
        assertEquals(JsonObject().put("message", "ping"), envelope.getJsonObject("data"))
        assertEquals(setOf("id", "event", "source", "createdAt", "apiVersion", "site", "data"), envelope.fieldNames())

        val signature = request.header(WebhookSigner.HEADER)

        assertNotNull(signature)
        assertTrue(WebhookSigner.verify(signature!!, secret, request.body, clock.get() / 1000), "the receiver could not verify the signature")
        assertFalse(WebhookSigner.verify(signature, "whsec_wrong", request.body, clock.get() / 1000))

        // the log has the ping as one finished row and the endpoint's failure counter is untouched
        val log = get("/webhooks/$id/deliveries").json.getJsonArray("items")

        assertEquals(1, log.size())
        assertEquals("SUCCEEDED", log.getJsonObject(0).getString("status"))
        assertEquals("core.test.ping", log.getJsonObject(0).getString("event"))
        assertEquals(0, endpointDao.rows.getValue(id).failureCount)
    }

    @Test
    fun `the test ping reports what went wrong without failing the call`() {
        allowPrivate = true

        val r = receiver { ReceiverReply(500, "boom") }
        val (id, _) = created("url" to "${r.baseUrl}/hook")

        val answer = post("/webhooks/$id/test")

        assertEquals(200, answer.status)
        assertEquals(500, answer.json.getInteger("statusCode"))
        assertNotNull(answer.json.getString("error"))

        // a url that is refused at send time (the flag is off again) comes back as an error, not as a 4xx
        allowPrivate = false

        val refused = post("/webhooks/$id/test")

        assertEquals(200, refused.status)
        assertNull(refused.json.getValue("statusCode"))
        assertTrue(refused.json.getString("error").startsWith("URL_GUARD:"))
    }

    // ---- the delivery log -------------------------------------------------------------------------------------------------

    @Test
    fun `the deliveries of an endpoint and the global log use the page shape and filter by source and status`(): Unit = runBlocking {
        val (a, _) = created()
        val (b, _) = created()

        repeat(12) { deliveryDao.put(deliveryRow(a)) }
        deliveryDao.put(deliveryRow(a, status = WebhookDeliveryStatus.DEAD))
        deliveryDao.put(deliveryRow(b, event = "market.order.paid", source = "market"))

        val first = get("/webhooks/$a/deliveries?pageSize=5")

        assertEquals(200, first.status)
        assertEquals(setOf("items", "page"), first.json.fieldNames())
        assertEquals(5, first.json.getJsonArray("items").size())
        assertEquals(JsonObject().put("number", 1).put("size", 5).put("totalItems", 13).put("totalPages", 3), first.json.getJsonObject("page"))

        val ids = first.json.getJsonArray("items").map { (it as JsonObject).getLong("id") }

        assertEquals(ids.sortedDescending(), ids, "newest first")

        val item = first.json.getJsonArray("items").getJsonObject(0)

        assertEquals(
            setOf("id", "endpointId", "source", "event", "eventId", "subjectRef", "ownerRef", "status", "attempts", "maxAttempts", "nextAttemptAt", "lastStatusCode", "lastError", "durationMs", "createdAt", "deliveredAt"),
            item.fieldNames()
        )

        assertEquals(1, get("/webhooks/$a/deliveries?status=DEAD").json.getJsonArray("items").size())
        assertEquals(14, get("/webhook-deliveries").json.getJsonObject("page").getInteger("totalItems"))
        assertEquals(1, get("/webhook-deliveries?source=market").json.getJsonArray("items").size())
        assertEquals(1, get("/webhook-deliveries?source=core&status=DEAD").json.getJsonArray("items").size())

        val bad = get("/webhook-deliveries?status=MAYBE")

        assertEquals(400, bad.status)
        assertEquals("INVALID_FIELDS", bad.errorCode)
        assertEquals("UNKNOWN_VALUE", bad.fields["status"])

        assertEquals("INVALID_FIELDS", get("/webhook-deliveries?pageSize=101").errorCode)
        assertEquals(404, get("/webhooks/$a/deliveries?page=9").status)
        assertEquals("PAGE_NOT_FOUND", get("/webhooks/$a/deliveries?page=9").errorCode)
        assertEquals(0, get("/webhooks/$b/deliveries?status=DEAD").json.getJsonArray("items").size())
    }

    @Test
    fun `one delivery shows the body and the last response but never the secret`(): Unit = runBlocking {
        val (id, _) = created()
        val deliveryId = deliveryDao.put(deliveryRow(id, eventId = "11111111-1111-4111-8111-111111111111"))

        val answer = get("/webhook-deliveries/$deliveryId")

        assertEquals(200, answer.status)
        assertEquals("""{"id":"11111111-1111-4111-8111-111111111111"}""", answer.json.getString("body"))
        assertEquals("ok", answer.json.getString("lastResponse"))
        assertEquals("https://hooks.example.test/in", answer.json.getString("url"))
        assertEquals("HMAC_SHA256", answer.json.getString("signing"))
        assertFalse(answer.json.containsKey("secret"))
        assertFalse(answer.json.encode().contains("whsec_hidden"))
        assertFalse(answer.json.encode().contains("v1:"), "the encrypted secret leaked")
    }

    @Test
    fun `redelivering puts the same row back to pending and arms the timer`(): Unit = runBlocking {
        val (id, _) = created()
        val dead = deliveryDao.put(deliveryRow(id, status = WebhookDeliveryStatus.DEAD, eventId = "22222222-2222-4222-8222-222222222222"))

        armed.set(0)

        val answer = post("/webhook-deliveries/$dead/redeliver")

        assertEquals(200, answer.status)
        assertEquals(JsonObject(), answer.json)

        val row = deliveryDao.rows.getValue(dead)

        assertEquals(WebhookDeliveryStatus.PENDING, row.status)
        assertEquals(0, row.attempts)
        assertEquals("22222222-2222-4222-8222-222222222222", row.eventId)
        assertEquals(1, deliveryDao.rows.size)
        assertEquals(1, armed.get())
    }

    // ---- CX-06: activity logs and the test button limit --------------------------------------------------------------------

    @Test
    fun `create, update, delete and redeliver write an activity log with the user and the endpoint name`() {
        val (id, _) = created()
        val dead = deliveryDao.put(deliveryRow(id, status = WebhookDeliveryStatus.DEAD, eventId = "33333333-3333-4333-8333-333333333333"))

        assertEquals(200, put("/webhooks/$id", hook("name" to "Renamed")).status)
        assertEquals(200, post("/webhook-deliveries/$dead/redeliver").status)
        assertEquals(200, delete("/webhooks/$id").status)

        assertEquals(
            listOf("CreatedWebhookLog", "UpdatedWebhookLog", "RedeliveredWebhookLog", "DeletedWebhookLog"),
            activityLogs.map { it::class.java.simpleName }
        )
        assertEquals(listOf("Discord relay", "Renamed", "Renamed", "Renamed"), activityLogs.map { it.details.getString("name") })
        assertTrue(activityLogs.all { it.userId == 7L && it.details.getString("username") == "admin" })
    }

    @Test
    fun `a refused call writes no activity log`() {
        assertEquals(404, delete("/webhooks/404").status)
        assertEquals(404, post("/webhook-deliveries/404/redeliver").status)
        assertTrue(activityLogs.isEmpty())
    }

    @Test
    fun `the test button answers 429 RATE_LIMITED from the eleventh call in a minute, per user`() {
        val (id, _) = created()

        repeat(10) { assertEquals(200, post("/webhooks/$id/test").status) }

        val limited = post("/webhooks/$id/test")

        assertEquals(429, limited.status)
        assertEquals("RATE_LIMITED", limited.errorCode)

        actingUser = 8L

        assertEquals(200, post("/webhooks/$id/test").status)
    }

    // ---- importEndpoint ----------------------------------------------------------------------------------------------------

    @Test
    fun `importEndpoint stores an endpoint with the secret and headers encrypted by the core key`(): Unit = runBlocking {
        val id = endpointService.importEndpoint(
            name = "Old market hook", url = "https://hooks.example.test/old", events = listOf("market.*", "market.order.paid", "market.*"),
            format = WebhookFormat.JSON, signing = WebhookSigning.HMAC_SHA256, plainSecret = "whsec_plain_secret_value",
            plainHeaders = mapOf("X-Api-Key" to "k"), template = "ignored for json", enabled = false, maxAttempts = 99
        )

        val stored = endpointDao.rows.getValue(id)

        assertEquals("Old market hook", stored.name)
        assertEquals(JsonArray().add("market.*").add("market.order.paid").encode(), stored.events)
        assertEquals("whsec_plain_secret_value", cipher.decrypt(stored.secret!!))
        assertEquals("k", JsonObject(cipher.decrypt(stored.headers!!)).getString("X-Api-Key"))
        assertNull(stored.template)
        assertFalse(stored.enabled)
        assertEquals(20, stored.maxAttempts)

        // it reads back masked like any other endpoint, and it is not capped by the limit
        val view = endpointService.view(stored)

        assertEquals("********", view["secret"])
        assertEquals(mapOf("X-Api-Key" to "********"), view["headers"])

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                endpointService.importEndpoint("x", "https://hooks.example.test/x", listOf("*"), WebhookFormat.JSON, WebhookSigning.HMAC_SHA256, null, null, null, true, 8)
            }
        }
    }

    // ---- the core events: exact payload keys -------------------------------------------------------------------------------

    private fun coreEvents(): WebhookCoreEvents = WebhookCoreEvents(
        publishCore = { event, subjectKey, data, client, ref -> webhookService.publishCore(event, subjectKey, data, client, ref) },
        arm = { armed.incrementAndGet() },
        siteUrl = { "https://example.test/" }
    )

    private suspend fun subscribeToEverything(): Long =
        endpointDao.add(WebhookEndpoint(name = "all", url = "https://hooks.example.test/in", events = JsonArray().add("*").encode()), NO_CLIENT)

    private fun queued(): List<JsonObject> = deliveryDao.rows.values.map { JsonObject(it.body) }

    private fun assertEnvelope(envelope: JsonObject, event: String, dataKeys: Set<String>): JsonObject {
        assertEquals(setOf("id", "event", "source", "createdAt", "apiVersion", "site", "data"), envelope.fieldNames())
        assertEquals(event, envelope.getString("event"))
        assertEquals("core", envelope.getString("source"))
        assertEquals(1, envelope.getInteger("apiVersion"))
        assertEquals(dataKeys, envelope.getJsonObject("data").fieldNames(), "keys of the data of $event")

        return envelope.getJsonObject("data")
    }

    @Test
    fun `core user registered carries id, username and registeredAt and nothing else`(): Unit = runBlocking {
        subscribeToEverything()

        coreEvents().userRegistered(User(id = 12, username = "Steve", email = "steve@example.test", registeredIp = "10.9.8.7", registerDate = 1_700_000_000_000L), NO_CLIENT)

        val data = assertEnvelope(queued().single(), "core.user.registered", setOf("id", "username", "registeredAt"))

        assertEquals(12, data.getLong("id"))
        assertEquals("Steve", data.getString("username"))
        assertEquals(1_700_000_000_000L, data.getLong("registeredAt"))
        assertFalse(deliveryDao.rows.values.single().body.contains("steve@example.test"))
        assertFalse(deliveryDao.rows.values.single().body.contains("10.9.8.7"))
        assertEquals("user:12", deliveryDao.rows.values.single().subjectRef)
        assertEquals(1, armed.get())
    }

    @Test
    fun `core user deleted carries id and username`(): Unit = runBlocking {
        subscribeToEverything()

        coreEvents().userDeleted(12, "Steve", NO_CLIENT)

        val data = assertEnvelope(queued().single(), "core.user.deleted", setOf("id", "username"))

        assertEquals(12, data.getLong("id"))
        assertEquals("Steve", data.getString("username"))
    }

    @Test
    fun `core ticket created carries id, title, categoryId, userId and username`(): Unit = runBlocking {
        subscribeToEverything()

        coreEvents().ticketCreated(7, "Help with my rank", 1, 12, "Steve", NO_CLIENT)

        val data = assertEnvelope(queued().single(), "core.ticket.created", setOf("id", "title", "categoryId", "userId", "username"))

        assertEquals(7, data.getLong("id"))
        assertEquals("Help with my rank", data.getString("title"))
        assertEquals(1, data.getLong("categoryId"))
        assertEquals(12, data.getLong("userId"))
    }

    @Test
    fun `core ticket replied carries ticketId, messageId, userId and staff, never the text`(): Unit = runBlocking {
        subscribeToEverything()

        coreEvents().ticketReplied(7, 31, 12, false, NO_CLIENT)
        coreEvents().ticketReplied(7, 32, 3, true, NO_CLIENT)

        val all = queued()

        assertEquals(2, all.size, "a different message is a different event")

        val first = assertEnvelope(all[0], "core.ticket.replied", setOf("ticketId", "messageId", "userId", "staff"))
        val second = assertEnvelope(all[1], "core.ticket.replied", setOf("ticketId", "messageId", "userId", "staff"))

        assertEquals(false, first.getBoolean("staff"))
        assertEquals(true, second.getBoolean("staff"))
        assertEquals(31, first.getLong("messageId"))
        assertEquals(7, first.getLong("ticketId"))

        // replaying the same message inserts nothing
        coreEvents().ticketReplied(7, 31, 12, false, NO_CLIENT)

        assertEquals(2, deliveryDao.rows.size)
    }

    @Test
    fun `core post published carries id, title, url, categoryId and publishedAt`(): Unit = runBlocking {
        subscribeToEverything()

        val post = Post(
            id = 4, title = "Server news", categoryId = 1, writerUserId = 3, text = "<p>secret draft text</p>",
            date = 1_700_000_000_000L, status = PostStatus.PUBLISHED, thumbnailUrl = "", url = "server-news-4"
        )

        coreEvents().postPublished(post, NO_CLIENT)

        val data = assertEnvelope(queued().single(), "core.post.published", setOf("id", "title", "url", "categoryId", "publishedAt"))

        assertEquals(4, data.getLong("id"))
        assertEquals("https://example.test/post/server-news-4", data.getString("url"))
        assertEquals(1_700_000_000_000L, data.getLong("publishedAt"))
        assertFalse(deliveryDao.rows.values.single().body.contains("secret draft text"))

        // publishing again later is a new event, replaying the same publish is not
        coreEvents().postPublished(post, NO_CLIENT)

        assertEquals(1, deliveryDao.rows.size)

        coreEvents().postPublished(Post(id = 4, title = "Server news", categoryId = 1, writerUserId = 3, text = "", date = 1_700_000_100_000L, thumbnailUrl = "", url = "server-news-4"), NO_CLIENT)

        assertEquals(2, deliveryDao.rows.size)
    }

    @Test
    fun `wildcards never match the ping and the core wildcard matches the core events`(): Unit = runBlocking {
        val star = subscribeToEverything()
        val core = endpointDao.add(WebhookEndpoint(name = "core", url = "https://hooks.example.test/in", events = JsonArray().add("core.*").encode()), NO_CLIENT)
        val one = endpointDao.add(WebhookEndpoint(name = "one", url = "https://hooks.example.test/in", events = JsonArray().add("core.user.deleted").encode()), NO_CLIENT)

        coreEvents().userDeleted(12, "Steve", NO_CLIENT)

        assertEquals(setOf(star, core, one), deliveryDao.rows.values.map { it.endpointId }.toSet())

        deliveryDao.rows.clear()

        assertEquals(0, webhookService.publishCore(WebhookEvents.TEST_PING, "x", JsonObject(), NO_CLIENT))
    }

    @Test
    fun `an emit never fails the business call`(): Unit = runBlocking {
        val failing = WebhookCoreEvents(publishCore = { _, _, _, _, _ -> throw IllegalStateException("database is gone") })

        failing.userDeleted(1, "x", NO_CLIENT)
        failing.userRegistered(User(id = 1, username = "x", registeredIp = ""), NO_CLIENT)

        assertEquals(0, armed.get())

        // a context without the bean is swallowed as well
        Main.applicationContext = AnnotationConfigApplicationContext().apply {
            registerBean(Logger::class.java, java.util.function.Supplier { LoggerFactory.getLogger("WebhookPanelApiTest") })
            refresh()
        }

        WebhookCoreEvents.fire { userDeleted(1, "x", NO_CLIENT) }
    }

    @Test
    fun `no rows and no timer without a listener`(): Unit = runBlocking {
        coreEvents().userDeleted(12, "Steve", NO_CLIENT)

        assertEquals(0, deliveryDao.rows.size)
        assertEquals(0, armed.get())
    }

    // ---- the emit sites ------------------------------------------------------------------------------------------------------

    private fun source(path: String): String {
        val base = File("src/main/kotlin/com/panomc/platform")
        val file = base.resolve(path)

        if (!base.exists()) {
            // started from another directory: the check is only meaningful next to the sources
            return ""
        }

        return file.readText()
    }

    @Test
    fun `every emit site of the core events calls the helper after the existing code`() {
        val sites = mapOf(
            "auth/AuthProvider.kt" to listOf("WebhookCoreEvents.fire { userRegistered(user, sqlClient) }"),
            "route/api/auth/RegisterAPI.kt" to listOf("WebhookCoreEvents.fire { userRegistered(registeredUser, sqlClient) }", "WebhookCoreEvents.fire { userRegistered(registeredUser, sqlClient) }"),
            "route/api/panel/players/PanelDeletePlayerAPI.kt" to listOf("WebhookCoreEvents.fire { userDeleted(user.id, user.username, sqlClient) }"),
            "route/api/ticket/CreateTicketAPI.kt" to listOf("WebhookCoreEvents.fire { ticketCreated(id, title, categoryId, userId, username, sqlClient) }"),
            "route/api/ticket/SendTicketMessageAPI.kt" to listOf("WebhookCoreEvents.fire { ticketReplied(ticketId, messageId, userId, false, sqlClient) }"),
            "route/api/panel/ticket/PanelSendTicketMessageAPI.kt" to listOf("WebhookCoreEvents.fire { ticketReplied(ticketId, messageId, userId, true, sqlClient) }"),
            "route/api/panel/post/PanelUpdatePostStatusAPI.kt" to listOf("WebhookCoreEvents.fire { postPublished(published, sqlClient) }"),
            "route/api/panel/post/PanelCreateOrUpdatePostAPI.kt" to listOf("WebhookCoreEvents.fire { postPublished(published, sqlClient) }")
        )

        for ((path, calls) in sites) {
            val text = source(path)

            if (text.isEmpty()) return

            assertEquals(calls.size, Regex("WebhookCoreEvents\\.fire").findAll(text).count(), path)
            calls.forEach { assertTrue(text.contains(it), "$path: $it") }
        }

        // the AuthMe import does not emit a delete
        val authMe = File("src/main/kotlin/com/panomc/platform/route/api/panel/settings")

        if (authMe.exists()) {
            authMe.walkTopDown().filter { it.extension == "kt" }.forEach { assertFalse(it.readText().contains("userDeleted"), it.path) }
        }
    }

    // ---- the Spring wiring ------------------------------------------------------------------------------------------------

    @Test
    fun `the endpoint service and the core events resolve by type next to the webhook beans`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val previousConfigFile = System.getProperty("pano.configFile")
        val objenesis = org.springframework.objenesis.ObjenesisStd()

        System.setProperty("pano.configFile", dir.resolve("config.conf").toString())

        val context = AnnotationConfigApplicationContext()

        try {
            context.beanFactory.registerSingleton("vertx", vertx)
            context.beanFactory.registerSingleton("databaseManager", objenesis.newInstance(com.panomc.platform.db.DatabaseManager::class.java))
            context.beanFactory.registerSingleton("configManager", objenesis.newInstance(com.panomc.platform.config.ConfigManager::class.java))
            context.beanFactory.registerSingleton("pluginManager", objenesis.newInstance(com.panomc.platform.PluginManager::class.java))
            context.beanFactory.registerSingleton("setupManager", objenesis.newInstance(com.panomc.platform.setup.SetupManager::class.java))
            context.beanFactory.registerSingleton("endpointDao", com.panomc.platform.db.implementation.WebhookEndpointDaoImpl())
            context.beanFactory.registerSingleton("deliveryDao", com.panomc.platform.db.implementation.WebhookDeliveryDaoImpl())
            context.register(
                com.panomc.platform.webhook.WebhookBeans::class.java,
                com.panomc.platform.webhook.WebhookEndpointBeans::class.java,
                com.panomc.platform.webhook.WebhookCoreEventsBeans::class.java
            )
            context.refresh()

            val service = context.getBean(WebhookEndpointService::class.java)

            assertTrue(service === context.getBean(WebhookEndpointService::class.java), "a singleton")
            assertNotNull(context.getBean(WebhookCoreEvents::class.java))
            assertFalse(context.getBean(com.panomc.platform.webhook.WebhookDispatcher::class.java).isStarted, "wiring does not arm the timer")
        } finally {
            context.close()

            if (previousConfigFile == null) System.clearProperty("pano.configFile") else System.setProperty("pano.configFile", previousConfigFile)
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------

    /** A pool whose connections and transactions do nothing: every DAO call in these tests goes to the in-memory DAOs. */
    private fun fakePool(): Pool {
        val loader = WebhookPanelApiTest::class.java.classLoader

        val query = Proxy.newProxyInstance(loader, arrayOf(io.vertx.sqlclient.Query::class.java)) { proxy, method, _ ->
            when {
                method.name == "toString" -> "FakeQuery"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> false
                Future::class.java.isAssignableFrom(method.returnType) -> Future.succeededFuture<Any?>(null)
                else -> null
            }
        }
        val transaction = Proxy.newProxyInstance(loader, arrayOf(io.vertx.sqlclient.Transaction::class.java)) { proxy, method, _ ->
            when {
                method.name == "toString" -> "FakeTransaction"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> false
                Future::class.java.isAssignableFrom(method.returnType) -> Future.succeededFuture<Any?>(null)
                else -> null
            }
        }
        val connection = Proxy.newProxyInstance(loader, arrayOf(io.vertx.sqlclient.SqlConnection::class.java)) { proxy, method, _ ->
            when {
                method.name == "toString" -> "FakeConnection"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> false
                method.name == "begin" -> Future.succeededFuture(transaction)
                method.name == "query" || method.name == "preparedQuery" -> query
                Future::class.java.isAssignableFrom(method.returnType) -> Future.succeededFuture<Any?>(null)
                else -> null
            }
        }

        val pool = Proxy.newProxyInstance(loader, arrayOf(Pool::class.java)) { proxy, method, _ ->
            when {
                method.name == "toString" -> "FakePool"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> false
                method.name == "getConnection" -> Future.succeededFuture(connection)
                method.name == "query" || method.name == "preparedQuery" -> query
                Future::class.java.isAssignableFrom(method.returnType) -> Future.succeededFuture<Any?>(null)
                else -> null
            }
        } as Pool

        return pool
    }

    companion object {
        private const val DISCORD_URL = "https://discord.com/api/webhooks/123456/abcdef"

        /** What the in-memory DAOs are handed instead of a connection. */
        private val NO_CLIENT: SqlClient = Proxy.newProxyInstance(
            WebhookPanelApiTest::class.java.classLoader, arrayOf(SqlClient::class.java)
        ) { _, method, _ -> if (method.name == "toString") "NoClient" else null } as SqlClient
    }
}
