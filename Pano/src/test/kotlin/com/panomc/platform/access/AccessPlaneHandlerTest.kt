package com.panomc.platform.access

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.dao.FrontendKeyDao
import com.panomc.platform.db.model.FrontendKey
import com.panomc.platform.util.ProxyDetector
import com.panomc.platform.util.RateLimitManager
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/**
 * The access plane (open front-end plan, doc 05 §2.1, §3.2, §7): a real Vert.x router with the access
 * handler and the rate limiter in front of an endpoint that echoes what the access plane decided.
 *
 * The test client connects from 127.0.0.1, so the socket peer is loopback. A request that carries an
 * `X-Forwarded-For` header is therefore what a reverse proxy in front of Pano looks like: not exempt.
 */
class AccessPlaneHandlerTest {
    private class MemoryDao : FrontendKeyDao() {
        val rows = linkedMapOf<Long, FrontendKey>()
        val lastUsedUpdates = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Long>>()
        private var nextId = 1L

        override suspend fun init(sqlClient: SqlClient) {}

        override suspend fun add(frontendKey: FrontendKey, sqlClient: SqlClient): Long {
            val id = nextId++

            rows[id] = frontendKey.copy(id = id)

            return id
        }

        override suspend fun getAll(sqlClient: SqlClient) = rows.values.toList()

        override suspend fun getById(id: Long, sqlClient: SqlClient) = rows[id]

        override suspend fun count(sqlClient: SqlClient) = rows.size

        override suspend fun deleteById(id: Long, sqlClient: SqlClient) = rows.remove(id) != null

        override suspend fun updateLastUsedAt(id: Long, lastUsedAt: Long, sqlClient: SqlClient) {
            lastUsedUpdates += id to lastUsedAt
        }
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: String) {
        val json: JsonObject get() = JsonObject(body)
    }

    private val sqlClient: SqlClient = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SqlClient::class.java)
    ) { _, method, _ -> error("the access plane must not use the SqlClient itself: ${method.name}") } as SqlClient

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private lateinit var dao: MemoryDao
    private lateinit var service: FrontendKeyService
    private lateinit var rateLimitManager: RateLimitManager
    private var port = 0
    private val mode = java.util.concurrent.atomic.AtomicReference(com.panomc.platform.ui.FrontendMode.CUSTOM_APP)

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        dao = MemoryDao()
        service = FrontendKeyService(dao)
        rateLimitManager = newRateLimitManager()
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(true))
        port = startServer(service)
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    private fun newRateLimitManager() = RateLimitManager(
        // Never configured: the manager falls back to "no trusted proxies listed" (loopback and private peers stay trusted).
        ConfigManager(vertx, LoggerFactory.getLogger("test"), AnnotationConfigApplicationContext()),
        ProxyDetector()
    )

    private fun <T> Future<T>.blockingGet(): T = toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private fun startServer(keys: FrontendKeyService): Int {
        val router = Router.router(vertx)

        router.route("/api/v1/*").order(0).handler(
            AccessPlaneHandler.create(keys, sqlClient = { sqlClient }, trustedProxies = { emptyList() }, frontendMode = { mode.get() })
        )
        router.route("/api/v1/*").order(0).handler(rateLimitManager.createHandler())
        router.route("/api/v1/*").order(1).handler { context ->
            val access = AccessContext.of(context)

            context.response()
                .putHeader("content-type", "application/json")
                .end(
                    JsonObject()
                        .put("ip", access?.clientIp)
                        .put("keyId", access?.key?.id)
                        .put("fromKey", access?.clientIpFromKey)
                        .encode()
                )
        }

        return vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private fun get(path: String, headers: Map<String, String> = emptyMap()): Response =
        com.panomc.platform.TestHttp.onLoop(vertx) { client.request(HttpMethod.GET, port, "127.0.0.1", path)
            .compose { request ->
                headers.forEach { (name, value) -> request.putHeader(name, value) }

                request.send()
            }
            .compose { response ->
                response.body().map { body ->
                    Response(
                        response.statusCode(),
                        response.headers().associate { it.key.lowercase() to it.value },
                        body.toString()
                    )
                }
            } }.blockingGet()

    private fun createKey(name: String = "site") = runBlocking { service.create(name, 1L, sqlClient) }

    private fun keyed(key: String, vararg more: Pair<String, String>) =
        mapOf(FrontendKeyService.HEADER to key) + more

    @Test
    fun `a valid key's client ip header is the client ip`() {
        val key = createKey()

        val response = get(
            "/api/v1/posts",
            keyed(key.key, "X-Pano-Client-Ip" to "198.51.100.7", "X-Forwarded-For" to "203.0.113.9")
        )

        assertEquals(200, response.status)
        assertEquals("198.51.100.7", response.json.getString("ip"))
        assertEquals(key.id, response.json.getLong("keyId"))
        assertTrue(response.json.getBoolean("fromKey"))
    }

    @Test
    fun `an ipv6 client ip is accepted`() {
        val key = createKey()

        val response = get("/api/v1/posts", keyed(key.key, "X-Pano-Client-Ip" to "2001:DB8::7"))

        assertEquals(200, response.status)
        assertEquals("2001:db8::7", response.json.getString("ip"))
    }

    @Test
    fun `a valid key without the ip header gets the shipped resolver's answer`() {
        val key = createKey()

        val response = get("/api/v1/posts", keyed(key.key, "X-Forwarded-For" to "203.0.113.9"))

        assertEquals(200, response.status)
        assertEquals("203.0.113.9", response.json.getString("ip"))
        assertEquals(false, response.json.getBoolean("fromKey"))
    }

    @Test
    fun `the ip header without a key is ignored`() {
        val response = get(
            "/api/v1/posts",
            mapOf("X-Pano-Client-Ip" to "198.51.100.7", "X-Forwarded-For" to "203.0.113.9")
        )

        assertEquals(200, response.status)
        assertEquals("203.0.113.9", response.json.getString("ip"))
        assertNull(response.json.getValue("keyId"))
    }

    @Test
    fun `a bad client ip is 400 INVALID_CLIENT_IP`() {
        val key = createKey()

        listOf("not-an-ip", "198.51.100.7, 203.0.113.9", "198.51.100.7:4711", "[2001:db8::7]", "999.1.1.1", "evil.example.com", "1:2")
            .forEach { bad ->
                val response = get("/api/v1/posts", keyed(key.key, "X-Pano-Client-Ip" to bad))

                assertEquals(400, response.status, bad)
                assertEquals("INVALID_CLIENT_IP", response.json.getJsonObject("error").getString("code"), bad)
            }
    }

    @Test
    fun `in THEME mode a stored key is 403 FRONTEND_ACCESS_DISABLED, the internal key and a wrong key are unchanged`() {
        val key = createKey()

        mode.set(com.panomc.platform.ui.FrontendMode.THEME)

        val refused = get("/api/v1/posts", keyed(key.key))

        assertEquals(403, refused.status)
        assertEquals("FRONTEND_ACCESS_DISABLED", refused.json.getJsonObject("error").getString("code"))
        assertTrue(refused.json.getJsonObject("error").getString("message").contains("Themes → Site display settings"))

        assertEquals(200, get("/api/v1/posts", keyed(service.internalKey)).status)

        val wrong = get("/api/v1/posts", keyed("pfk_nope"))

        assertEquals(401, wrong.status)
        assertEquals("INVALID_FRONTEND_KEY", wrong.json.getJsonObject("error").getString("code"))
    }

    @Test
    fun `a mode change applies to the next request and the kept key works again`() {
        val key = createKey()

        for (blocked in listOf(true, false, true)) {
            mode.set(if (blocked) com.panomc.platform.ui.FrontendMode.THEME else com.panomc.platform.ui.FrontendMode.EXTERNAL)

            assertEquals(if (blocked) 403 else 200, get("/api/v1/posts", keyed(key.key)).status)
        }

        for (other in listOf(com.panomc.platform.ui.FrontendMode.CUSTOM_APP, com.panomc.platform.ui.FrontendMode.NONE)) {
            mode.set(other)

            assertEquals(200, get("/api/v1/posts", keyed(key.key)).status, other.name)
        }
    }

    @Test
    fun `creating a key is refused only in THEME mode`() {
        val refusal = org.junit.jupiter.api.Assertions.assertThrows(FrontendAccessDisabled::class.java) {
            FrontendAccessDisabled.requireOff(com.panomc.platform.ui.FrontendMode.THEME)
        }

        assertEquals(403, refusal.getStatusCode())

        com.panomc.platform.ui.FrontendMode.entries.filter { it != com.panomc.platform.ui.FrontendMode.THEME }
            .forEach { FrontendAccessDisabled.requireOff(it) }
    }

    @Test
    fun `an unknown key is 401 INVALID_FRONTEND_KEY and never anonymous`() {
        val response = get(
            "/api/v1/posts",
            keyed("pfk_nope", "X-Forwarded-For" to "203.0.113.9", "X-Pano-Client-Ip" to "198.51.100.7")
        )

        assertEquals(401, response.status)
        assertEquals("INVALID_FRONTEND_KEY", response.json.getJsonObject("error").getString("code"))
    }

    @Test
    fun `a revoked key is 401 from the next request on`() {
        val key = createKey()

        assertEquals(200, get("/api/v1/posts", keyed(key.key)).status)

        runBlocking { service.revoke(key.id, sqlClient) }

        assertEquals(401, get("/api/v1/posts", keyed(key.key)).status)
    }

    @Test
    fun `stored keys are loaded on the first request after a restart`() {
        val key = createKey()
        val restarted = FrontendKeyService(dao)
        val restartedPort = startServer(restarted)

        val response = com.panomc.platform.TestHttp.onLoop(vertx) { client.request(HttpMethod.GET, restartedPort, "127.0.0.1", "/api/v1/posts")
            .compose { request ->
                request.putHeader(FrontendKeyService.HEADER, key.key)

                request.send()
            } }.blockingGet()

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `a stored key's use is recorded, the internal key's is not`() {
        val key = createKey()

        get("/api/v1/posts", keyed(service.internalKey))
        get("/api/v1/posts", keyed(key.key))

        val deadline = System.currentTimeMillis() + 3000

        while (dao.lastUsedUpdates.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        assertEquals(listOf(key.id), dao.lastUsedUpdates.map { it.first })
    }

    @Test
    fun `buckets of keys and visitors are separate`() {
        val key = createKey("one")
        val other = createKey("two")
        val ip = "198.51.100.7"

        // A visitor behind a proxy exhausts the login tier (10 burst).
        val visitor = mapOf("X-Forwarded-For" to ip)

        repeat(10) { assertEquals(200, get("/api/v1/auth/login", visitor).status) }

        val limited = get("/api/v1/auth/login", visitor)

        assertEquals(429, limited.status)
        assertEquals("RATE_LIMIT_EXCEEDED", limited.json.getJsonObject("error").getString("code"))
        assertTrue(limited.headers.containsKey("retry-after"))

        // The same visitor address through a key has its own bucket, a fresh one per key.
        val viaKey = get("/api/v1/auth/login", keyed(key.key, "X-Pano-Client-Ip" to ip))

        assertEquals(200, viaKey.status)
        assertEquals("10", viaKey.headers["x-ratelimit-limit"])
        assertEquals("9", viaKey.headers["x-ratelimit-remaining"])

        val viaOther = get("/api/v1/auth/login", keyed(other.key, "X-Pano-Client-Ip" to ip))

        assertEquals(200, viaOther.status)
        assertEquals("9", viaOther.headers["x-ratelimit-remaining"])

        // And the keyed traffic does not drain the visitor's bucket either.
        repeat(10) { assertEquals(200, get("/api/v1/auth/login", keyed(key.key, "X-Pano-Client-Ip" to "198.51.100.8")).status) }
        assertEquals(429, get("/api/v1/auth/login", keyed(key.key, "X-Pano-Client-Ip" to "198.51.100.8")).status)
        assertEquals(429, get("/api/v1/auth/login", visitor).status)
    }

    @Test
    fun `a key without a client ip counts in the FRONTEND_KEY tier under its own id`() {
        val key = createKey("one")
        val other = createKey("two")

        val first = get("/api/v1/auth/login", keyed(key.key, "X-Forwarded-For" to "203.0.113.9"))

        assertEquals(200, first.status)
        assertEquals("2000", first.headers["x-ratelimit-limit"])
        assertEquals("1999", first.headers["x-ratelimit-remaining"])

        // Many requests of a key do not hit the 10-per-address login tier.
        repeat(40) { assertEquals(200, get("/api/v1/auth/login", keyed(key.key)).status) }

        assertEquals("1999", get("/api/v1/auth/login", keyed(other.key)).headers["x-ratelimit-remaining"])
    }

    @Test
    fun `the internal key from a loopback peer is not limited`() {
        repeat(60) {
            val response = get(
                "/api/v1/auth/login",
                keyed(service.internalKey, "X-Pano-Client-Ip" to "198.51.100.7", "X-Forwarded-For" to "198.51.100.7")
            )

            assertEquals(200, response.status)
            assertNull(response.headers["x-ratelimit-limit"])
        }
    }

    @Test
    fun `credentials csrf and ws-ticket are public tier, thirty reads stay 200`() {
        val visitor = mapOf("X-Forwarded-For" to "198.51.100.9")

        listOf("/api/v1/auth/credentials", "/api/v1/auth/csrf", "/api/v1/auth/ws-ticket").forEach { path ->
            repeat(30) { assertEquals(200, get(path, visitor).status, path) }
        }

        assertEquals("500", get("/api/v1/auth/credentials", visitor).headers["x-ratelimit-limit"])
    }

    @Test
    fun `tier by intent`() {
        assertEquals(RateLimitManager.Tier.PUBLIC_API, RateLimitManager.tierForPath("/api/v1/auth/credentials"))
        assertEquals(RateLimitManager.Tier.PUBLIC_API, RateLimitManager.tierForPath("/api/v1/auth/csrf"))
        assertEquals(RateLimitManager.Tier.PUBLIC_API, RateLimitManager.tierForPath("/api/v1/auth/ws-ticket"))
        assertEquals(RateLimitManager.Tier.AUTH, RateLimitManager.tierForPath("/api/v1/auth/login"))
        assertEquals(RateLimitManager.Tier.AUTH, RateLimitManager.tierForPath("/api/v1/auth/register"))
        assertEquals(RateLimitManager.Tier.AUTH, RateLimitManager.tierForPath("/api/v1/auth/credentials/extra"))
    }

    @Test
    fun `a loopback visitor without forwarding headers is still not limited`() {
        repeat(30) { assertEquals(200, get("/api/v1/auth/login").status) }
    }

    @Test
    fun `parseIpLiteral accepts exactly one address`() {
        assertEquals("198.51.100.7", AccessPlaneHandler.parseIpLiteral(" 198.51.100.7 "))
        assertEquals("198.51.100.7", AccessPlaneHandler.parseIpLiteral("198.051.100.7"))
        assertEquals("::1", AccessPlaneHandler.parseIpLiteral("::1"))
        assertEquals("1.2.3.4", AccessPlaneHandler.parseIpLiteral("::ffff:1.2.3.4"))
        assertNull(AccessPlaneHandler.parseIpLiteral(""))
        assertNull(AccessPlaneHandler.parseIpLiteral("1.2.3"))
        assertNull(AccessPlaneHandler.parseIpLiteral("1.2.3.256"))
        assertNull(AccessPlaneHandler.parseIpLiteral("fe80::1%eth0"))
        assertNull(AccessPlaneHandler.parseIpLiteral("localhost"))
        assertNull(AccessPlaneHandler.parseIpLiteral("a:b"))
    }
}
