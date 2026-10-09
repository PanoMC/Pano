package com.panomc.platform.access

import com.panomc.platform.access.OriginPolicy.Companion.classify
import com.panomc.platform.db.dao.FrontendKeyDao
import com.panomc.platform.db.model.FrontendKey
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.util.RegistrableDomain
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/**
 * Allowed origins (open front-end plan, doc 05 §5, slice 7): the public-suffix lookup, the origin
 * validation, the pure `classify` rule, and the credentialed CORS / preflight answers of the access
 * handler on a real Vert.x router.
 */
class OriginPolicyTest {
    private val sqlClient: SqlClient = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SqlClient::class.java)
    ) { _, method, _ -> error("the origin policy must not use the SqlClient itself: ${method.name}") } as SqlClient

    // ---------------------------------------------------------------- RegistrableDomain

    @Test
    fun `registrable domain of an ordinary host`() {
        assertEquals("example.com", RegistrableDomain.of("example.com"))
        assertEquals("example.com", RegistrableDomain.of("play.example.com"))
        assertEquals("example.com", RegistrableDomain.of("A.B.Example.COM."))
    }

    @Test
    fun `registrable domain honours multi label suffixes`() {
        assertEquals("good.co.uk", RegistrableDomain.of("www.good.co.uk"))
        assertEquals("evil.co.uk", RegistrableDomain.of("evil.co.uk"))
        assertFalse(RegistrableDomain.sameSite("good.co.uk", "evil.co.uk"))
        assertNull(RegistrableDomain.of("co.uk"))
        assertNull(RegistrableDomain.of("com"))
    }

    @Test
    fun `registrable domain honours private suffixes wildcards and exceptions`() {
        // github.io is a private suffix: two user sites are two sites.
        assertEquals("alice.github.io", RegistrableDomain.of("www.alice.github.io"))
        assertFalse(RegistrableDomain.sameSite("alice.github.io", "bob.github.io"))
        // "*.ck" makes every second level label a suffix, "!www.ck" is the exception.
        assertEquals("a.b.ck", RegistrableDomain.of("a.b.ck"))
        assertNull(RegistrableDomain.of("b.ck"))
        assertEquals("www.ck", RegistrableDomain.of("www.ck"))
        assertEquals("www.ck", RegistrableDomain.of("sub.www.ck"))
    }

    @Test
    fun `registrable domain of an unlisted tld is the last two labels`() {
        assertEquals("pano.test", RegistrableDomain.of("play.pano.test"))
        assertTrue(RegistrableDomain.sameSite("play.pano.test", "api.pano.test"))
    }

    @Test
    fun `registrable domain is null for ips localhost and a bare label and then hosts must be equal`() {
        assertNull(RegistrableDomain.of("127.0.0.1"))
        assertNull(RegistrableDomain.of("203.0.113.9"))
        assertNull(RegistrableDomain.of("[::1]"))
        assertNull(RegistrableDomain.of("2001:db8::7"))
        assertNull(RegistrableDomain.of("localhost"))
        assertNull(RegistrableDomain.of("intranet"))
        assertNull(RegistrableDomain.of(null))

        assertTrue(RegistrableDomain.sameSite("127.0.0.1", "127.0.0.1"))
        assertFalse(RegistrableDomain.sameSite("127.0.0.1", "127.0.0.2"))
        assertTrue(RegistrableDomain.sameSite("localhost", "LOCALHOST"))
        assertFalse(RegistrableDomain.sameSite("localhost", "example.com"))
        assertFalse(RegistrableDomain.sameSite("localhost", "app.localhost"))
        assertFalse(RegistrableDomain.sameSite(null, "example.com"))
    }

    // ---------------------------------------------------------------- classify

    private fun classifyOf(
        origin: String?,
        host: String? = "api.example.com",
        forwardedHost: String? = null,
        trustedPeer: Boolean = false,
        siteHosts: List<String> = listOf("api.example.com"),
        allowed: List<String> = emptyList(),
        dev: Boolean = false
    ) = classify(origin, host, forwardedHost, trustedPeer, siteHosts, allowed, dev)

    @Test
    fun `no origin header is NONE`() {
        assertEquals(OriginClass.NONE, classifyOf(null))
    }

    @Test
    fun `the same host is SAME whatever the scheme port or path`() {
        assertEquals(OriginClass.SAME, classifyOf("https://api.example.com"))
        assertEquals(OriginClass.SAME, classifyOf("http://api.example.com:8088"))
        assertEquals(OriginClass.SAME, classifyOf("https://API.example.com/some/path?x=1"))
        assertEquals(OriginClass.SAME, classifyOf("https://api.example.com", host = "api.example.com:8088"))
    }

    @Test
    fun `an alias host behind a tls terminating proxy is SAME through the forwarded host of a trusted peer`() {
        // The proxy talks plain http to Pano under an internal name and forwards the public host.
        val behindProxy = classifyOf(
            "https://www.example.com",
            host = "pano:8088",
            forwardedHost = "www.example.com",
            trustedPeer = true,
            siteHosts = emptyList()
        )

        assertEquals(OriginClass.SAME, behindProxy)

        // The same header from a peer that is not trusted proves nothing.
        assertEquals(
            OriginClass.FOREIGN,
            classifyOf(
                "https://www.example.com",
                host = "pano:8088",
                forwardedHost = "www.example.com",
                trustedPeer = false,
                siteHosts = emptyList()
            )
        )

        // A forwarded list: the first value is the host the client asked for.
        assertEquals(
            OriginClass.SAME,
            classifyOf(
                "https://www.example.com", host = "pano:8088", forwardedHost = "www.example.com, edge.internal",
                trustedPeer = true, siteHosts = emptyList()
            )
        )
    }

    @Test
    fun `the website url and front-end site url hosts are SAME`() {
        assertEquals(
            OriginClass.SAME,
            classifyOf("https://example.com", host = "pano:8088", siteHosts = listOf("api.example.com", "example.com"))
        )
        assertEquals(
            OriginClass.FOREIGN,
            classifyOf("https://example.org", host = "pano:8088", siteHosts = listOf("api.example.com", "example.com"))
        )
    }

    @Test
    fun `an allowed entry matches scheme host and port exactly`() {
        val allowed = listOf("https://play.example.com", "http://localhost:5173")

        assertEquals(OriginClass.ALLOWED, classifyOf("https://play.example.com", allowed = allowed))
        assertEquals(OriginClass.ALLOWED, classifyOf("https://play.example.com:443", allowed = allowed))
        assertEquals(OriginClass.ALLOWED, classifyOf("https://PLAY.example.com/x", allowed = allowed))
        assertEquals(OriginClass.ALLOWED, classifyOf("http://localhost:5173", allowed = allowed))

        assertEquals(OriginClass.FOREIGN, classifyOf("http://play.example.com", allowed = allowed))
        assertEquals(OriginClass.FOREIGN, classifyOf("https://play.example.com:8443", allowed = allowed))
        assertEquals(OriginClass.FOREIGN, classifyOf("https://evil.example.com", allowed = allowed))
        assertEquals(OriginClass.FOREIGN, classifyOf("http://localhost:3000", allowed = allowed))
    }

    @Test
    fun `null and unparseable origins are FOREIGN`() {
        val allowed = listOf("https://play.example.com")

        assertEquals(OriginClass.FOREIGN, classifyOf("null", allowed = allowed))
        assertEquals(OriginClass.FOREIGN, classifyOf("", allowed = allowed))
        assertEquals(OriginClass.FOREIGN, classifyOf("not a url", allowed = allowed))
        assertEquals(OriginClass.FOREIGN, classifyOf("file:///etc/passwd", allowed = allowed))
        assertEquals(OriginClass.FOREIGN, classifyOf("chrome-extension://abcdef", allowed = allowed))
    }

    @Test
    fun `any localhost origin is ALLOWED under dev only`() {
        assertEquals(OriginClass.ALLOWED, classifyOf("http://localhost:3000", dev = true))
        assertEquals(OriginClass.ALLOWED, classifyOf("http://127.0.0.1:5173", dev = true))
        assertEquals(OriginClass.FOREIGN, classifyOf("http://localhost:3000", dev = false))
        assertEquals(OriginClass.FOREIGN, classifyOf("http://localhost.evil.com", dev = true))
    }

    // ---------------------------------------------------------------- validation

    private fun valid(raw: String, siteHost: String? = "api.example.com") = OriginPolicy.validate(raw, siteHost)

    @Test
    fun `a valid origin is normalized`() {
        assertEquals("https://play.example.com", valid("https://play.example.com"))
        assertEquals("https://play.example.com", valid("  https://PLAY.example.com:443/ "))
        assertEquals("https://play.example.com:8443", valid("https://play.example.com:8443"))
        assertEquals("https://example.com", valid("https://example.com"))
    }

    @Test
    fun `http is only valid for localhost and ips`() {
        assertEquals("http://localhost:5173", valid("http://localhost:5173", siteHost = "localhost"))
        assertEquals("http://127.0.0.1:3000", valid("http://127.0.0.1:3000", siteHost = "127.0.0.1"))
        assertThrows(InvalidFields::class.java) { valid("http://play.example.com") }
    }

    @Test
    fun `a path a wildcard or garbage is refused`() {
        listOf(
            "https://play.example.com/app",
            "https://play.example.com/?x=1",
            "https://play.example.com#top",
            "https://*.example.com",
            "*",
            "https://user@play.example.com",
            "play.example.com",
            "ftp://play.example.com",
            "https://",
            ""
        ).forEach { raw ->
            assertThrows(InvalidFields::class.java, { valid(raw) }, "'$raw' must be refused")
        }
    }

    @Test
    fun `another registrable domain is ORIGIN_DIFFERENT_SITE`() {
        val error = assertThrows(OriginDifferentSite::class.java) { valid("https://evil.com") }

        assertEquals("ORIGIN_DIFFERENT_SITE", error.code)
        assertEquals(400, error.getStatusCode())

        assertThrows(OriginDifferentSite::class.java) { valid("https://evil.co.uk", siteHost = "good.co.uk") }
        assertThrows(OriginDifferentSite::class.java) { valid("https://bob.github.io", siteHost = "alice.github.io") }
        // No site host at all (no website-url, no request host): nothing to compare with.
        assertThrows(OriginDifferentSite::class.java) { valid("https://play.example.com", siteHost = null) }
    }

    @Test
    fun `localhost and ips need the same host as the site`() {
        assertThrows(OriginDifferentSite::class.java) { valid("http://localhost:5173", siteHost = "api.example.com") }
        assertThrows(OriginDifferentSite::class.java) { valid("http://127.0.0.1:3000", siteHost = "localhost") }
        assertThrows(OriginDifferentSite::class.java) { valid("https://api.example.com", siteHost = "127.0.0.1") }
    }

    // ---------------------------------------------------------------- storage

    private class Store(var value: String? = null) {
        var writes = 0
    }

    private fun policy(
        store: Store,
        website: String = "https://api.example.com",
        site: String = "",
        trusted: List<String> = emptyList(),
        dev: Boolean = false
    ) = OriginPolicy(
        read = { store.value },
        write = { _, json ->
            store.value = json
            store.writes++
        },
        websiteUrl = { website },
        siteUrl = { site },
        trustedProxies = { trusted },
        devMode = { dev }
    )

    @Test
    fun `replace validates saves and caches the list`() = runBlocking {
        val store = Store()
        val policy = policy(store)

        assertEquals(emptyList<String>(), policy.list(sqlClient))

        val saved = policy.replace(
            listOf("https://play.example.com", "https://PLAY.example.com:443", "https://shop.example.com"),
            fallbackHost = null,
            sqlClient = sqlClient
        )

        assertEquals(listOf("https://play.example.com", "https://shop.example.com"), saved)
        assertEquals("""["https://play.example.com","https://shop.example.com"]""", store.value)
        assertEquals(saved, policy.list(sqlClient))

        // A second policy (a restart) reads the same list.
        assertEquals(saved, policy(store).list(sqlClient))
    }

    @Test
    fun `a refused entry saves nothing`() = runBlocking {
        val store = Store("""["https://play.example.com"]""")
        val policy = policy(store)

        assertThrows(OriginDifferentSite::class.java) {
            runBlocking { policy.replace(listOf("https://ok.example.com", "https://evil.com"), null, sqlClient) }
        }

        assertEquals(0, store.writes)
        assertEquals(listOf("https://play.example.com"), policy.list(sqlClient))
    }

    @Test
    fun `at most 20 origins`() {
        val store = Store()
        val policy = policy(store)

        val twenty = (1..20).map { "https://app$it.example.com" }

        assertEquals(20, runBlocking { policy.replace(twenty, null, sqlClient) }.size)

        val error = assertThrows(OriginLimitReached::class.java) {
            runBlocking { policy.replace(twenty + "https://app21.example.com", null, sqlClient) }
        }

        assertEquals("ORIGIN_LIMIT_REACHED", error.code)
        assertEquals(20, runBlocking { policy.list(sqlClient) }.size)
    }

    @Test
    fun `the website url host is empty so the request host is the site`() = runBlocking {
        val policy = policy(Store(), website = "")

        assertEquals(
            listOf("https://play.example.com"),
            policy.replace(listOf("https://play.example.com"), "api.example.com", sqlClient)
        )
    }

    @Test
    fun `a broken stored value is an empty list`() = runBlocking {
        assertEquals(emptyList<String>(), policy(Store("not json")).list(sqlClient))
        assertEquals(emptyList<String>(), policy(Store("""{"a":1}""")).list(sqlClient))
        assertEquals(
            listOf("https://a.example.com"),
            policy(Store("""["https://a.example.com", 5, "javascript:alert(1)", "*"]""")).list(sqlClient)
        )
    }

    // ---------------------------------------------------------------- CORS and preflight over HTTP

    private class MemoryDao : FrontendKeyDao() {
        val rows = linkedMapOf<Long, FrontendKey>()
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

        override suspend fun updateLastUsedAt(id: Long, lastUsedAt: Long, sqlClient: SqlClient) {}
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: String) {
        val json: JsonObject get() = JsonObject(body)
    }

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private lateinit var keys: FrontendKeyService
    private var port = 0

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(true))
        keys = FrontendKeyService(MemoryDao())
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    private fun <T> Future<T>.blockingGet(): T = toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    /** A router with the access handler in front of an endpoint that echoes the origin class. */
    private fun startServer(policy: OriginPolicy) {
        val router = Router.router(vertx)

        listOf("/api/v1/*", "/api/plugins/*").forEach { mount ->
            router.route(mount).order(0).handler(
                AccessPlaneHandler.create(keys, sqlClient = { sqlClient }, trustedProxies = { emptyList() }, origins = policy)
            )
            router.route(mount).order(1).handler { context ->
                context.response()
                    .putHeader("content-type", "application/json")
                    .end(JsonObject().put("origin", AccessContext.of(context)?.origin?.name).encode())
            }
        }

        port = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private fun request(method: HttpMethod, path: String, headers: Map<String, String> = emptyMap()): Response =
        client.request(method, port, "127.0.0.1", path)
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
            }
            .blockingGet()

    private fun preflight(origin: String, path: String = "/api/plugins/pano-plugin-market/cart/items", method: String = "POST") =
        request(
            HttpMethod.OPTIONS, path,
            mapOf(
                "Origin" to origin,
                "Access-Control-Request-Method" to method,
                "Access-Control-Request-Headers" to "content-type, x-csrf-token"
            )
        )

    private fun listed(vararg origins: String) = policy(Store(io.vertx.core.json.JsonArray(origins.toList()).encode()))

    @Test
    fun `preflight from an allowed origin is 204 with the credentialed headers`() {
        startServer(listed("https://play.example.com"))

        val response = preflight("https://play.example.com")

        assertEquals(204, response.status)
        assertEquals("https://play.example.com", response.headers["access-control-allow-origin"])
        assertEquals("true", response.headers["access-control-allow-credentials"])
        assertEquals("GET,POST,PUT,PATCH,DELETE", response.headers["access-control-allow-methods"])
        assertEquals("content-type, accept, x-csrf-token, x-requested-with", response.headers["access-control-allow-headers"])
        assertEquals("600", response.headers["access-control-max-age"])
        assertEquals("Origin", response.headers["vary"])
        assertEquals("X-RateLimit-Limit, X-RateLimit-Remaining, Retry-After", response.headers["access-control-expose-headers"])
    }

    @Test
    fun `an allowed origin on the site's own IP host is SAME, so an IP instance cannot reach ALLOWED`() {
        val own = "http://127.0.0.1:18404"

        assertEquals(
            OriginClass.SAME,
            classify(own, "localhost:18400", null, false, listOf("127.0.0.1"), listOf(own), false)
        )
    }

    @Test
    fun `preflight is answered on any api path`() {
        startServer(listed("https://play.example.com"))

        listOf("/api/v1/auth/login", "/api/v1/posts/12", "/api/plugins/x/y/z", "/api/plugins/x/panel/y", "/api/v1/no/such/endpoint").forEach {
            assertEquals(204, preflight("https://play.example.com", it, "DELETE").status, it)
        }
    }

    @Test
    fun `preflight from a foreign origin or with a null origin is 403 ORIGIN_NOT_ALLOWED`() {
        startServer(listed("https://play.example.com"))

        listOf("https://evil.com", "null", "http://play.example.com", "https://play.example.com:8443").forEach { origin ->
            val response = preflight(origin)

            assertEquals(403, response.status, origin)
            assertEquals("ORIGIN_NOT_ALLOWED", response.json.getJsonObject("error").getString("code"), origin)
            assertNull(response.headers["access-control-allow-origin"], origin)
            assertNull(response.headers["access-control-allow-credentials"], origin)
        }
    }

    @Test
    fun `preflight from the same site needs no cors and is refused`() {
        // Same host: the browser sends no preflight in practice; if it does, nothing is granted.
        startServer(listed())

        val response = preflight("http://127.0.0.1:${port}")

        assertEquals(403, response.status)
    }

    @Test
    fun `an options request without access-control-request-method is not a preflight`() {
        startServer(listed("https://play.example.com"))

        val response = request(HttpMethod.OPTIONS, "/api/v1/posts", mapOf("Origin" to "https://play.example.com"))

        assertEquals(200, response.status)
        assertEquals("https://play.example.com", response.headers["access-control-allow-origin"])
        assertNull(response.headers["access-control-max-age"])
    }

    @Test
    fun `a keyed preflight is refused`() {
        startServer(listed("https://play.example.com"))

        val key = runBlocking { keys.create("site", 1L, sqlClient) }

        val response = request(
            HttpMethod.OPTIONS, "/api/v1/posts",
            mapOf(
                "Origin" to "https://play.example.com",
                "Access-Control-Request-Method" to "POST",
                FrontendKeyService.HEADER to key.key
            )
        )

        assertEquals(403, response.status)
        assertNull(response.headers["access-control-allow-origin"])
    }

    @Test
    fun `a request from an allowed origin gets the credentialed headers and never a star`() {
        startServer(listed("https://play.example.com"))

        val response = request(HttpMethod.GET, "/api/v1/posts", mapOf("Origin" to "https://play.example.com"))

        assertEquals(200, response.status)
        assertEquals("ALLOWED", response.json.getString("origin"))
        assertEquals("https://play.example.com", response.headers["access-control-allow-origin"])
        assertEquals("true", response.headers["access-control-allow-credentials"])
        assertEquals("Origin", response.headers["vary"])
        assertEquals("X-RateLimit-Limit, X-RateLimit-Remaining, Retry-After", response.headers["access-control-expose-headers"])
    }

    @Test
    fun `a foreign origin gets no cors headers but its class is stored`() {
        startServer(listed("https://play.example.com"))

        val response = request(HttpMethod.GET, "/api/v1/posts", mapOf("Origin" to "https://evil.com"))

        assertEquals(200, response.status)
        assertEquals("FOREIGN", response.json.getString("origin"))
        assertNull(response.headers["access-control-allow-origin"])
        assertNull(response.headers["access-control-allow-credentials"])
        assertEquals("Origin", response.headers["vary"])
    }

    @Test
    fun `a request without an origin is NONE and has no cors headers`() {
        startServer(listed("https://play.example.com"))

        val response = request(HttpMethod.GET, "/api/v1/posts")

        assertEquals("NONE", response.json.getString("origin"))
        assertNull(response.headers["access-control-allow-origin"])
        assertNull(response.headers["vary"])
    }

    @Test
    fun `the origin of the host the request came to is SAME and gets no cors headers`() {
        startServer(listed("https://play.example.com"))

        val response = request(HttpMethod.GET, "/api/v1/posts", mapOf("Origin" to "https://127.0.0.1:9999"))

        assertEquals("SAME", response.json.getString("origin"))
        assertNull(response.headers["access-control-allow-origin"])
    }

    @Test
    fun `a keyed request gets no cors headers even from an allowed origin`() {
        startServer(listed("https://play.example.com"))

        val key = runBlocking { keys.create("site", 1L, sqlClient) }

        val response = request(
            HttpMethod.GET, "/api/v1/posts",
            mapOf("Origin" to "https://play.example.com", FrontendKeyService.HEADER to key.key)
        )

        assertEquals(200, response.status)
        // The class is still computed (the Origin gate of the next unit skips keyed requests itself).
        assertEquals("ALLOWED", response.json.getString("origin"))
        assertNull(response.headers["access-control-allow-origin"])
        assertNull(response.headers["access-control-allow-credentials"])
    }

    @Test
    fun `the list is read on the first request that carries an origin`() {
        val store = Store("""["https://play.example.com"]""")
        val policy = policy(store)

        startServer(policy)

        assertFalse(policy.isLoaded)

        // No origin: nothing is read.
        request(HttpMethod.GET, "/api/v1/posts")

        assertFalse(policy.isLoaded)

        val response = request(HttpMethod.GET, "/api/v1/posts", mapOf("Origin" to "https://play.example.com"))

        assertTrue(policy.isLoaded)
        assertEquals("ALLOWED", response.json.getString("origin"))
    }

    @Test
    fun `a saved list applies to the next request at once`() {
        val policy = policy(Store())

        startServer(policy)

        assertEquals(
            "FOREIGN",
            request(HttpMethod.GET, "/api/v1/posts", mapOf("Origin" to "https://play.example.com")).json.getString("origin")
        )

        runBlocking { policy.replace(listOf("https://play.example.com"), null, sqlClient) }

        assertEquals(
            "ALLOWED",
            request(HttpMethod.GET, "/api/v1/posts", mapOf("Origin" to "https://play.example.com")).json.getString("origin")
        )
    }
}
