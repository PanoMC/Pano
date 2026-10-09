package com.panomc.platform.auth

import com.panomc.platform.AppConstants
import com.panomc.platform.access.AccessPlaneHandler
import com.panomc.platform.access.FrontendKeyService
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.FrontendKeyDao
import com.panomc.platform.db.dao.TokenDao
import com.panomc.platform.db.model.FrontendKey
import com.panomc.platform.db.model.Token
import com.panomc.platform.model.Error
import com.panomc.platform.token.AuthenticationTokenType
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.objenesis.ObjenesisStd
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The site session token (open front-end plan, doc 05 §3.3): a real Vert.x router with the access
 * handler in front of stand-in endpoints that call the real [AuthProvider] and [TokenProvider]
 * (login, a logged-in mutation, a panel endpoint), over an in-memory token table.
 */
class SiteSessionTest {
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

        fun setCookies() = headers.filter { it.first.equals("set-cookie", ignoreCase = true) }.map { it.second }
    }

    private val jwtCookie = AppConstants.COOKIE_PREFIX + AppConstants.JWT_COOKIE_NAME
    private val csrfCookie = AppConstants.COOKIE_PREFIX + AppConstants.CSRF_TOKEN_COOKIE_NAME

    private val sqlClient: SqlClient = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SqlClient::class.java)
    ) { _, method, _ -> error("not expected: ${method.name}") } as SqlClient

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private lateinit var tokenDao: MemoryTokenDao
    private lateinit var keys: FrontendKeyService
    private lateinit var tokenProvider: TokenProvider
    private lateinit var authProvider: AuthProvider
    private var port = 0

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(true))
        tokenDao = MemoryTokenDao()
        keys = FrontendKeyService(MemoryKeyDao())

        val objenesis = ObjenesisStd()

        // DatabaseManager has a hundred DAO parameters and a real connection; the session code only
        // needs the token table and a SqlClient to hand along.
        val databaseManager = objenesis.newInstance(DatabaseManager::class.java)

        setField(databaseManager, "tokenDao", tokenDao)
        setField(databaseManager, "sqlClient", sqlClient)

        val configManager = ConfigManager(vertx, LoggerFactory.getLogger("test"), AnnotationConfigApplicationContext())

        // PanoConfig's constructor reads the build stage from the environment; only two fields are used here.
        val config = objenesis.newInstance(PanoConfig::class.java)

        setField(config, "jwtKey", Base64.getEncoder().encodeToString("site-session-test-secret".toByteArray()))
        setField(config, "websiteUrl", "")
        setField(configManager, "config", config)

        tokenProvider = TokenProvider(databaseManager, configManager)

        val permissionManager = objenesis.newInstance(PermissionManager::class.java)
        val context = AnnotationConfigApplicationContext().also { it.refresh() }

        authProvider = AuthProvider(databaseManager, tokenProvider, permissionManager, configManager, context)

        port = startServer()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun <T> Future<T>.blockingGet(): T = com.panomc.platform.HangDiagnostics.await(this, 15L)

    private fun launchOn(context: RoutingContext, block: suspend () -> Unit) {
        CoroutineScope(vertx.dispatcher()).launch {
            try {
                block()
            } catch (error: Error) {
                respond(context, error.getStatusCode(), error.encode())
            } catch (e: Throwable) {
                context.fail(e)
            }
        }
    }

    private fun respond(context: RoutingContext, status: Int, body: String) {
        context.response().setStatusCode(status).putHeader("content-type", "application/json").end(body)
    }

    private fun startServer(): Int {
        val router = Router.router(vertx)

        router.route("/api/v1/*").order(0).handler(
            AccessPlaneHandler.create(keys, sqlClient = { sqlClient }, trustedProxies = { emptyList() })
        )

        // Stands in for LoginAPI: the user is already authenticated, issueSessionFor is what it calls.
        router.post("/api/v1/auth/login").order(1).handler { context ->
            launchOn(context) {
                val session = authProvider.issueSessionFor(USER_ID, context, sqlClient)

                respond(context, 200, JsonObject(session).encode())
            }
        }

        // Stands in for a LoggedInApi mutation (a cart): CSRF policy, then the login check.
        router.post("/api/v1/cart/items").order(1).handler { context ->
            launchOn(context) {
                when {
                    !authProvider.isLoggedIn(context) -> respond(context, 401, """{"error":{"code":"NOT_LOGGED_IN"}}""")
                    !authProvider.isCsrfSafe(context) -> respond(context, 403, """{"error":{"code":"INVALID_CSRF_TOKEN"}}""")
                    else -> respond(
                        context, 200, JsonObject().put("userId", authProvider.getUserIdFromRoutingContext(context)).encode()
                    )
                }
            }
        }

        // Stands in for a PanelApi: the site token refusal comes first, then the login check.
        router.get("/api/v1/panel/basicData").order(1).handler { context ->
            launchOn(context) {
                authProvider.requireNoSiteToken(context)

                if (!authProvider.isLoggedIn(context)) {
                    respond(context, 401, """{"error":{"code":"NOT_LOGGED_IN"}}""")
                } else {
                    respond(context, 200, "{}")
                }
            }
        }

        return vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private fun call(method: HttpMethod, path: String, headers: Map<String, String> = emptyMap()): Response =
        client.also { com.panomc.platform.HangDiagnostics.inFlight = "$method $path $headers on 127.0.0.1:$port" }.request(method, port, "127.0.0.1", path)
            .compose { request ->
                headers.forEach { (name, value) -> request.putHeader(name, value) }

                request.send()
            }
            .compose { response ->
                response.body().map { body ->
                    Response(response.statusCode(), response.headers().map { it.key to it.value }, body.toString())
                }
            }
            .blockingGet()

    private fun createStoredKey() = runBlocking { keys.create("site", 1L, sqlClient) }.key

    private fun keyed(key: String, vararg more: Pair<String, String>) =
        mapOf(FrontendKeyService.HEADER to key, "X-Pano-Client-Ip" to "203.0.113.9") + more

    private fun bearer(token: String) = "Authorization" to "Bearer $token"

    private fun login(headers: Map<String, String>) = call(HttpMethod.POST, "/api/v1/auth/login", headers)

    private fun cookieValue(response: Response, name: String) =
        response.setCookies().firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.substringBefore(";")

    @Test
    fun `worked example - login with a stored key returns a site token, it works as a Bearer, the panel refuses it`() {
        val key = createStoredKey()

        val loginResponse = login(keyed(key))

        assertEquals(200, loginResponse.status)
        assertTrue(loginResponse.json.getString("sessionToken").isNotBlank())
        assertTrue(loginResponse.json.getLong("expiresAt") > System.currentTimeMillis())
        assertFalse(loginResponse.json.containsKey("csrfToken"))
        assertTrue(loginResponse.setCookies().isEmpty(), "a site session sets no cookies")

        val token = loginResponse.json.getString("sessionToken")

        assertTrue(authProvider.isSiteToken(token))
        assertEquals("site", tokenProvider.parseToken(token).getClaim("scope").asString())
        assertEquals(1, tokenDao.rows.size, "the site token is a stored session like any other")

        // No CSRF header and no cookie: a Bearer with a stored key is exempt.
        val cart = call(HttpMethod.POST, "/api/v1/cart/items", keyed(key, bearer(token)))

        assertEquals(200, cart.status)
        assertEquals(USER_ID, cart.json.getLong("userId"))

        val panel = call(HttpMethod.GET, "/api/v1/panel/basicData", keyed(key, bearer(token)))

        assertEquals(403, panel.status)
        assertEquals("SITE_TOKEN_NOT_ALLOWED", panel.json.getJsonObject("error").getString("code"))
    }

    @Test
    fun `a stray cookie header on a keyed request is ignored`() {
        val key = createStoredKey()
        val token = login(keyed(key)).json.getString("sessionToken")

        val cart = call(
            HttpMethod.POST,
            "/api/v1/cart/items",
            keyed(key, bearer(token), "cookie" to "$jwtCookie=nonsense; $csrfCookie=x")
        )

        assertEquals(200, cart.status)

        val cookieOnly = call(
            HttpMethod.POST, "/api/v1/cart/items", keyed(key, "cookie" to "$jwtCookie=$token; $csrfCookie=x", "X-CSRF-Token" to "x")
        )

        assertEquals(401, cookieOnly.status, "with a stored key the session cookie is not read")
    }

    @Test
    fun `a site token in a cookie is not logged in`() {
        val key = createStoredKey()
        val token = login(keyed(key)).json.getString("sessionToken")

        val cart = call(
            HttpMethod.POST,
            "/api/v1/cart/items",
            mapOf("cookie" to "$jwtCookie=$token; $csrfCookie=abc", "X-CSRF-Token" to "abc")
        )

        assertEquals(401, cart.status)
    }

    @Test
    fun `a site token as a Bearer without a stored key is not logged in`() {
        val key = createStoredKey()
        val token = login(keyed(key)).json.getString("sessionToken")

        assertEquals(401, call(HttpMethod.POST, "/api/v1/cart/items", mapOf(bearer(token))).status)

        // The per-boot internal key is not a stored key either.
        val internal = mapOf(FrontendKeyService.HEADER to keys.internalKey, bearer(token))

        assertEquals(401, call(HttpMethod.POST, "/api/v1/cart/items", internal).status)
    }

    @Test
    fun `the panel refuses a site token wherever it comes from`() {
        val key = createStoredKey()
        val token = login(keyed(key)).json.getString("sessionToken")

        for (headers in listOf(
            mapOf(bearer(token)),
            mapOf("cookie" to "$jwtCookie=$token"),
            keyed(key, bearer(token))
        )) {
            val panel = call(HttpMethod.GET, "/api/v1/panel/basicData", headers)

            assertEquals(403, panel.status)
            assertEquals("SITE_TOKEN_NOT_ALLOWED", panel.json.getJsonObject("error").getString("code"))
        }
    }

    @Test
    fun `the internal key still gets a cookie session`() {
        val internal = mapOf(FrontendKeyService.HEADER to keys.internalKey)

        val loginResponse = login(internal)

        assertEquals(200, loginResponse.status)

        val csrf = loginResponse.json.getString("csrfToken")

        assertTrue(csrf.isNotBlank())
        assertFalse(loginResponse.json.containsKey("sessionToken"))

        val jwt = cookieValue(loginResponse, jwtCookie) ?: cookieValue(loginResponse, "${jwtCookie}_http")
        val csrfFromCookie = cookieValue(loginResponse, csrfCookie) ?: cookieValue(loginResponse, "${csrfCookie}_http")

        assertNotNull(jwt, "the session cookie is set")
        assertEquals(csrf, csrfFromCookie)
        assertFalse(authProvider.isSiteToken(jwt!!), "a cookie session carries no site scope")

        val cookie = "${AppConstants.COOKIE_PREFIX + AppConstants.JWT_COOKIE_NAME}_http=$jwt; ${csrfCookie}_http=$csrf"

        assertEquals(403, call(HttpMethod.POST, "/api/v1/cart/items", internal + ("cookie" to cookie)).status)
        assertEquals(
            200,
            call(HttpMethod.POST, "/api/v1/cart/items", internal + mapOf("cookie" to cookie, "X-CSRF-Token" to csrf)).status
        )
        assertEquals(200, call(HttpMethod.GET, "/api/v1/panel/basicData", internal + ("cookie" to cookie)).status)
    }

    @Test
    fun `a request without a key gets a cookie session`() {
        val loginResponse = login(emptyMap())

        assertEquals(200, loginResponse.status)
        assertTrue(loginResponse.json.getString("csrfToken").isNotBlank())
        assertFalse(loginResponse.json.containsKey("sessionToken"))
        assertEquals(2, loginResponse.setCookies().count { it.startsWith(AppConstants.COOKIE_PREFIX) && !it.contains("Max-Age=0") })
    }

    @Test
    fun `banning a player revokes the site token`() {
        val key = createStoredKey()
        val token = login(keyed(key)).json.getString("sessionToken")

        assertEquals(200, call(HttpMethod.POST, "/api/v1/cart/items", keyed(key, bearer(token))).status)

        // What PlayerBanService.ban does to the player's sessions.
        runBlocking {
            tokenProvider.invalidateTokensBySubjectAndType(USER_ID.toString(), AuthenticationTokenType, sqlClient)
        }

        assertEquals(401, call(HttpMethod.POST, "/api/v1/cart/items", keyed(key, bearer(token))).status)
    }

    @Test
    fun `site sessions count toward the five session cap`() {
        val key = createStoredKey()

        repeat(7) { login(keyed(key)) }

        assertEquals(5, tokenDao.rows.size)
    }

    @Test
    fun `credentialSource - stored key ignores cookies, otherwise cookie wins over Bearer`() {
        val cookie = "$jwtCookie=abc"

        assertEquals(CredentialSource.BEARER, AuthProvider.credentialSource(cookie, "Bearer t", storedKey = true))
        assertNull(AuthProvider.credentialSource(cookie, null, storedKey = true))
        assertNull(AuthProvider.credentialSource(cookie, "Basic x", storedKey = true))
        assertNull(AuthProvider.credentialSource(null, "Bearer ", storedKey = true))

        assertEquals(CredentialSource.COOKIE, AuthProvider.credentialSource(cookie, "Bearer t", storedKey = false))
        assertEquals(CredentialSource.COOKIE, AuthProvider.credentialSource("${jwtCookie}_http=abc", null, storedKey = false))
        assertEquals(CredentialSource.BEARER, AuthProvider.credentialSource("theme=dark", "Bearer t", storedKey = false))
        assertNull(AuthProvider.credentialSource("theme=dark", null, storedKey = false))
    }

    private companion object {
        const val USER_ID = 42L
    }
}
