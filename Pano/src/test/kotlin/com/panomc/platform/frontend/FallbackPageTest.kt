package com.panomc.platform.frontend

import com.panomc.platform.frontend.pages.ActivateFallbackPage
import com.panomc.platform.frontend.pages.ActivateNewEmailFallbackPage
import com.panomc.platform.frontend.pages.FallbackPageRenderer
import com.panomc.platform.frontend.pages.LoginFallbackPage
import com.panomc.platform.frontend.pages.RenewPasswordFallbackPage
import com.panomc.platform.route.FallbackPageRoute
import com.panomc.platform.ui.FrontendMode
import com.panomc.platform.util.UsageMode
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/**
 * The built-in fallback pages (open front-end plan, doc 05 section 10.3): the registry, the one route
 * `GET /_pano/:target`, the shell and the assets. A real Vert.x server with the route mounted the way
 * `RouterProvider` mounts it; the platform side (config, setup, maintenance, URL map inputs) is a fake.
 */
class FallbackPageTest {
    private class FakeInputs : FrontendUrlInputs {
        var mode = FrontendMode.NONE
        var site = "https://site.example"
        var website = "https://pano.example"
        var overrides: Map<String, String> = emptyMap()
        var frontend: Map<String, Any?> = emptyMap()
        var routes: ThemeRouteMap? = null

        override fun mode() = mode
        override fun siteUrl() = site
        override fun websiteUrl() = website
        override fun overrides() = overrides
        override fun frontendUrls() = frontend
        override fun themeRoutes() = routes
    }

    private class FakeEnvironment : FallbackPageEnvironment {
        var ready = true
        var maintenance = false
        var locale = "en-US"

        override fun ready() = ready
        override suspend fun blockedByMaintenance(context: RoutingContext) = maintenance
        override fun siteName() = "My <Site>"
        override fun locale() = locale
        override fun logoUrl() = "/api/v1/website-logo"
        override fun faviconUrl() = "/api/v1/favicon"
        override fun homeUrl() = "/"
    }

    private val logger = LoggerFactory.getLogger("test")
    private val inputs = FakeInputs()
    private val environment = FakeEnvironment()
    private val urls = FrontendUrlMap(inputs, logger)
    private val registry = FallbackPageRegistry(urls, logger)
    private val route = FallbackPageRoute(registry, urls, FallbackPageRenderer(environment, logger), environment, logger)

    /** What the stub of `POST /api/v1/auth/verify-email` received. */
    private val activated = mutableListOf<String>()

    private lateinit var vertx: Vertx
    private var port = 0

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        registry.registerCore(
            listOf(
                ActivateFallbackPage(urls),
                ActivateNewEmailFallbackPage(urls),
                RenewPasswordFallbackPage(urls),
                LoginFallbackPage()
            )
        )

        vertx = Vertx.vertx()

        val router = Router.router(vertx)

        route.paths.forEach { path ->
            router.route(path.routeType.vertxHttpMethod, path.url).order(route.order).handler(route.getHandler())
        }

        router.route(HttpMethod.POST, "/api/v1/auth/verify-email").handler(BodyHandler.create()).handler { context ->
            activated.add(context.body().asJsonObject().getString("token"))

            context.json(JsonObject())
        }

        port = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    private fun <T> Future<T>.blockingGet(): T = toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private class Answer(val status: Int, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers[name.lowercase()]
    }

    private fun send(
        method: HttpMethod,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Answer {
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))

        return client.request(method, port, "127.0.0.1", path)
            .compose { request ->
                headers.forEach { (name, value) -> request.putHeader(name, value) }

                if (body == null) request.send() else request.send(Buffer.buffer(body))
            }
            .compose { response ->
                response.body().map { buffer ->
                    Answer(
                        response.statusCode(),
                        response.headers().entries().associate { it.key.lowercase() to it.value },
                        buffer.toString()
                    )
                }
            }
            .blockingGet()
    }

    private fun get(path: String, headers: Map<String, String> = emptyMap()) = send(HttpMethod.GET, path, null, headers)

    private val marketTargets = """{ "order": { "path": "/store/order/{id}", "fallback": true } }"""

    /** A plugin page whose template is read from a folder, the way a plugin's class loader would serve it. */
    private class OrderPage : FallbackPage("order", "fallback/order.hbs") {
        override suspend fun model(context: RoutingContext) = mapOf<String, Any?>(
            "orderId" to (context.request().getParam("id") ?: "")
        )
    }

    /** A page that words its error codes in `clientConfig.errors`, as the premium-login callback pages do. */
    private class ErrorsPage(private val own: Map<String, String> = emptyMap()) : FallbackPage("order", "fallback/order.hbs") {
        override suspend fun model(context: RoutingContext) = mapOf<String, Any?>(
            "clientConfig" to mapOf("errors" to mapOf("A_CODE" to "From client config", "SHARED" to "client"))
        )

        override fun errors(locale: String) = own
    }

    private fun pluginLoader(): ClassLoader {
        File(tempDir, "fallback").mkdirs()

        File(tempDir, "fallback/order.hbs").writeText(
            """{{pageTitle "Order"}}<h1>{{t.common.signIn}} order {{orderId}}</h1>"""
        )

        return URLClassLoader(arrayOf(tempDir.toURI().toURL()), FallbackPageTest::class.java.classLoader)
    }

    // --- the registry --------------------------------------------------------

    @Test
    fun `the four core pages are registered under their target ids`() {
        assertEquals(
            listOf("auth.activate", "auth.activate-new-email", "auth.login", "auth.renew-password"),
            registry.ids()
        )

        assertTrue(registry.has("auth.login"))
        assertFalse(registry.has("auth.register"))
    }

    @Test
    fun `a plugin page is registered under the namespace and dropped on unload`() {
        registry.registerPlugin("pano-plugin-market", "market", listOf(OrderPage()), pluginLoader())

        assertTrue(registry.has("market.order"))
        assertEquals("pano-plugin-market", registry.find("market.order")!!.owner)

        registry.unregisterPlugin("pano-plugin-market")

        assertFalse(registry.has("market.order"))
        assertTrue(registry.has("auth.login"))
    }

    @Test
    fun `a plugin cannot take a core target and a target with a dot is refused`() {
        registry.registerPlugin("pano-plugin-x", "auth", listOf(object : FallbackPage("login", "fallback/x.hbs") {}))

        // "auth.login" is core's: the plugin's page is left out, core's page stays.
        assertEquals("core", registry.find("auth.login")!!.owner)

        registry.unregisterPlugin("pano-plugin-x")

        assertEquals("core", registry.find("auth.login")!!.owner)

        assertThrows<FallbackPageRefusal> {
            registry.registerPlugin("pano-plugin-market", "market", listOf(object : FallbackPage("market.order", "x.hbs") {}))
        }
    }

    @Test
    fun `fallback true without a registered page fails the plugin load, naming the target`() {
        val refusal = assertThrows<FrontendTargetsRefusal> { urls.registerPlugin("pano-plugin-market", "market", marketTargets) }

        assertTrue(refusal.message!!.contains("market.order"))
        assertNull(urls.target("market.order"))

        registry.registerPlugin("pano-plugin-market", "market", listOf(OrderPage()), pluginLoader())
        urls.registerPlugin("pano-plugin-market", "market", marketTargets)

        assertNotNull(urls.target("market.order"))
        assertEquals("https://pano.example/_pano/market.order?id=7", urls.url("market.order", mapOf("id" to "7")))
    }

    // --- the route -------------------------------------------------------------

    @Test
    fun `the route belongs to sites with a website and answers on three paths`() {
        assertEquals(UsageMode.WITH_WEBSITE, route.usageModes)
        assertEquals(1, route.order)
        assertEquals(listOf("/_pano/assets/fallback.js", "/_pano/assets/fallback.css", "/_pano/:target"), route.paths.map { it.url })
    }

    @Test
    fun `in NONE mode an activation link reaches the fallback page and activates`() {
        // Step 4 of the URL map: nothing claims auth.activate, so the mail links to Pano's own page.
        val link = urls.url("auth.activate", mapOf("token" to "tok-123"))!!

        assertEquals("https://pano.example/_pano/auth.activate?token=tok-123", link)

        val page = get(link.removePrefix("https://pano.example"))

        assertEquals(200, page.status)
        assertTrue(page.header("content-type")!!.startsWith("text/html"))
        assertEquals("no-store", page.header("cache-control"))
        assertTrue(page.header("content-security-policy")!!.contains("default-src 'self'"))
        assertTrue(page.header("content-security-policy")!!.contains("frame-ancestors 'none'"))
        assertEquals("no-referrer", page.header("referrer-policy"))
        assertTrue(page.body.contains("""<link rel="stylesheet" href="/_pano/assets/fallback.css">"""))
        assertTrue(page.body.contains("""<script src="/_pano/assets/fallback.js" defer></script>"""))
        assertFalse(Regex("""<script(?![^>]*(src=|type="application/json"))""").containsMatchIn(page.body), "no inline script")

        // What fallback.js does with this page: read the endpoint and the fields of the form and post them as JSON.
        val endpoint = Regex("""data-pano-endpoint="([^"]+)"""").find(page.body)!!.groupValues[1]
        val token = Regex("""name="token" value="([^"]*)"""").find(page.body)!!.groupValues[1]
        val config = JsonObject(Regex("""<script type="application/json" id="pano-fallback-config">(.*?)</script>""").find(page.body)!!.groupValues[1])

        assertEquals("/auth/verify-email", endpoint)
        assertEquals("tok-123", token)
        assertEquals("/api/v1", config.getString("api"))
        assertEquals("auth.activate", config.getString("target"))
        assertTrue(page.body.contains("data-pano-auto"))

        val posted = send(
            HttpMethod.POST, config.getString("api") + endpoint,
            JsonObject().put("token", token).encode(), mapOf("Content-Type" to "application/json")
        )

        assertEquals(200, posted.status)
        assertEquals(listOf("tok-123"), activated)
    }

    @Test
    fun `every core page renders with its form`() {
        val expected = mapOf(
            "auth.activate" to "/auth/verify-email",
            "auth.activate-new-email" to "/auth/verify-new-email",
            "auth.renew-password" to "/auth/renew-password"
        )

        expected.forEach { (id, endpoint) ->
            val page = get("/_pano/$id?token=abc")

            assertEquals(200, page.status, id)
            assertTrue(page.body.contains("""data-pano-endpoint="$endpoint""""), id)
            assertTrue(page.body.contains("""name="token" value="abc""""), id)
        }

        val renew = get("/_pano/auth.renew-password?token=abc")

        assertTrue(renew.body.contains("""name="newPassword""""))
        assertTrue(renew.body.contains("""name="newPasswordRepeat""""))
    }

    @Test
    fun `the login page has the credentials, challenge and link-code steps`() {
        val page = get("/_pano/auth.login?next=/profile")

        assertEquals(200, page.status)
        assertTrue(page.body.contains("""data-pano-flow="login""""))

        listOf("credentials", "challenge", "link", "register", "verify").forEach { step ->
            assertTrue(page.body.contains("""data-pano-step="$step""""), step)
        }

        // One generic code input, named by the page's script after the deny's challengeField.
        assertTrue(page.body.contains("""name="challenge""""))
        assertTrue(page.body.contains("""name="usernameOrEmail""""))

        val config = JsonObject(Regex("""id="pano-fallback-config">(.*?)</script>""").find(page.body)!!.groupValues[1])

        assertEquals("/profile", config.getJsonObject("page").getString("next"))
    }

    @Test
    fun `next is only ever a site path`() {
        assertEquals("/profile?x=1", LoginFallbackPage.safeNext("/profile?x=1"))
        assertNull(LoginFallbackPage.safeNext("//evil.example"))
        assertNull(LoginFallbackPage.safeNext("/\\evil.example"))
        assertNull(LoginFallbackPage.safeNext("https://evil.example"))
        assertNull(LoginFallbackPage.safeNext("javascript:alert(1)"))
        assertNull(LoginFallbackPage.safeNext(""))
        assertNull(LoginFallbackPage.safeNext(null))
    }

    @Test
    fun `values are escaped in the markup and in the config block`() {
        val page = get("/_pano/auth.activate?token=" + "%22%3E%3Cscript%3Ealert(1)%3C/script%3E")

        assertFalse(page.body.contains("<script>alert(1)"))
        assertTrue(page.body.contains("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;") || page.body.contains("&#x3D;") || page.body.contains("&lt;script&gt;"))

        // The site name has markup too; it is text, and the config block cannot end its script tag.
        assertTrue(page.body.contains("My &lt;Site&gt;"))
        assertFalse(page.body.contains("My <Site>"))

        val block = Regex("""id="pano-fallback-config">(.*?)</script>""").find(page.body)!!.groupValues[1]

        assertFalse(block.contains("<"))
        assertFalse(block.contains(">"))
    }

    @Test
    fun `the page is in the language of the site and falls back to English`() {
        environment.locale = "tr"

        assertTrue(get("/_pano/auth.activate?token=a").body.contains("Hesabını etkinleştir"))

        environment.locale = "ru"

        assertTrue(get("/_pano/auth.activate?token=a").body.contains("Активация аккаунта"))

        environment.locale = "xx-YY"

        val page = get("/_pano/auth.activate?token=a")

        assertTrue(page.body.contains("Activate your account"))
        assertTrue(page.body.contains("<title>Activate your account - My &lt;Site&gt;</title>"))
        assertTrue(page.body.contains("""<html lang="xx-YY">"""))
    }

    @Test
    fun `an unknown target and a site that is not set up answer 404`() {
        assertEquals(404, get("/_pano/auth.nope").status)
        assertEquals(404, get("/_pano/auth.reset-password").status)

        environment.ready = false

        assertEquals(404, get("/_pano/auth.login").status)
    }

    @Test
    fun `a target taken over by a front-end answers 302 to it and keeps the query`() {
        inputs.mode = FrontendMode.THEME

        // The theme serves /activate itself (step 3, identity routes).
        val theme = get("/_pano/auth.activate?token=abc&utm=1")

        assertEquals(302, theme.status)
        assertEquals("https://site.example/activate?token=abc&utm=1", theme.header("location"))

        // An override of the admin wins, and the same placeholders are filled.
        inputs.overrides = mapOf("auth.activate" to "/confirm?t={token}")

        assertEquals("https://site.example/confirm?t=abc", get("/_pano/auth.activate?token=abc").header("location"))

        // The front-end's own urls entry, an absolute address on another host.
        inputs.overrides = emptyMap()
        inputs.frontend = mapOf("auth.login" to "https://app.example/sign-in")

        val login = get("/_pano/auth.login?next=%2Fprofile")

        assertEquals(302, login.status)
        assertEquals("https://app.example/sign-in?next=%2Fprofile", login.header("location"))

        // No value for the placeholder: the visitor still gets to the front-end's path.
        inputs.frontend = emptyMap()

        assertEquals("https://site.example/activate?utm=1", get("/_pano/auth.activate?utm=1").header("location"))
    }

    @Test
    fun `a theme without the route or a front-end with false leaves the fallback page standing`() {
        inputs.mode = FrontendMode.THEME
        inputs.routes = ThemeRouteMap.fromCoreMeta(JsonObject("""{ "disable": ["/activate"] }"""))

        assertEquals(200, get("/_pano/auth.activate?token=abc").status)

        inputs.routes = null
        inputs.frontend = mapOf("auth.activate" to false)

        assertEquals(200, get("/_pano/auth.activate?token=abc").status)
    }

    @Test
    fun `an override that points back at the fallback page is not a takeover`() {
        inputs.overrides = mapOf("auth.login" to "/_pano/auth.login")

        assertEquals(200, get("/_pano/auth.login").status)
    }

    @Test
    fun `maintenance mode sends a visitor who is not let through to the root`() {
        environment.maintenance = true

        val answer = get("/_pano/auth.login")

        assertEquals(302, answer.status)
        assertEquals("/", answer.header("location"))

        environment.maintenance = false

        assertEquals(200, get("/_pano/auth.login").status)
    }

    @Test
    fun `a plugin page renders from its own template and is gone after unload`() {
        registry.registerPlugin("pano-plugin-market", "market", listOf(OrderPage()), pluginLoader())

        val page = get("/_pano/market.order?id=abc123")

        assertEquals(200, page.status)
        assertTrue(page.body.contains("<h1>Sign in order abc123</h1>"))
        assertTrue(page.body.contains("<title>Order - My &lt;Site&gt;</title>"))

        registry.unregisterPlugin("pano-plugin-market")

        assertEquals(404, get("/_pano/market.order?id=abc123").status)
    }

    @Test
    fun `error texts in clientConfig reach config errors and the page's own errors win`() {
        registry.registerPlugin("pano-plugin-market", "market", listOf(ErrorsPage(mapOf("SHARED" to "own"))), pluginLoader())

        val body = get("/_pano/market.order").body

        assertTrue(body.contains("From client config"), body)
        assertTrue(body.contains("\"errors\":{"), body)
        assertTrue(body.contains("\"SHARED\":\"own\""), body)
    }

    @Test
    fun `a broken template answers 500 and does not take the route down`() {
        File(tempDir, "fallback").mkdirs()

        registry.registerPlugin(
            "pano-plugin-broken", "broken",
            listOf(object : FallbackPage("page", "fallback/missing.hbs") {}),
            URLClassLoader(arrayOf(tempDir.toURI().toURL()), FallbackPageTest::class.java.classLoader)
        )

        assertEquals(500, get("/_pano/broken.page").status)
        assertEquals(200, get("/_pano/auth.login").status)
    }

    // --- assets ------------------------------------------------------------------

    @Test
    fun `the script and the stylesheet are served and revalidated by ETag`() {
        val js = get("/_pano/assets/fallback.js")

        assertEquals(200, js.status)
        assertTrue(js.header("content-type")!!.startsWith("text/javascript"))
        assertTrue(js.body.contains("pano-fallback-config"))
        assertEquals("no-cache", js.header("cache-control"))

        val again = get("/_pano/assets/fallback.js", mapOf("If-None-Match" to js.header("etag")!!))

        assertEquals(304, again.status)

        val css = get("/_pano/assets/fallback.css")

        assertEquals(200, css.status)
        assertTrue(css.header("content-type")!!.startsWith("text/css"))
        assertTrue(css.body.contains("--pano-color-primary"))
    }

    @Test
    fun `the script keeps its promises about CSRF and generic errors`() {
        val js = get("/_pano/assets/fallback.js").body

        assertTrue(js.contains("X-CSRF-Token"))
        assertTrue(js.contains("/auth/csrf"))
        assertTrue(js.contains("challengeField"))
        assertTrue(js.contains("LINK_CODE_REQUIRED"))
    }

    @Test
    fun `a plugin page gets its own api root, a core page does not`() {
        registry.registerPlugin("pano-plugin-market", "market", listOf(OrderPage()), pluginLoader())

        fun config(path: String) =
            JsonObject(Regex("""<script type="application/json" id="pano-fallback-config">(.*?)</script>""").find(get(path).body)!!.groupValues[1])

        assertEquals("/api/plugins/pano-plugin-market", config("/_pano/market.order").getString("pluginApi"))
        assertNull(config("/_pano/auth.activate?token=a").getString("pluginApi"))
    }

    @Test
    fun `the script resolves plugin endpoints and refuses anything outside api`() {
        val node = ProcessBuilder("node", "--version").redirectErrorStream(true).start().let { runCatching { it.waitFor() == 0 }.getOrDefault(false) }

        org.junit.jupiter.api.Assumptions.assumeTrue(node, "node is not installed")

        val js = get("/_pano/assets/fallback.js").body
        val config = """{"api":"/api/v1","pluginApi":"/api/plugins/p-x"}"""

        val harness = "global.window = {}; global.document = { getElementById: () => ({ textContent: process.argv[2] }), querySelectorAll: () => [] };" +
            "eval(require('fs').readFileSync(process.argv[3], 'utf8'));" +
            "const out = JSON.parse(process.argv[4]).map(e => window.PanoFallback.resolve(e)); console.log(JSON.stringify(out));"

        val script = File(tempDir, "harness.js").apply { writeText(harness) }
        val source = File(tempDir, "fallback-under-test.js").apply { writeText(js) }
        val inputs = JsonArray()
            .add("/auth/verify-email").add("plugin:/magic-login/verify").add("/api/plugins/p-x/y").add("/api/v1/auth/login")
            .add("/../plugins/p-x/y").add("/api/../x").add("plugin:/../x").add("plugin:/a/../b").add("https://evil.test/x").add("//evil.test/x")
            .add("/api/plugins/p-x/y?z=1").add("/api\\x").add("x").add("")

        val process = ProcessBuilder("node", script.absolutePath, config, source.absolutePath, inputs.encode()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()

        process.waitFor()

        assertEquals(
            listOf(
                "/api/v1/auth/verify-email", "/api/plugins/p-x/magic-login/verify", "/api/plugins/p-x/y", "/api/v1/auth/login",
                "", "", "", "", "", "", "", "", "", ""
            ),
            JsonArray(output).list
        )
    }
}
