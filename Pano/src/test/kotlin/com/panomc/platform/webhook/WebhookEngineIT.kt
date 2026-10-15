package com.panomc.platform.webhook

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.webhook.DirectWebhook
import com.panomc.platform.api.webhook.RenderedBody
import com.panomc.platform.api.webhook.WebhookEventType
import com.panomc.platform.db.dao.WebhookDeliveryDao
import com.panomc.platform.db.dao.WebhookEndpointDao
import com.panomc.platform.db.implementation.WebhookDeliveryDaoImpl
import com.panomc.platform.db.implementation.WebhookEndpointDaoImpl
import com.panomc.platform.db.model.WebhookDelivery
import com.panomc.platform.db.model.WebhookDeliveryStatus
import com.panomc.platform.db.model.WebhookEndpoint
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLConnection
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.function.Executable
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * The webhook engine against a real MariaDB and a local receiver: publishing (rolled-back transaction, replay,
 * wildcards, two plugins), the DAO queries, claim / send / retry / dead / auto-disable / redeliver / purge, direct deliveries
 * and the dispatcher. Gated like the other database tests: `PANO_IT_MARIADB=host:port` + `PANO_IT_MARIADB_PASSWORD`
 * (root). It creates its own throwaway database and drops it at the end.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "PANO_IT_MARIADB", matches = ".+")
class WebhookEngineIT {
    private val address = System.getenv("PANO_IT_MARIADB") ?: "127.0.0.1:3306"
    private val rootPassword = System.getenv("PANO_IT_MARIADB_PASSWORD") ?: ""
    private val database = "pano_webhook_it_" + UUID.randomUUID().toString().replace("-", "").take(10)

    private lateinit var vertx: Vertx
    private lateinit var pool: Pool
    private val receivers = ArrayList<TestReceiver>()

    private val clock = AtomicLong(1_760_000_000_000L)
    private val cipher = WebhookTestSupport.cipher()
    private val active = HashSet<String>()

    private val endpoints: WebhookEndpointDao = object : WebhookEndpointDaoImpl() {
        override fun prefix() = "t_"
    }
    private val deliveries: WebhookDeliveryDao = object : WebhookDeliveryDaoImpl() {
        override fun prefix() = "t_"
    }

    private lateinit var registry: WebhookRegistry
    private lateinit var service: WebhookService
    private lateinit var publisher: WebhookPublisherImpl
    private lateinit var dispatcher: WebhookDispatcher

    private fun options(db: String?): MySQLConnectOptions {
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "3306").toInt()

        return MySQLConnectOptions().setHost(host).setPort(port).setUser("root").setPassword(rootPassword).also {
            if (db != null) it.setDatabase(db)
        }
    }

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        WebhookTestSupport.installGson()
        vertx = Vertx.vertx()

        val admin = MySQLConnection.connect(vertx, options(null)).coAwait()
        admin.query("CREATE DATABASE `$database` CHARACTER SET utf8mb4").execute().coAwait()
        admin.close().coAwait()

        pool = MySQLBuilder.pool().with(PoolOptions().setMaxSize(8)).connectingTo(options(database)).using(vertx).build()

        pool.query(WebhookEndpointDaoImpl.createTableQuery("t_webhook_endpoint")).execute().coAwait()
        pool.query(WebhookDeliveryDaoImpl.createTableQuery("t_webhook_delivery")).execute().coAwait()
    }

    @AfterAll
    fun tearDown(): Unit = runBlocking {
        runCatching { pool.close().coAwait() }

        val admin = MySQLConnection.connect(vertx, options(null)).coAwait()
        admin.query("DROP DATABASE IF EXISTS `$database`").execute().coAwait()
        admin.close().coAwait()
        vertx.close().coAwait()
    }

    @BeforeEach
    fun reset(): Unit = runBlocking {
        pool.query("TRUNCATE TABLE `t_webhook_delivery`").execute().coAwait()
        pool.query("TRUNCATE TABLE `t_webhook_endpoint`").execute().coAwait()

        active.clear()
        clock.set(1_760_000_000_000L)

        registry = WebhookRegistry(null)

        val sender = WebhookSender(WebhookTestSupport.outbound(vertx, StubResolver()), { cipher }, { clock.get() }, "test", { true })

        service = WebhookService(
            pool = { pool }, endpoints = endpoints, deliveries = deliveries, registry = registry, cipher = { cipher },
            sender = sender, site = { SiteInfo("Test site", "https://example.test") }, activeSources = { active.toSet() },
            now = { clock.get() }, random = Random(1), disableAfter = 3
        )
        // a timer far in the future: the tests tick by hand
        dispatcher = WebhookDispatcher(vertx, service, intervalMs = 3_600_000L, now = { clock.get() })
        publisher = WebhookPublisherImpl(service, registry, dispatcher)
    }

    @AfterEach
    fun closeReceivers() {
        dispatcher.stop()
        receivers.forEach { it.close() }
        receivers.clear()
    }

    // ----- helpers ---------------------------------------------------------------------------------------------------

    private class TestPlugin(id: String) : PanoPlugin() {
        init {
            pluginId = id
        }
    }

    private val market = TestPlugin("pano-plugin-market")
    private val shop = TestPlugin("pano-plugin-shop")

    private fun receiver(handler: (ReceivedRequest) -> ReceiverReply = { ReceiverReply(200) }) =
        TestReceiver(vertx, handler).also { receivers += it }

    private suspend fun endpoint(
        url: String = "http://127.0.0.1:9/hook", events: List<String> = listOf("*"), enabled: Boolean = true,
        format: WebhookFormat = WebhookFormat.JSON, signing: WebhookSigning = WebhookSigning.NONE, secret: String? = null,
        maxAttempts: Int = 8, template: String? = null
    ): Long = endpoints.add(
        WebhookEndpoint(
            name = "e", url = url, events = JsonArray(events).encode(), format = format, signing = signing,
            secret = secret?.let { cipher.encrypt(it) }, enabled = enabled, maxAttempts = maxAttempts, template = template,
            createdAt = clock.get(), updatedAt = clock.get()
        ),
        pool
    )

    private suspend fun rows(): List<WebhookDelivery> = deliveries.getPage(null, null, null, 100, 0, pool).reversed()

    private suspend fun count(client: SqlClient): Long = deliveries.countFiltered(null, null, null, client)

    private fun data(k: String = "v") = JsonObject().put("k", k)

    // ----- publishing ------------------------------------------------------------------------------------------------

    @Test
    fun `a publish writes one pending row per matching endpoint with the envelope of the contract`(): Unit = runBlocking {
        val a = endpoint(events = listOf("market.order.paid"))
        val b = endpoint(events = listOf("market.*"))
        endpoint(events = listOf("market.order.refunded"))

        val inserted = publisher.publish(
            market, "order.paid", "42", JsonObject().put("total", 10), subjectRef = "order:42",
            extra = JsonObject().put("testMode", true)
        )

        assertEquals(2, inserted)

        val rows = rows()

        assertEquals(listOf(a, b), rows.map { it.endpointId })

        val row = rows.first()

        assertEquals(WebhookDeliveryStatus.PENDING, row.status)
        assertEquals("market", row.source)
        assertEquals("market.order.paid", row.event)
        assertEquals("order:42", row.subjectRef)
        assertNull(row.ownerRef)
        assertEquals(clock.get(), row.nextAttemptAt)
        assertEquals(WebhookEvents.eventId("market.order.paid", "42", a), row.eventId)

        val envelope = JsonObject(row.body)

        assertEquals(
            listOf("id", "event", "source", "createdAt", "apiVersion", "site", "data", "testMode"),
            envelope.fieldNames().toList()
        )
        assertEquals(row.eventId, envelope.getString("id"))
        assertEquals("market.order.paid", envelope.getString("event"))
        assertEquals("market", envelope.getString("source"))
        assertEquals(clock.get(), envelope.getLong("createdAt"))
        assertEquals(1, envelope.getInteger("apiVersion"))
        assertEquals("Test site", envelope.getJsonObject("site").getString("name"))
        assertEquals("https://example.test", envelope.getJsonObject("site").getString("url"))
        assertEquals(10, envelope.getJsonObject("data").getInteger("total"))
        assertTrue(envelope.getBoolean("testMode"))
    }

    @Test
    fun `a publish in a rolled back transaction leaves no row`(): Unit = runBlocking {
        endpoint()

        val conn = pool.connection.coAwait()
        val tx = conn.begin().coAwait()

        assertEquals(1, publisher.publish(market, "order.paid", "1", data(), sqlClient = conn))
        assertEquals(1, count(conn), "the caller sees its own row inside the transaction")
        assertEquals(0, count(pool), "nobody else does before the commit")

        tx.rollback().coAwait()
        conn.close().coAwait()

        assertEquals(0, count(pool))
    }

    @Test
    fun `a publish in a committed transaction keeps its row`(): Unit = runBlocking {
        endpoint()

        val conn = pool.connection.coAwait()
        val tx = conn.begin().coAwait()

        publisher.publish(market, "order.paid", "1", data(), sqlClient = conn)
        tx.commit().coAwait()
        conn.close().coAwait()

        assertEquals(1, count(pool))
    }

    @Test
    fun `a replay of the same event and subject inserts nothing`(): Unit = runBlocking {
        endpoint()
        endpoint()

        assertEquals(2, publisher.publish(market, "order.paid", "7", data()))
        assertEquals(0, publisher.publish(market, "order.paid", "7", data("changed")))
        assertEquals(2, count(pool))

        // another subject is a new event
        assertEquals(2, publisher.publish(market, "order.paid", "8", data()))
        assertEquals(4, count(pool))
    }

    @Test
    fun `a replay inside a transaction does not break it`(): Unit = runBlocking {
        endpoint()

        publisher.publish(market, "order.paid", "7", data())

        val conn = pool.connection.coAwait()
        val tx = conn.begin().coAwait()

        assertEquals(0, publisher.publish(market, "order.paid", "7", data(), sqlClient = conn))
        assertEquals(1, publisher.publish(market, "order.paid", "8", data(), sqlClient = conn))

        tx.commit().coAwait()
        conn.close().coAwait()

        assertEquals(2, count(pool))
    }

    @Test
    fun `wildcard rules`(): Unit = runBlocking {
        val star = endpoint(events = listOf("*"))
        val marketAll = endpoint(events = listOf("market.*"))
        val coreAll = endpoint(events = listOf("core.*"))
        val exact = endpoint(events = listOf("shop.order.paid"))
        endpoint(events = listOf("market.*"), enabled = false)

        publisher.publish(market, "order.paid", "1", data())
        publisher.publish(shop, "order.paid", "1", data())
        service.publishCore("core.user.registered", "1", data())

        val byEvent = rows().groupBy { it.event }.mapValues { (_, v) -> v.map { it.endpointId }.sorted() }

        assertEquals(listOf(star, marketAll).sorted(), byEvent["market.order.paid"])
        assertEquals(listOf(star, exact).sorted(), byEvent["shop.order.paid"])
        assertEquals(listOf(star, coreAll).sorted(), byEvent["core.user.registered"])
    }

    @Test
    fun `a star never matches the test ping or an event that is not subscribable`(): Unit = runBlocking {
        endpoint(events = listOf("*"))
        endpoint(events = listOf("market.*"))

        publisher.register(market, listOf(WebhookEventType("action.grant", subscribable = false)))

        assertEquals(0, publisher.publish(market, "action.grant", "1", data()))
        assertEquals(0, service.publishCore("core.test.ping", "1", data()))
        assertEquals(0, count(pool))
    }

    @Test
    fun `two plugins with the same event and subject do not collide`(): Unit = runBlocking {
        endpoint()
        endpoint()

        assertEquals(2, publisher.publish(market, "order.paid", "1", data("m")))
        assertEquals(2, publisher.publish(shop, "order.paid", "1", data("s")))

        val rows = rows()

        assertEquals(4, rows.size)
        assertEquals(4, rows.map { it.eventId }.toSet().size)
        assertEquals(setOf("market", "shop"), rows.map { it.source }.toSet())
    }

    @Test
    fun `a plugin cannot emit core events and a bad name is refused`() = runBlocking<Unit> {
        endpoint()

        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.publish("core", "user.registered", "1", data()) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { publisher.publish(market, "Order.Paid", "1", data()) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { publisher.publish(market, "order.paid", "", data()) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.publishCore("market.order.paid", "1", data()) } }
        assertEquals(0, count(pool))
    }

    @Test
    fun `extra may not shadow an envelope key`() = runBlocking<Unit> {
        endpoint()

        for (key in listOf("id", "event", "source", "createdAt", "apiVersion", "site", "data")) {
            assertThrows(
                IllegalArgumentException::class.java,
                Executable { runBlocking { publisher.publish(market, "order.paid", "1", data(), extra = JsonObject().put(key, 1)) } },
                key
            )
        }

        assertEquals(0, count(pool))
    }

    @Test
    fun `the first publish arms the dispatcher`(): Unit = runBlocking {
        assertFalse(dispatcher.isStarted)

        publisher.publish(market, "order.paid", "1", data())

        assertTrue(dispatcher.isStarted)
    }

    @Test
    fun `an undeclared event is registered on its first publish`(): Unit = runBlocking {
        endpoint()

        publisher.publish(market, "order.paid", "1", data())

        assertTrue(registry.isKnown("market.order.paid"))
    }

    @Test
    fun `hasListeners follows the endpoints and sees the callers transaction`(): Unit = runBlocking {
        assertFalse(publisher.hasListeners(market, "order.paid"))

        val conn = pool.connection.coAwait()
        val tx = conn.begin().coAwait()
        endpoints.add(WebhookEndpoint(name = "e", url = "http://x.test/", events = """["market.order.paid"]"""), conn)

        assertTrue(publisher.hasListeners(market, "order.paid", conn))
        assertFalse(publisher.hasListeners(market, "order.paid"))
        assertFalse(publisher.hasListeners(market, "order.refunded", conn))

        tx.rollback().coAwait()
        conn.close().coAwait()

        assertFalse(publisher.hasListeners(market, "order.paid"))
    }

    @Test
    fun `a discord endpoint gets the plugin renderer, else the generic embed`(): Unit = runBlocking {
        endpoint(events = listOf("market.*"), format = WebhookFormat.DISCORD, template = "tpl")

        publisher.register(market, emptyList(), { event, envelope, template ->
            RenderedBody(JsonObject().put("content", "$event/${envelope.getString("event")}/$template").encode(), "TEMPLATE_ERROR")
        })
        publisher.publish(market, "order.paid", "1", data())

        val rendered = rows().single()

        assertEquals("""{"content":"order.paid/market.order.paid/tpl"}""", rendered.body)
        assertEquals("TEMPLATE_ERROR", rendered.lastError)
        assertEquals(WebhookDeliveryStatus.PENDING, rendered.status)

        // no renderer: the generic embed with event, site and the start of data
        registry.unregister("market")
        publisher.publish(market, "order.refunded", "1", JsonObject().put("text", "y".repeat(3000)))

        val embed = JsonObject(rows().last().body).getJsonArray("embeds").getJsonObject(0)

        assertEquals("market.order.refunded", embed.getString("title"))
        assertEquals("Test site", embed.getJsonObject("footer").getString("text"))
        assertTrue(embed.getString("description").length < 1100)
    }

    @Test
    fun `a renderer that throws makes a dead row and never the publish`(): Unit = runBlocking {
        endpoint(format = WebhookFormat.DISCORD)

        publisher.register(market, emptyList(), { _, _, _ -> throw IllegalStateException("template") })

        assertEquals(1, publisher.publish(market, "order.paid", "1", data()))

        val row = rows().single()

        assertEquals(WebhookDeliveryStatus.DEAD, row.status)
        assertEquals("RENDER_FAILED", row.lastError)
        assertNull(row.nextAttemptAt)
    }

    // ----- DAO queries -----------------------------------------------------------------------------------------------

    @Test
    fun `the log pages and filters by source, status and endpoint`(): Unit = runBlocking {
        val a = endpoint()
        val b = endpoint(events = listOf("shop.*"))

        for (i in 1..5) publisher.publish(market, "order.paid", "$i", data(), subjectRef = "order:$i")
        for (i in 1..3) publisher.publish(shop, "order.paid", "$i", data())

        assertEquals(5 + 3 * 2, count(pool))
        assertEquals(5, deliveries.countFiltered("market", null, null, pool))
        assertEquals(3, deliveries.countFiltered("shop", null, b, pool).toInt())
        assertEquals(8, deliveries.countFiltered(null, null, a, pool))
        assertEquals(0, deliveries.countFiltered(null, WebhookDeliveryStatus.DEAD, null, pool))
        assertEquals(11, deliveries.countFiltered(null, WebhookDeliveryStatus.PENDING, null, pool))

        val first = deliveries.getPage("market", null, null, 2, 0, pool)
        val second = deliveries.getPage("market", null, null, 2, 2, pool)

        assertEquals(2, first.size)
        assertTrue(first[0].id > first[1].id, "newest first")
        assertTrue(first[1].id > second[0].id)
        assertEquals(1, deliveries.countBySubjectRef("market", "order:3", pool))
        assertEquals(0, deliveries.countBySubjectRef("shop", "order:3", pool))
    }

    // ----- sending ---------------------------------------------------------------------------------------------------

    @Test
    fun `the dispatcher sends a signed delivery and stores the outcome`(): Unit = runBlocking {
        val r = receiver { ReceiverReply(200, "ok") }
        val id = endpoint(r.baseUrl + "/hook", signing = WebhookSigning.HMAC_SHA256, secret = "s3cret")

        publisher.publish(market, "order.paid", "1", data())

        assertEquals(1, dispatcher.tick())

        val row = rows().single()

        assertEquals(WebhookDeliveryStatus.SUCCEEDED, row.status)
        assertEquals(1, row.attempts)
        assertEquals(200, row.lastStatusCode)
        assertEquals("ok", row.lastResponse)
        assertEquals(clock.get(), row.deliveredAt)
        assertNull(row.claimedUntil)

        val request = r.requests.single()

        assertEquals(row.body, request.body)
        assertEquals("market.order.paid", request.header("X-Pano-Event"))
        assertEquals(row.eventId, request.header("X-Pano-Event-Id"))
        assertEquals(row.id.toString(), request.header("X-Pano-Delivery"))
        assertTrue(WebhookSigner.verify(request.header("X-Pano-Signature")!!, "s3cret", request.body, clock.get() / 1000))
        assertEquals(0, endpoints.getById(id, pool)!!.failureCount)
        assertEquals(0, dispatcher.tick(), "nothing is due any more")
    }

    @Test
    fun `a failing delivery is retried with a backoff and ends dead after its attempts`(): Unit = runBlocking {
        val r = receiver { ReceiverReply(500, "boom") }
        val id = endpoint(r.baseUrl + "/hook", maxAttempts = 3)

        publisher.publish(market, "order.paid", "1", data())

        dispatcher.tick()

        var row = rows().single()

        assertEquals(WebhookDeliveryStatus.FAILED, row.status)
        assertEquals(1, row.attempts)
        assertEquals("HTTP_500", row.lastError)
        assertNotNull(row.nextAttemptAt)
        assertTrue(row.nextAttemptAt!! in (clock.get() + 24_000)..(clock.get() + 36_000), "30 s with 20 % jitter")
        assertEquals(0, dispatcher.tick(), "not due yet")

        clock.addAndGet(60_000)
        dispatcher.tick()
        clock.addAndGet(3_600_000)
        dispatcher.tick()

        row = rows().single()

        assertEquals(WebhookDeliveryStatus.DEAD, row.status)
        assertEquals(3, row.attempts)
        assertNull(row.nextAttemptAt)
        assertEquals(3, r.requests.size)
        assertEquals("3", r.requests.last().header("X-Pano-Attempt"))

        val endpoint = endpoints.getById(id, pool)!!

        assertEquals(3, endpoint.failureCount)
        assertFalse(endpoint.enabled, "the third consecutive failure reaches the limit of this test (3)")
    }

    @Test
    fun `a 410 answer is dead at once`(): Unit = runBlocking {
        val r = receiver { ReceiverReply(410) }
        endpoint(r.baseUrl + "/hook")

        publisher.publish(market, "order.paid", "1", data())
        dispatcher.tick()

        val row = rows().single()

        assertEquals(WebhookDeliveryStatus.DEAD, row.status)
        assertEquals("GONE", row.lastError)
    }

    @Test
    fun `an endpoint is disabled after consecutive failures and its open rows end dead`(): Unit = runBlocking {
        val r = receiver { ReceiverReply(500) }
        val id = endpoint(r.baseUrl + "/hook")

        for (i in 1..5) publisher.publish(market, "order.paid", "$i", data())

        // disableAfter is 3 in this test; rows of one endpoint go out one after the other
        dispatcher.tick()

        val endpoint = endpoints.getById(id, pool)!!

        assertFalse(endpoint.enabled)
        assertEquals(WebhookEndpointDao.AUTO_DISABLED_REASON, endpoint.disabledReason)
        assertEquals(3, endpoint.failureCount)

        // the third failure disabled the endpoint: every open row ended DEAD, and the rows behind it were never sent
        assertEquals(3, r.requests.size)
        assertEquals(5, rows().size)
        assertTrue(rows().all { it.status == WebhookDeliveryStatus.DEAD && it.lastError == "ENDPOINT_DISABLED" })

        // a disabled endpoint receives no new rows
        assertEquals(0, publisher.publish(market, "order.paid", "9", data()))
    }

    @Test
    fun `a row of a deleted or disabled endpoint ends dead without a request`(): Unit = runBlocking {
        val r = receiver()
        val gone = endpoint(r.baseUrl + "/hook")
        val off = endpoint(r.baseUrl + "/hook")

        publisher.publish(market, "order.paid", "1", data())
        endpoints.delete(gone, pool)
        endpoints.update(endpoints.getById(off, pool)!!.let {
            WebhookEndpoint(it.id, it.name, it.url, it.events, it.format, it.signing, it.secret, it.headers, it.template, false, it.maxAttempts)
        }, clock.get(), pool)

        dispatcher.tick()

        assertTrue(r.requests.isEmpty())
        assertEquals(setOf("ENDPOINT_DELETED", "ENDPOINT_DISABLED"), rows().map { it.lastError }.toSet())
        assertTrue(rows().all { it.status == WebhookDeliveryStatus.DEAD })
    }

    @Test
    fun `deleting an endpoint kills its open rows`(): Unit = runBlocking {
        val id = endpoint()

        publisher.publish(market, "order.paid", "1", data())
        publisher.publish(market, "order.paid", "2", data())

        assertEquals(2, deliveries.deadenOpenRows(id, "ENDPOINT_DELETED", clock.get(), pool))
        assertTrue(rows().all { it.status == WebhookDeliveryStatus.DEAD && it.lastError == "ENDPOINT_DELETED" })
    }

    @Test
    fun `a refused target is dead at once`(): Unit = runBlocking {
        // private targets are allowed in this test's sender; a metadata address never is
        endpoint("http://169.254.169.254/latest")

        publisher.publish(market, "order.paid", "1", data())
        dispatcher.tick()

        val row = rows().single()

        assertEquals(WebhookDeliveryStatus.DEAD, row.status)
        assertEquals("URL_GUARD:PRIVATE_ADDRESS", row.lastError)
    }

    @Test
    fun `a claim that ran out is retried and counted`(): Unit = runBlocking {
        endpoint()
        publisher.publish(market, "order.paid", "1", data())

        val claimed = service.claimDue()

        assertEquals(1, claimed.size)
        assertEquals(1, claimed.single().attempts)
        assertEquals(WebhookDeliveryStatus.SENDING, rows().single().status)

        // the worker died; the claim expires after 60 s
        clock.addAndGet(61_000)

        val again = service.claimDue()

        assertEquals(1, again.size)
        assertEquals(2, again.single().attempts)
        assertEquals("CLAIM_EXPIRED", deliveries.getById(again.single().id, pool)!!.lastError)
    }

    @Test
    fun `two claims never take the same row`(): Unit = runBlocking {
        endpoint()
        for (i in 1..10) publisher.publish(market, "order.paid", "$i", data())

        val results = (1..4).map { async(Dispatchers.Default) { service.claimDue(10) } }
            .map { it.await() }

        val ids = results.flatten().map { it.id }

        assertEquals(10, ids.size)
        assertEquals(10, ids.toSet().size)
    }

    @Test
    fun `redeliver puts a finished row back and refuses one in flight`(): Unit = runBlocking {
        val r = receiver()
        endpoint(r.baseUrl + "/hook")

        publisher.publish(market, "order.paid", "1", data())
        dispatcher.tick()

        val id = rows().single().id

        assertEquals(RedeliverResult.OK, service.redeliver(id))

        val row = rows().single()

        assertEquals(WebhookDeliveryStatus.PENDING, row.status)
        assertEquals(0, row.attempts)
        assertEquals(id, row.id)

        dispatcher.tick()

        assertEquals(2, r.requests.size)
        assertEquals(r.requests[0].header("X-Pano-Event-Id"), r.requests[1].header("X-Pano-Event-Id"))

        pool.query("UPDATE `t_webhook_delivery` SET `status` = 'SENDING'").execute().coAwait()

        assertEquals(RedeliverResult.IN_FLIGHT, service.redeliver(id))
        assertEquals(RedeliverResult.NOT_FOUND, service.redeliver(9999))
    }

    @Test
    fun `the test ping goes out at once, works on a disabled endpoint and leaves the counters alone`(): Unit = runBlocking {
        val r = receiver { ReceiverReply(202) }
        val id = endpoint(r.baseUrl + "/hook", enabled = false, signing = WebhookSigning.HMAC_SHA256, secret = "k")

        val result = service.sendTestPing(id)!!

        assertEquals(202, result.statusCode)
        assertNull(result.error)

        val request = r.requests.single()

        assertEquals("core.test.ping", request.header("X-Pano-Event"))
        assertEquals("ping", JsonObject(request.body).getJsonObject("data").getString("message"))
        assertEquals("core", JsonObject(request.body).getString("source"))
        assertTrue(WebhookSigner.verify(request.header("X-Pano-Signature")!!, "k", request.body, clock.get() / 1000))

        val row = rows().single()

        assertEquals(WebhookDeliveryStatus.SUCCEEDED, row.status)
        assertEquals(1, row.maxAttempts)
        assertEquals(0, endpoints.getById(id, pool)!!.failureCount)
        assertNull(service.sendTestPing(9999))
    }

    @Test
    fun `finished rows older than 30 days are purged`(): Unit = runBlocking {
        val r = receiver()
        endpoint(r.baseUrl + "/hook")

        publisher.publish(market, "order.paid", "1", data())
        publisher.publish(market, "order.paid", "2", data())
        dispatcher.tick()
        publisher.publish(market, "order.paid", "3", data())

        clock.addAndGet(29L * 24 * 3_600_000)
        assertEquals(0, service.purgeOld())

        clock.addAndGet(2L * 24 * 3_600_000)
        // the pending row is old as well but not finished: it stays
        assertEquals(2, service.purgeOld())
        assertEquals(1, rows().size)
        assertEquals(WebhookDeliveryStatus.PENDING, rows().single().status)
    }

    // ----- direct deliveries -----------------------------------------------------------------------------------------

    private fun direct(url: String, eventId: String = "dir-1", ref: String = "delivery:1", signing: WebhookSigning = WebhookSigning.NONE, secret: String? = null) =
        DirectWebhook(url, "action.grant", eventId, """{"action":"grant"}""", signing, secret, maxAttempts = 2, ownerRef = ref)

    @Test
    fun `a direct delivery is queued once, sent only while its plugin runs and reported back`(): Unit = runBlocking {
        val r = receiver()
        val outcomes = ArrayList<Pair<String?, WebhookDeliveryStatus>>()
        val seenInside = ArrayList<WebhookDeliveryStatus>()
        val seenOutside = ArrayList<WebhookDeliveryStatus>()

        publisher.register(market, listOf(WebhookEventType("action.grant", subscribable = false)), null) { client, delivery, decision ->
            // the listener runs on the connection of the transaction that stores the end
            seenInside += deliveries.getById(delivery.id, client)!!.status
            seenOutside += deliveries.getById(delivery.id, pool)!!.status
            outcomes += delivery.ownerRef to decision.status
        }

        val id = publisher.enqueueDirect(market, direct(r.baseUrl + "/d", signing = WebhookSigning.HMAC_SHA256, secret = "dk"))

        assertNotNull(id)
        assertNull(publisher.enqueueDirect(market, direct(r.baseUrl + "/d")), "the same event id is queued once")

        val row = deliveries.getById(id!!, pool)!!

        assertEquals(0L, row.endpointId)
        assertEquals("market", row.source)
        assertEquals("delivery:1", row.ownerRef)
        assertEquals("market.action.grant", row.event)
        assertNotEquals("dk", row.secret, "the secret is stored encrypted")
        assertEquals("dk", cipher.decrypt(row.secret!!))

        // plugin stopped: not claimed
        assertEquals(0, dispatcher.tick())
        assertEquals(WebhookDeliveryStatus.PENDING, deliveries.getById(id, pool)!!.status)

        active += "market"
        assertEquals(1, dispatcher.tick())

        val sent = deliveries.getById(id, pool)!!

        assertEquals(WebhookDeliveryStatus.SUCCEEDED, sent.status)
        assertEquals(listOf<Pair<String?, WebhookDeliveryStatus>>("delivery:1" to WebhookDeliveryStatus.SUCCEEDED), outcomes)
        assertEquals(listOf(WebhookDeliveryStatus.SUCCEEDED), seenInside)
        assertEquals(listOf(WebhookDeliveryStatus.SENDING), seenOutside)
        assertEquals("""{"action":"grant"}""", r.requests.single().body)
        assertEquals("dir-1", r.requests.single().header("X-Pano-Event-Id"))
        assertTrue(WebhookSigner.verify(r.requests.single().header("X-Pano-Signature")!!, "dk", r.requests.single().body, clock.get() / 1000))
    }

    @Test
    fun `a direct delivery that dies is reported as dead`(): Unit = runBlocking {
        val r = receiver { ReceiverReply(500) }
        val outcomes = ArrayList<WebhookDeliveryStatus>()

        publisher.register(market, emptyList(), null) { _, _, decision -> outcomes += decision.status }
        publisher.enqueueDirect(market, direct(r.baseUrl + "/d"))
        active += "market"

        dispatcher.tick()

        assertTrue(outcomes.isEmpty(), "a retry is not an end")

        clock.addAndGet(3_600_000)
        dispatcher.tick()

        assertEquals(listOf(WebhookDeliveryStatus.DEAD), outcomes)
    }

    @Test
    fun `an outcome listener that throws leaves the row sending for the next claim`(): Unit = runBlocking {
        val r = receiver()

        publisher.register(market, emptyList(), null) { _, _, _ -> throw IllegalStateException("listener") }
        publisher.enqueueDirect(market, direct(r.baseUrl + "/d"))
        active += "market"

        dispatcher.tick()

        assertEquals(WebhookDeliveryStatus.SENDING, rows().single().status)
    }

    @Test
    fun `a direct delivery with an unreadable secret is dead`(): Unit = runBlocking {
        val r = receiver()
        val id = publisher.enqueueDirect(market, direct(r.baseUrl + "/d", signing = WebhookSigning.HMAC_SHA256, secret = "dk"))!!

        pool.query("UPDATE `t_webhook_delivery` SET `secret` = 'v1:AAAA'").execute().coAwait()
        active += "market"
        dispatcher.tick()

        assertEquals("SECRET_UNREADABLE", deliveries.getById(id, pool)!!.lastError)
        assertEquals(WebhookDeliveryStatus.DEAD, deliveries.getById(id, pool)!!.status)
        assertTrue(r.requests.isEmpty())
    }

    @Test
    fun `a direct delivery is checked when queued`() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { publisher.enqueueDirect(market, direct("")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { publisher.enqueueDirect(market, direct("http://x.test/", eventId = "")) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { publisher.enqueueDirect(market, direct("http://x.test/", signing = WebhookSigning.HMAC_SHA256, secret = null)) }
        }
        assertEquals(0, count(pool))
    }

    @Test
    fun `a direct delivery in a rolled back transaction leaves no row`(): Unit = runBlocking {
        val conn = pool.connection.coAwait()
        val tx = conn.begin().coAwait()

        assertNotNull(publisher.enqueueDirect(market, direct("http://x.test/"), conn))

        tx.rollback().coAwait()
        conn.close().coAwait()

        assertEquals(0, count(pool))
    }
}
