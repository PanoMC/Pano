package com.panomc.platform.access

import com.panomc.platform.AppConstants
import com.panomc.platform.Main
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.CredentialSource
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.FrontendKeyDao
import com.panomc.platform.db.dao.TokenDao
import com.panomc.platform.db.model.FrontendKey
import com.panomc.platform.db.model.Token
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.model.Api
import com.panomc.platform.model.BrowserAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.route.RouterProvider
import com.panomc.platform.route.api.auth.CreateWsTicketAPI
import com.panomc.platform.route.api.auth.GetCsrfTokenAPI
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.objenesis.ObjenesisStd
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CSRF on every mutation, the Origin gate, `GET /auth/csrf` and the WebSocket ticket (open front-end plan,
 * doc 05 §4 and §6, slices 8 and 11).
 *
 * A real Vert.x router, the real access plane handler, the real [Api] wrapper (`getHandler` /
 * `authorizedBodyHandler`) and a real [AuthProvider] over an in-memory token table. The stand-in endpoints
 * only replace what needs a database: `LoggedInApi.onBeforeHandle` becomes "is there a live session", the rest
 * (the CSRF check, `GetCsrfTokenAPI.handle`, `CreateWsTicketAPI.handle`, `WsAuth`) is the shipped code.
 */
class CsrfEverywhereTest {
    private class MemoryKeyDao : FrontendKeyDao() {
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

    private class MemoryTokenDao : TokenDao() {
        val rows = CopyOnWriteArrayList<Token>()

        override suspend fun init(sqlClient: SqlClient) {}

        override suspend fun add(token: Token, sqlClient: SqlClient): Long {
            rows += token

            return rows.size.toLong()
        }

        override suspend fun existsByTokenAndType(token: String, tokenType: TokenType, sqlClient: SqlClient) =
            rows.any { it.token == token && it.type.getName() == tokenType.getName() }

        override suspend fun deleteByToken(token: String, sqlClient: SqlClient) {
            rows.removeIf { it.token == token }
        }

        override suspend fun deleteBySubjectAndType(subject: String, type: TokenType, sqlClient: SqlClient) {
            rows.removeIf { it.subject == subject && it.type.getName() == type.getName() }
        }

        override suspend fun deleteBySubject(subject: String, sqlClient: SqlClient) {
            rows.removeIf { it.subject == subject }
        }

        override suspend fun getLastBySubjectAndType(subject: String, type: TokenType, sqlClient: SqlClient) =
            rows.lastOrNull { it.subject == subject && it.type.getName() == type.getName() }

        override suspend fun getAllBySubjectAndType(subject: String, type: TokenType, sqlClient: SqlClient) =
            rows.filter { it.subject == subject && it.type.getName() == type.getName() }

        override suspend fun deleteById(id: Long, sqlClient: SqlClient) {}

        override suspend fun getById(id: Long, sqlClient: SqlClient): Token? = null
    }

    private class Response(val status: Int, val headers: List<Pair<String, String>>, val body: String) {
        val json: JsonObject get() = JsonObject(body)

        val errorCode: String? get() = json.getJsonObject("error")?.getString("code")

        fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        fun setCookies() = headers.filter { it.first.equals("set-cookie", ignoreCase = true) }.map { it.second }
    }

    /** A logged-in browser: the `Cookie` header and the CSRF token it would send. */
    private class Browser(val cookie: String, val csrf: String)

    private val sqlClient: SqlClient = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SqlClient::class.java)
    ) { _, method, _ -> error("not expected: ${method.name}") } as SqlClient

    private val bodyRead = AtomicBoolean()
    private val clock = AtomicLong(1_000_000L)

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private lateinit var tokenDao: MemoryTokenDao
    private lateinit var keys: FrontendKeyService
    private lateinit var tokenProvider: TokenProvider
    private lateinit var authProvider: AuthProvider
    private lateinit var origins: OriginPolicy
    private lateinit var tickets: WsTicketStore
    private var previousContext: AnnotationConfigApplicationContext? = null
    private var port = 0

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(true))
        tokenDao = MemoryTokenDao()
        keys = FrontendKeyService(MemoryKeyDao())
        tickets = WsTicketStore({ clock.get() }, WsTicketStore.TTL_SECONDS * 1000L)

        // The site is pano.test; https://play.pano.test is on its allowed list; https://evil.example is neither.
        origins = OriginPolicy(
            read = { """["https://play.pano.test"]""" },
            write = { _, _ -> },
            websiteUrl = { "https://pano.test" }
        )

        val objenesis = ObjenesisStd()
        val databaseManager = objenesis.newInstance(DatabaseManager::class.java)

        setField(databaseManager, "tokenDao", tokenDao)
        setField(databaseManager, "sqlClient", sqlClient)

        val configManager = ConfigManager(vertx, LoggerFactory.getLogger("test"), AnnotationConfigApplicationContext())
        val config = objenesis.newInstance(PanoConfig::class.java)

        setField(config, "jwtKey", Base64.getEncoder().encodeToString("csrf-everywhere-test-secret".toByteArray()))
        setField(config, "websiteUrl", "")
        setField(configManager, "config", config)

        tokenProvider = TokenProvider(databaseManager, configManager)

        val permissionManager = objenesis.newInstance(PermissionManager::class.java)
        val context = AnnotationConfigApplicationContext().also { it.refresh() }

        authProvider = AuthProvider(databaseManager, tokenProvider, permissionManager, configManager, context)

        previousContext = runCatching { Main.applicationContext }.getOrNull()

        // Api.checkCsrf reads the AuthProvider bean and the failure handler logs through the Logger bean.
        Main.applicationContext = AnnotationConfigApplicationContext().apply {
            registerBean(Logger::class.java, java.util.function.Supplier { LoggerFactory.getLogger("CsrfEverywhereTest") })
            registerBean(AuthProvider::class.java, java.util.function.Supplier { authProvider })
            refresh()
        }

        port = startServer()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

        previousContext?.let { Main.applicationContext = it }
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun <T> Future<T>.blockingGet(): T = com.panomc.platform.HangDiagnostics.await(this, 15L)

    // ---- stand-in endpoints --------------------------------------------------------------------------------

    private abstract inner class StandIn(vararg methods: RouteType, path: String) : Api() {
        override val paths = methods.map { Path(path, it) }

        override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

        override fun bodyHandler(): Handler<RoutingContext> = BodyHandler.create()

        /** What `LoggedInApi.onBeforeHandle` boils down to without a database. */
        protected suspend fun requireSession(context: RoutingContext) {
            if (!authProvider.isLoggedIn(context)) throw NotLoggedIn()
        }
    }

    /** `LoginAPI`: an `Api`, needs no session, creates one. */
    private inner class LoginLike : StandIn(RouteType.POST, path = "/auth/login") {
        override suspend fun onBeforeHandle(context: RoutingContext) = Unit

        override suspend fun handle(context: RoutingContext): Result =
            Successful(authProvider.issueSessionFor(USER_ID, context, sqlClient))
    }

    /** A `LoggedInApi` mutation, such as creating a ticket or filling a cart. */
    private inner class CartLike : StandIn(RouteType.POST, RouteType.PUT, RouteType.DELETE, path = "/cart/items") {
        override suspend fun onBeforeHandle(context: RoutingContext) = requireSession(context)

        override suspend fun handle(context: RoutingContext): Result =
            Successful(mapOf("userId" to authProvider.getUserIdFromRoutingContext(context)))
    }

    /** A logged-in `GET`. */
    private inner class ReadLike : StandIn(RouteType.GET, path = "/cart") {
        override suspend fun onBeforeHandle(context: RoutingContext) = requireSession(context)

        override suspend fun handle(context: RoutingContext): Result = Successful(mapOf("ok" to true))
    }

    /** The maintenance form: `csrfExempt`. */
    private inner class FormLike : StandIn(RouteType.POST, path = "/maintenance/skip") {
        override val csrfExempt = true

        override suspend fun onBeforeHandle(context: RoutingContext) = requireSession(context)

        override suspend fun handle(context: RoutingContext): Result = Successful()
    }

    /** `SSR visitorVisit`: an `Api` with no login at all. */
    private inner class VisitorLike : StandIn(RouteType.POST, path = "/visitor-visit") {
        override suspend fun onBeforeHandle(context: RoutingContext) = Unit

        override suspend fun handle(context: RoutingContext): Result = Successful()
    }

    /** An upload route built on `authorizedBodyHandler`. */
    private inner class UploadLike : StandIn(RouteType.POST, path = "/upload") {
        override fun bodyHandler(): Handler<RoutingContext> {
            val body = BodyHandler.create()

            return authorizedBodyHandler(Handler { bodyRead.set(true); body.handle(it) })
        }

        override suspend fun onBeforeHandle(context: RoutingContext) = requireSession(context)

        override suspend fun handle(context: RoutingContext): Result = Successful()
    }

    /** A third-party post: a payment return that declares `ANY_ORIGIN`. */
    private inner class PaymentReturnLike : StandIn(RouteType.POST, path = "/plugins/pano-plugin-market/payment/:id/notify") {
        override val browserAccess = BrowserAccess.ANY_ORIGIN

        override suspend fun onBeforeHandle(context: RoutingContext) = Unit

        override suspend fun handle(context: RoutingContext): Result = Successful()
    }

    /** `GET /auth/csrf` with the shipped `handle`. */
    private inner class CsrfRoute : StandIn(RouteType.GET, path = "/auth/csrf") {
        private val real = GetCsrfTokenAPI(authProvider)

        override suspend fun onBeforeHandle(context: RoutingContext) = requireSession(context)

        override suspend fun handle(context: RoutingContext): Result = real.handle(context)
    }

    /** `POST /auth/ws-ticket` with the shipped `handle`. */
    private inner class TicketRoute : StandIn(RouteType.POST, path = "/auth/ws-ticket") {
        private val real = CreateWsTicketAPI(authProvider, tickets)

        override suspend fun onBeforeHandle(context: RoutingContext) = requireSession(context)

        override suspend fun handle(context: RoutingContext): Result = real.handle(context)
    }

    /** `WebsiteWebSocketAPI`'s credential resolution (`WsAuth.resolveUser`) with a session check in place of the DB. */
    private inner class WebsiteWs : StandIn(RouteType.GET, path = "/ws") {
        override suspend fun onBeforeHandle(context: RoutingContext) {
            val userId = WsAuth.resolveUser(
                ticket = context.request().getParam("ticket"),
                source = authProvider.credentialSource(context),
                origin = AccessContext.of(context)?.origin ?: OriginClass.NONE,
                originHeader = context.request().getHeader("Origin"),
                store = tickets,
                beforeTicket = {},
                session = {
                    requireSession(context)

                    authProvider.getUserIdFromRoutingContext(context)
                }
            )

            context.put(WsTicketStore.USER_ID_KEY, userId)
        }

        override suspend fun handle(context: RoutingContext): Result =
            Successful(mapOf("userId" to context.get<Long>(WsTicketStore.USER_ID_KEY)))
    }

    /** `PanelWebSocketAPI`: cookie + the site's own origin only. */
    private inner class PanelWs : StandIn(RouteType.GET, path = "/panel/ws") {
        override suspend fun onBeforeHandle(context: RoutingContext) {
            WsAuth.checkCookieOrigin(
                source = authProvider.credentialSource(context),
                origin = AccessContext.of(context)?.origin ?: OriginClass.NONE,
                originHeader = context.request().getHeader("Origin"),
                panel = true
            )

            requireSession(context)
        }

        override suspend fun handle(context: RoutingContext): Result = Successful(mapOf("ok" to true))
    }

    private fun startServer(): Int {
        val router = Router.router(vertx)

        router.route("/api/v1/*").order(0).handler(
            AccessPlaneHandler.create(keys, sqlClient = { sqlClient }, trustedProxies = { emptyList() }, origins = origins)
        )

        val apis = listOf(
            LoginLike(), CartLike(), ReadLike(), FormLike(), VisitorLike(), UploadLike(), PaymentReturnLike(),
            CsrfRoute(), TicketRoute(), WebsiteWs(), PanelWs()
        )

        // What RouterProvider.applyRoutes does with browserAccess.
        origins.addAnyOriginPaths(
            "core",
            RouterProvider.anyOriginUrls(apis.flatMap { api -> api.paths.map { api to "/api/v1" + it.url } })
        )

        apis.forEach { api ->
            api.paths.forEach { path ->
                val route = router.route(path.routeType.vertxHttpMethod, "/api/v1" + path.url).order(1)

                api.bodyHandler()?.let { route.handler(it) }

                route.handler(api.getHandler()).failureHandler(api.getFailureHandler())
            }
        }

        return vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private val jwtCookie = AppConstants.COOKIE_PREFIX + AppConstants.JWT_COOKIE_NAME
    private val csrfCookie = AppConstants.COOKIE_PREFIX + AppConstants.CSRF_TOKEN_COOKIE_NAME

    private fun call(method: HttpMethod, path: String, headers: Map<String, String> = emptyMap(), body: String? = null): Response =
        com.panomc.platform.TestHttp.onLoop(vertx) { client.also { com.panomc.platform.HangDiagnostics.inFlight = "$method $path $headers on 127.0.0.1:$port" }.request(method, port, "127.0.0.1", "/api/v1$path")
            .compose { request ->
                headers.forEach { (name, value) -> request.putHeader(name, value) }

                if (body != null) request.send(body) else request.send()
            }
            .compose { response ->
                response.body().map { buffer ->
                    Response(response.statusCode(), response.headers().map { it.key to it.value }, buffer.toString())
                }
            } }.blockingGet()

    private fun post(path: String, headers: Map<String, String> = emptyMap()) = call(HttpMethod.POST, path, headers)

    /** Logs in without a key (a plain browser) and returns what the browser would hold. */
    private fun browser(): Browser {
        val response = post("/auth/login")

        assertEquals(200, response.status)

        val cookie = response.setCookies()
            .map { it.substringBefore(";") }
            .filter { it.startsWith(AppConstants.COOKIE_PREFIX) }
            .joinToString("; ")

        return Browser(cookie, response.json.getString("csrfToken"))
    }

    private fun Browser.headers(vararg more: Pair<String, String>) = mapOf("cookie" to cookie) + more

    private fun Browser.withCsrf(vararg more: Pair<String, String>) = headers("X-CSRF-Token" to csrf, *more)

    private fun storedKey() = runBlocking { keys.create("site", 1L, sqlClient) }.key

    private fun keyed(key: String, vararg more: Pair<String, String>) =
        mapOf(FrontendKeyService.HEADER to key, "X-Pano-Client-Ip" to "203.0.113.9") + more

    private fun bearer(token: String) = "Authorization" to "Bearer $token"

    private fun revoke() {
        tokenDao.rows.clear()
    }

    // ---- slice 8: CSRF in the Api wrapper -------------------------------------------------------------------

    @Test
    fun `a logged-in mutation without the header is 403, with it 200`() {
        val browser = browser()

        val without = post("/cart/items", browser.headers())

        assertEquals(403, without.status)
        assertEquals("INVALID_CSRF_TOKEN", without.errorCode)

        val message = without.json.getJsonObject("error").getString("message")

        assertTrue(message.contains("X-CSRF-Token"), message)
        assertTrue(message.contains("/auth/csrf"), "the message names the fix: $message")

        val with = post("/cart/items", browser.withCsrf())

        assertEquals(200, with.status)
        assertEquals(USER_ID, with.json.getLong("userId"))
    }

    @Test
    fun `a wrong header is refused and every unsafe method is covered`() {
        val browser = browser()

        assertEquals(403, post("/cart/items", browser.headers("X-CSRF-Token" to "not-the-token")).status)

        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            assertEquals(403, call(method, "/cart/items", browser.headers()).status, method.name())
            assertEquals(200, call(method, "/cart/items", browser.withCsrf()).status, method.name())
        }
    }

    @Test
    fun `a safe method never needs the header`() {
        val browser = browser()

        assertEquals(200, call(HttpMethod.GET, "/cart", browser.headers()).status)
    }

    @Test
    fun `a stray Authorization header does not exempt a cookie session`() {
        val browser = browser()
        val token = browser.cookie.substringAfter("=").substringBefore(";")

        assertEquals(403, post("/cart/items", browser.headers(bearer(token))).status)
        assertEquals(403, post("/cart/items", browser.headers("Authorization" to "Basic x")).status)
    }

    @Test
    fun `a revoked cookie can still log in again, and is simply logged out elsewhere`() {
        val browser = browser()

        revoke()

        // Login CSRF is the Origin gate's business; with a dead session there is nothing for a forged request to ride.
        val again = post("/auth/login", browser.headers())

        assertEquals(200, again.status)
        assertTrue(again.json.getString("csrfToken").isNotBlank())

        revoke()

        assertEquals(401, post("/cart/items", browser.headers()).status)
        assertEquals(401, post("/cart/items", browser.withCsrf()).status)
    }

    @Test
    fun `a Bearer token needs no header, with a stored key or without`() {
        val key = storedKey()
        val token = post("/auth/login", keyed(key)).json.getString("sessionToken")

        assertNotNull(token)
        assertEquals(200, post("/cart/items", keyed(key, bearer(token))).status)

        // Stray cookie headers on a keyed request are ignored, so they cannot bring the check back.
        assertEquals(
            200,
            post("/cart/items", keyed(key, bearer(token), "cookie" to "$jwtCookie=junk; $csrfCookie=x")).status
        )
    }

    @Test
    fun `a csrfExempt form post passes without the header`() {
        val browser = browser()

        assertEquals(200, post("/maintenance/skip", browser.headers()).status)
    }

    @Test
    fun `a login by a visitor with no session and the server-side visitor count pass`() {
        assertEquals(200, post("/auth/login").status)
        assertEquals(200, post("/visitor-visit").status)

        // SSR: the per-boot internal key from a loopback peer, no Origin.
        assertEquals(200, post("/visitor-visit", mapOf(FrontendKeyService.HEADER to keys.internalKey)).status)
    }

    @Test
    fun `authorizedBodyHandler checks the header before any of the body is read`() {
        val browser = browser()

        bodyRead.set(false)

        val refused = call(HttpMethod.POST, "/upload", browser.headers(), body = "some bytes")

        assertEquals(403, refused.status)
        assertEquals("INVALID_CSRF_TOKEN", refused.errorCode)
        assertFalse(bodyRead.get(), "the upload was refused before its body was read")

        val accepted = call(HttpMethod.POST, "/upload", browser.withCsrf(), body = "some bytes")

        assertEquals(200, accepted.status)
        assertTrue(bodyRead.get())
    }

    // ---- slice 8: the Origin gate ----------------------------------------------------------------------------

    @Test
    fun `a foreign-origin login is 403 ORIGIN_NOT_ALLOWED and the message names the origin and the setting`() {
        val response = post("/auth/login", mapOf("Origin" to "https://evil.example"))

        assertEquals(403, response.status)
        assertEquals("ORIGIN_NOT_ALLOWED", response.errorCode)

        val message = response.json.getJsonObject("error").getString("message")

        assertTrue(message.contains("https://evil.example"), message)
        assertTrue(message.contains("Themes → Site display settings"), message)
        assertNull(response.header("Access-Control-Allow-Origin"), "a foreign origin gets no CORS headers")
    }

    @Test
    fun `the gate covers every unsafe method, not reads, and Origin null is foreign`() {
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            assertEquals(403, call(method, "/cart/items", mapOf("Origin" to "https://evil.example")).status, method.name())
        }

        assertEquals(403, post("/auth/login", mapOf("Origin" to "null")).status)

        // A read from the same foreign page is not blocked here (the browser's CORS rules keep the answer from it).
        val read = call(HttpMethod.GET, "/cart", mapOf("Origin" to "https://evil.example"))

        assertEquals(401, read.status, "answered by the endpoint (no session), not by the gate")
        assertNull(read.header("Access-Control-Allow-Origin"))
    }

    @Test
    fun `a keyed login with a foreign Origin passes`() {
        val key = storedKey()

        val response = post("/auth/login", keyed(key, "Origin" to "https://evil.example"))

        assertEquals(200, response.status)
        assertTrue(response.json.getString("sessionToken").isNotBlank())

        val internal = post("/visitor-visit", mapOf(FrontendKeyService.HEADER to keys.internalKey, "Origin" to "https://evil.example"))

        assertEquals(200, internal.status)

        // An invalid key is still refused first.
        assertEquals(
            401,
            post("/auth/login", mapOf(FrontendKeyService.HEADER to "pfk_nope", "Origin" to "https://evil.example")).status
        )
    }

    @Test
    fun `the site's own origin, an allowed origin and no origin pass`() {
        assertEquals(200, post("/auth/login", mapOf("Origin" to "https://pano.test")).status)
        assertEquals(200, post("/auth/login", mapOf("Origin" to "http://pano.test:8088")).status)
        assertEquals(200, post("/auth/login", mapOf("Origin" to "http://127.0.0.1:$port")).status)
        assertEquals(200, post("/auth/login").status)

        val allowed = post("/auth/login", mapOf("Origin" to "https://play.pano.test"))

        assertEquals(200, allowed.status)
        assertEquals("https://play.pano.test", allowed.header("Access-Control-Allow-Origin"))
        assertEquals("true", allowed.header("Access-Control-Allow-Credentials"))
    }

    @Test
    fun `an ANY_ORIGIN route takes a foreign post, the next route does not`() {
        val foreign = mapOf("Origin" to "https://pay.third-party.example")

        assertEquals(200, post("/plugins/pano-plugin-market/payment/42/notify", foreign).status)
        assertEquals(403, post("/plugins/pano-plugin-market/payment/42/other", foreign).status)
        assertEquals(403, post("/auth/login", foreign).status)

        // The plugin unloads: its paths go away with it.
        origins.removeAnyOriginPaths("core")

        assertEquals(403, post("/plugins/pano-plugin-market/payment/42/notify", foreign).status)
    }

    @Test
    fun `RouterProvider collects the ANY_ORIGIN urls and nothing else`() {
        val urls = RouterProvider.anyOriginUrls(
            listOf(
                PaymentReturnLike() to "/api/plugins/p/payment/:id/notify",
                CartLike() to "/api/v1/cart/items",
                PaymentReturnLike() to "/api/plugins/p/payment/:id/notify"
            )
        )

        assertEquals(listOf("/api/plugins/p/payment/:id/notify"), urls)
        assertEquals(BrowserAccess.SAME_SITE, CartLike().browserAccess)
    }

    @Test
    fun `any-origin paths belong to their owner and match params, wildcards and a trailing slash`() {
        val policy = OriginPolicy.empty()

        policy.addAnyOriginPaths("a", listOf("/api/v1/pay/:id/return", "/api/v1/hooks/*"))
        policy.addAnyOriginPaths("b", listOf("/api/v1/oauth/callback"))

        assertTrue(policy.isAnyOriginPath("/api/v1/pay/7/return"))
        assertTrue(policy.isAnyOriginPath("/api/v1/pay/7/return/"))
        assertTrue(policy.isAnyOriginPath("/api/v1/hooks/stripe/events"))
        assertTrue(policy.isAnyOriginPath("/api/v1/oauth/callback"))
        assertFalse(policy.isAnyOriginPath("/api/v1/pay/7/return/extra"))
        assertFalse(policy.isAnyOriginPath("/api/v1/pay//return"))
        assertFalse(policy.isAnyOriginPath("/api/v1/oauth/callbackx"))
        assertFalse(policy.isAnyOriginPath("/apixv1/oauth/callback"), "dots and other regex characters are literal")
        assertEquals(setOf("/api/v1/pay/:id/return", "/api/v1/hooks/*", "/api/v1/oauth/callback"), policy.anyOriginPaths)

        policy.removeAnyOriginPaths("a")

        assertFalse(policy.isAnyOriginPath("/api/v1/pay/7/return"))
        assertTrue(policy.isAnyOriginPath("/api/v1/oauth/callback"))
    }

    // ---- GET /auth/csrf ------------------------------------------------------------------------------------------

    @Test
    fun `csrf endpoint gives a cookie session its token, never cached, and the token works`() {
        val browser = browser()

        val response = call(HttpMethod.GET, "/auth/csrf", browser.headers())

        assertEquals(200, response.status)
        assertEquals(browser.csrf, response.json.getString("csrfToken"))
        assertEquals("no-store", response.header("Cache-Control"))

        assertEquals(200, post("/cart/items", browser.headers("X-CSRF-Token" to response.json.getString("csrfToken"))).status)
    }

    @Test
    fun `csrf endpoint answers null to a Bearer and 401 to nobody`() {
        val key = storedKey()
        val token = post("/auth/login", keyed(key)).json.getString("sessionToken")

        val bearerResponse = call(HttpMethod.GET, "/auth/csrf", keyed(key, bearer(token)))

        assertEquals(200, bearerResponse.status)
        assertTrue(bearerResponse.json.containsKey("csrfToken"))
        assertNull(bearerResponse.json.getValue("csrfToken"))
        assertEquals("no-store", bearerResponse.header("Cache-Control"))

        assertEquals(401, call(HttpMethod.GET, "/auth/csrf").status)

        val browser = browser()

        revoke()

        assertEquals(401, call(HttpMethod.GET, "/auth/csrf", browser.headers()).status, "a stale cookie: the client just logs in")
    }

    @Test
    fun `csrf endpoint re-issues a missing csrf cookie`() {
        val browser = browser()
        val onlySession = browser.cookie.split("; ").filter { !it.contains("csrf_token") }.joinToString("; ")

        val response = call(HttpMethod.GET, "/auth/csrf", mapOf("cookie" to onlySession))

        assertEquals(200, response.status)

        val fresh = response.json.getString("csrfToken")

        assertTrue(fresh.isNotBlank())
        assertNotEquals(browser.csrf, fresh)
        assertTrue(response.setCookies().any { it.contains("csrf_token") && it.contains(fresh) }, "the cookie carries the token")

        val repaired = "$onlySession; ${AppConstants.COOKIE_PREFIX}${AppConstants.CSRF_TOKEN_COOKIE_NAME}_http=$fresh"

        assertEquals(200, post("/cart/items", mapOf("cookie" to repaired, "X-CSRF-Token" to fresh)).status)
    }

    @Test
    fun `csrf cookie value is read from either cookie name`() {
        assertEquals("a", GetCsrfTokenAPI.csrfCookieValue("x=1; $csrfCookie=a"))
        assertEquals("b", GetCsrfTokenAPI.csrfCookieValue("${csrfCookie}_http=b"))
        assertEquals("a", GetCsrfTokenAPI.csrfCookieValue("${csrfCookie}_http=b; $csrfCookie=a"))
        assertNull(GetCsrfTokenAPI.csrfCookieValue("$csrfCookie=; other=1"))
        assertNull(GetCsrfTokenAPI.csrfCookieValue(null))
    }

    // ---- slice 11: WebSocket ticket --------------------------------------------------------------------------

    private fun ticketOf(browser: Browser) = post("/auth/ws-ticket", browser.withCsrf()).json.getString("ticket")

    @Test
    fun `a ticket is created with the csrf header, answers ticket and expiresIn 30, and is no-store`() {
        val browser = browser()

        assertEquals(403, post("/auth/ws-ticket", browser.headers()).status)
        assertEquals(401, post("/auth/ws-ticket").status)

        val response = post("/auth/ws-ticket", browser.withCsrf())

        assertEquals(200, response.status)
        assertTrue(response.json.getString("ticket").length >= 40)
        assertEquals(30, response.json.getInteger("expiresIn"))
        assertEquals("no-store", response.header("Cache-Control"))
    }

    @Test
    fun `a ticket opens the socket once`() {
        val browser = browser()
        val ticket = ticketOf(browser)

        // No cookie at all, from any origin: the ticket is the credential.
        val first = call(HttpMethod.GET, "/ws?ticket=$ticket", mapOf("Origin" to "https://evil.example"))

        assertEquals(200, first.status)
        assertEquals(USER_ID, first.json.getLong("userId"))

        val second = call(HttpMethod.GET, "/ws?ticket=$ticket")

        assertEquals(401, second.status)
        assertEquals("INVALID_WS_TICKET", second.errorCode)

        assertEquals("INVALID_WS_TICKET", call(HttpMethod.GET, "/ws?ticket=nope").errorCode)
        assertEquals("INVALID_WS_TICKET", call(HttpMethod.GET, "/ws?ticket=").errorCode)
    }

    @Test
    fun `a ticket expires after 30 seconds and a bad ticket is not rescued by a good cookie`() {
        val browser = browser()
        val ticket = ticketOf(browser)

        clock.addAndGet(31_000)

        assertEquals("INVALID_WS_TICKET", call(HttpMethod.GET, "/ws?ticket=$ticket").errorCode)

        // The cookie is good, the ticket is not: the ticket step answers, there is no fall-through.
        assertEquals(401, call(HttpMethod.GET, "/ws?ticket=nope", browser.headers()).status)
    }

    @Test
    fun `a foreign-origin cookie upgrade is refused, the site and allowed origins are not`() {
        val browser = browser()

        val foreign = call(HttpMethod.GET, "/ws", browser.headers("Origin" to "https://evil.example"))

        assertEquals(403, foreign.status)
        assertEquals("ORIGIN_NOT_ALLOWED", foreign.errorCode)

        assertEquals(403, call(HttpMethod.GET, "/ws", browser.headers("Origin" to "null")).status)

        assertEquals(200, call(HttpMethod.GET, "/ws", browser.headers("Origin" to "https://pano.test")).status)
        assertEquals(200, call(HttpMethod.GET, "/ws", browser.headers("Origin" to "https://play.pano.test")).status)
        assertEquals(200, call(HttpMethod.GET, "/ws", browser.headers()).status)
    }

    @Test
    fun `a Bearer upgrade needs no origin check`() {
        val key = storedKey()
        val token = post("/auth/login", keyed(key)).json.getString("sessionToken")

        val response = call(HttpMethod.GET, "/ws", keyed(key, bearer(token), "Origin" to "https://evil.example"))

        assertEquals(200, response.status)
        assertEquals(USER_ID, response.json.getLong("userId"))
    }

    @Test
    fun `the panel socket takes a cookie from the site itself only`() {
        val browser = browser()

        assertEquals(200, call(HttpMethod.GET, "/panel/ws", browser.headers("Origin" to "https://pano.test")).status)
        assertEquals(200, call(HttpMethod.GET, "/panel/ws", browser.headers()).status)
        assertEquals("ORIGIN_NOT_ALLOWED", call(HttpMethod.GET, "/panel/ws", browser.headers("Origin" to "https://play.pano.test")).errorCode)
        assertEquals("ORIGIN_NOT_ALLOWED", call(HttpMethod.GET, "/panel/ws", browser.headers("Origin" to "https://evil.example")).errorCode)
    }

    @Test
    fun `WsTicketStore - single use, 30 seconds, one user each, unique tickets`() {
        val clock = AtomicLong(0)
        val store = WsTicketStore({ clock.get() }, 30_000L)

        val a = store.issue(1L)
        val b = store.issue(2L)

        assertTrue(a != b)
        assertTrue(a.length >= 40)
        assertEquals(2L, store.consume(b))
        assertNull(store.consume(b), "single use")
        assertEquals(1L, store.consume(a))
        assertNull(store.consume(a))

        val c = store.issue(3L)

        clock.set(29_999)
        assertEquals(3L, store.consume(c))

        val d = store.issue(4L)

        clock.set(29_999 + 30_000)
        assertNull(store.consume(d), "expired exactly at 30 s")

        assertNull(store.consume(null))
        assertNull(store.consume(""))
        assertNull(store.consume("never-issued"))
    }

    @Test
    fun `WsTicketStore - expired tickets are dropped before live ones, the map stays bounded`() {
        val clock = AtomicLong(0)
        val store = WsTicketStore({ clock.get() }, 30_000L)

        repeat(10_000) { store.issue(it.toLong()) }

        assertEquals(10_000, store.size())

        clock.set(31_000)

        val fresh = store.issue(99L)

        assertEquals(1, store.size(), "the expired ones were purged")
        assertEquals(99L, store.consume(fresh))

        repeat(10_005) { store.issue(it.toLong()) }

        assertTrue(store.size() <= 10_000)
    }

    @Test
    fun `cookie upgrade rule - panel is site only, website is site or allowed, no Origin passes`() {
        for (panel in listOf(false, true)) {
            assertTrue(WsTicketStore.cookieUpgradeAllowed(OriginClass.SAME, panel))
            assertTrue(WsTicketStore.cookieUpgradeAllowed(OriginClass.NONE, panel))
            assertFalse(WsTicketStore.cookieUpgradeAllowed(OriginClass.FOREIGN, panel))
        }

        assertTrue(WsTicketStore.cookieUpgradeAllowed(OriginClass.ALLOWED, panel = false))
        assertFalse(WsTicketStore.cookieUpgradeAllowed(OriginClass.ALLOWED, panel = true))

        // Only a cookie is ambient: Bearer and no credential are never judged by their origin.
        WsAuth.checkCookieOrigin(CredentialSource.BEARER, OriginClass.FOREIGN, "https://evil.example", panel = true)
        WsAuth.checkCookieOrigin(null, OriginClass.FOREIGN, "https://evil.example", panel = true)
    }

    companion object {
        private const val USER_ID = 7L
    }
}
