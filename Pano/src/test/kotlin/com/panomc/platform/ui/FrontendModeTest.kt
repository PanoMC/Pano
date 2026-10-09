package com.panomc.platform.ui

import com.panomc.platform.Main
import com.panomc.platform.UIManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.HoconWriter
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.config.migration.ConfigMigration38To39
import com.panomc.platform.access.FrontendKeyService
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.dao.FrontendKeyDao
import com.panomc.platform.db.model.FrontendKey
import com.panomc.platform.route.ServersModeRootHandler
import com.panomc.platform.setup.SetupManager
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.http.WebSocketClientOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.objenesis.ObjenesisStd
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The front-end modes (open front-end plan, doc 05 §8): a real UIManager, controller, installer and
 * Vert.x router in front of real Bun processes and stub upstreams. The panel is a stub route at
 * order 4, where the real panel proxy sits, so "`/panel` still answers" is about the order.
 */
class FrontendModeTest {
    @TempDir
    lateinit var dir: File

    private val systemProperties = listOf("pano.configFile", "pano.librariesFolder", "pano.customAppsFolder")
    private val savedProperties = systemProperties.associateWith { System.getProperty(it) }

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private lateinit var proxyClient: HttpClient
    private lateinit var configManager: ConfigManager
    private lateinit var uiManager: UIManager
    private lateinit var installer: CustomAppInstaller
    private lateinit var controller: ThemeUiController
    private lateinit var router: Router
    private lateinit var context: AnnotationConfigApplicationContext
    private val servers = CopyOnWriteArrayList<HttpServer>()
    private var panoPort = 0
    private var panelRequests = 0

    /** Requests the stub upstream saw: `METHOD path` plus the forwarded-proto header. */
    private val upstreamSeen = CopyOnWriteArrayList<String>()

    private class Response(val status: Int, val headers: Map<String, String>, val body: String)

    @BeforeEach
    fun setUp() {
        System.setProperty("pano.configFile", File(dir, "config.conf").absolutePath)
        System.setProperty("pano.librariesFolder", File(dir, "libraries").absolutePath)
        System.setProperty("pano.customAppsFolder", File(dir, "custom-apps").absolutePath)

        vertx = Vertx.vertx()
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
        // Pano's own client: keep-alive on, as in SpringConfig (a closing client breaks a websocket handshake).
        proxyClient = vertx.createHttpClient(HttpClientOptions().setTrustAll(true).setVerifyHost(false))
        context = AnnotationConfigApplicationContext().apply { refresh() }

        val logger = LoggerFactory.getLogger("test")

        configManager = ConfigManager(vertx, logger, context)

        val config = PanoConfig(39, releaseChannel = com.panomc.platform.ReleaseStage.ALPHA)

        config.server.host = "127.0.0.1"
        config.server.httpPort = freePort()
        config.setup.step = 5

        ConfigManager::class.java.getDeclaredField("config").apply { isAccessible = true }.set(configManager, config)

        val keyService = FrontendKeyService(object : FrontendKeyDao() {
            override suspend fun init(sqlClient: SqlClient) {}
            override suspend fun add(frontendKey: FrontendKey, sqlClient: SqlClient) = 1L
            override suspend fun getAll(sqlClient: SqlClient) = emptyList<FrontendKey>()
            override suspend fun getById(id: Long, sqlClient: SqlClient): FrontendKey? = null
            override suspend fun count(sqlClient: SqlClient) = 0
            override suspend fun deleteById(id: Long, sqlClient: SqlClient) = false
            override suspend fun updateLastUsedAt(id: Long, lastUsedAt: Long, sqlClient: SqlClient) {}
        })

        context.beanFactory.registerSingleton("frontendKeyService", keyService)

        val objenesis = ObjenesisStd()

        uiManager = UIManager(
            logger,
            configManager,
            SetupManager(configManager, context),
            proxyClient,
            objenesis.newInstance(AuthProvider::class.java),
            objenesis.newInstance(Main::class.java),
            context,
            vertx
        )

        installer = CustomAppInstaller(uiManager, vertx, logger)

        router = Router.router(vertx)

        // Where the real panel proxy is (order 4); everything the front-end route must not take.
        router.route("/panel/*").order(4).handler {
            panelRequests++
            it.response().end("panel")
        }

        controller = ThemeUiController(uiManager, configManager, router, logger)

        // The order-6 floor, as RouterProvider builds it.
        router.route("/*").order(6).handler(ServersModeRootHandler(configManager, SetupManager(configManager, context), logger).create())
        router.route("/*").order(6).handler(UIManager.uiUnavailableHandler())

        panoPort = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    @AfterEach
    fun tearDown() {
        uiManager.shutdown()
        servers.forEach { runCatching { it.close().blockingGet() } }
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
        context.close()

        savedProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    // --- custom apps ---------------------------------------------------------------------------

    @Test
    fun `the two-file app answers hello at the root`() {
        installBun()

        val zip = zip(
            "manifest.json" to """{ "id": "my-site", "type": "custom-app", "title": "My site", "version": "1.0.0", "author": "me" }""",
            "index.js" to """Bun.serve({ port: process.env.PORT, hostname: process.env.HOST, fetch: () => new Response("hello") });"""
        )

        val app = runBlocking { installer.install(zip) }

        assertEquals("my-site", app.id)
        assertEquals(listOf("my-site"), installer.list().map { it.id })

        val result = runBlocking { controller.apply(FrontendMode.CUSTOM_APP, "my-site") }

        assertFalse(result.alreadyRunning)
        assertEquals(FrontendMode.CUSTOM_APP, result.mode)
        assertEquals("hello", get("/").body)
        assertEquals("hello", get("/anything/else").body)
        assertEquals("panel", get("/panel/x").body)
        assertEquals("my-site", uiManager.activeFrontendId())
        assertTrue(uiManager.isCustomAppActive("my-site"))
        assertTrue(controller.isRunning)

        // The choice is on disk, in the documented block.
        val onDisk = File(dir, "config.conf").readText()
        assertTrue(onDisk.contains("frontend {"), onDisk)
        assertTrue(onDisk.contains("""mode = "CUSTOM_APP""""), onDisk)
        assertTrue(onDisk.contains("""custom-app = "my-site""""), onDisk)

        // Applying again is a no-op, and an active app can be neither deleted nor replaced.
        assertTrue(runBlocking { controller.apply(FrontendMode.CUSTOM_APP, "my-site") }.alreadyRunning)
        assertThrows<CustomAppActive> { installer.installBlocking(zip) }

        assertEquals("my-site", controller.stop())
        assertFalse(controller.isRunning)
        assertTrue(uiManager.getStartedUIs().none { it.id == "my-site" })
    }

    @Test
    fun `a theme zip is refused as an app and a custom app is refused as a theme`() {
        val themeManifest = """{ "id": "fancy-theme", "title": "Fancy", "version": "v1", "author": "me",
            "screenshots": ["screenshots/1.png"], "panoVersion": "local-build" }"""

        val refused = assertThrows<CustomAppInvalidManifest> {
            installer.installBlocking(zip("manifest.json" to themeManifest, "index.js" to "// theme"))
        }

        assertEquals("CUSTOM_APP_INVALID_MANIFEST", refused.code)
        assertTrue(refused.encode(mapOf()).contains("\"type\""), refused.encode(mapOf()))
        assertTrue(installer.list().isEmpty())

        val appManifest = File(dir, "manifest.json")
        appManifest.writeText("""{ "id": "my-site", "type": "custom-app", "title": "T", "version": "1", "author": "a" }""")

        val notATheme = assertThrows<IllegalArgumentException> { uiManager.parseThemeManifest(appManifest) }
        assertTrue(notATheme.message!!.contains("custom app"), notATheme.message)

        // A real theme manifest still parses.
        val theme = File(dir, "theme-manifest.json")
        theme.writeText(themeManifest)
        assertEquals("fancy-theme", uiManager.parseThemeManifest(theme).id)
    }

    @Test
    fun `the installer names what is wrong with an app`() {
        val ok = """{ "id": "ok-app", "type": "custom-app", "title": "T", "version": "1", "author": "a" }"""

        // No entry file.
        assertEquals("CUSTOM_APP_NO_ENTRY", assertThrows<CustomAppNoEntry> {
            installer.installBlocking(zip("manifest.json" to ok))
        }.code)

        // A field that is missing is named.
        val missing = assertThrows<CustomAppInvalidManifest> {
            installer.installBlocking(zip("manifest.json" to """{ "id": "ok-app", "type": "custom-app", "title": "T", "author": "a" }""", "index.js" to ""))
        }
        assertTrue(missing.encode(mapOf()).contains("\"version\""), missing.encode(mapOf()))

        // Premium is refused.
        assertThrows<CustomAppInvalidManifest> {
            installer.installBlocking(zip("manifest.json" to ok.replace("}", ", \"premium\": true }"), "index.js" to ""))
        }

        // A theme id, panel-ui and setup-ui are taken.
        for (taken in listOf("vanilla-theme", "panel-ui", "setup-ui")) {
            assertEquals("CUSTOM_APP_ID_TAKEN", assertThrows<CustomAppIdTaken> {
                installer.installBlocking(zip("manifest.json" to ok.replace("ok-app", taken), "index.js" to ""))
            }.code)
        }

        // apiLevel is optional; a level outside the supported range is refused with the range.
        val tooNew = assertThrows<CustomAppApiLevel> {
            installer.installBlocking(zip("manifest.json" to ok.replace("}", ", \"apiLevel\": 99 }"), "index.js" to ""))
        }
        assertEquals("CUSTOM_APP_API_LEVEL", tooNew.code)
        assertTrue(tooNew.encode(mapOf()).contains("\"current\""), tooNew.encode(mapOf()))

        assertEquals("ok-app", installer.installBlocking(zip("manifest.json" to ok, "index.js" to "")).id)
        assertEquals(
            1,
            installer.installBlocking(zip("manifest.json" to ok.replace("}", ", \"apiLevel\": ${SupportedApiLevel.current} }"), "index.js" to "")).apiLevel
        )

        // A path that climbs out of the folder never lands on disk.
        assertThrows<CustomAppInvalidManifest> {
            installer.installBlocking(zip("manifest.json" to ok, "index.js" to "", "../escaped.txt" to "x"))
        }
        assertFalse(File(dir, "escaped.txt").exists())

        // Not a zip at all.
        assertThrows<CustomAppInvalidManifest> {
            installer.installBlocking(File(dir, "garbage.zip").apply { writeText("not a zip") })
        }
    }

    @Test
    fun `an app that cannot start leaves the previous front-end serving`() {
        val upstream = stub("one")

        runBlocking { controller.apply(FrontendMode.EXTERNAL, upstreamUrl = upstream) }

        assertEquals("one:/", get("/").body)

        // Not installed, so there is no index.js to run.
        val failure = assertThrows<FrontendStartFailed> { runBlocking { controller.apply(FrontendMode.CUSTOM_APP, "ghost") } }

        assertEquals("FRONTEND_START_FAILED", failure.code)
        assertEquals("one:/", get("/").body)
        assertEquals(FrontendMode.EXTERNAL, uiManager.frontendMode)
        assertEquals("EXTERNAL", configManager.config.effectiveFrontend.mode)
    }

    @Test
    fun `switching from an app to a proxy stops the app only after the proxy is bound`() {
        installBun()

        installer.installBlocking(
            zip(
                "manifest.json" to """{ "id": "my-site", "type": "custom-app", "title": "T", "version": "1", "author": "a" }""",
                "index.js" to """Bun.serve({ port: process.env.PORT, hostname: process.env.HOST, fetch: () => new Response("hello") });"""
            )
        )

        runBlocking { controller.apply(FrontendMode.CUSTOM_APP, "my-site") }

        assertEquals("hello", get("/").body)

        val upstream = stub("ext")

        runBlocking { controller.apply(FrontendMode.EXTERNAL, upstreamUrl = upstream) }

        assertEquals("ext:/", get("/").body)
        assertTrue(uiManager.getStartedUIs().none { it.id == "my-site" }, "the app process must be gone")
        assertEquals("external", uiManager.activeFrontendId())
        assertFalse(uiManager.isCustomAppActive("my-site"))
    }

    // --- EXTERNAL ------------------------------------------------------------------------------

    @Test
    fun `external proxies the pages while panel and api paths never reach it`() {
        val upstream = stub("up")

        val result = runBlocking { controller.apply(FrontendMode.EXTERNAL, upstreamUrl = "$upstream/") }

        assertFalse(result.alreadyRunning)
        assertEquals(FrontendMode.EXTERNAL, result.mode)
        assertEquals("external", result.themeId)

        val page = get("/shop/items?page=2", mapOf("x-test" to "1"))

        assertEquals(200, page.status)
        assertEquals("up:/shop/items", page.body)

        // The forwarded-proto header comes from ForwardedProtoInterceptor.
        assertTrue(upstreamSeen.any { it == "GET /shop/items proto=http" }, upstreamSeen.toString())

        // Panel answers (order 4), and nothing under /panel, /api or /_pano is forwarded.
        val before = upstreamSeen.size

        assertEquals("panel", get("/panel/servers").body)
        assertEquals(404, get("/api/v1/does-not-exist").status)
        assertEquals(404, get("/_pano/auth.login").status)
        assertEquals(before, upstreamSeen.size)

        // The cache policy is the upstream's own, not the theme one.
        assertNull(get("/shop/items").headers["cache-control"])

        assertTrue(runBlocking { controller.apply(FrontendMode.EXTERNAL, upstreamUrl = upstream) }.alreadyRunning)
        assertEquals("""mode = "EXTERNAL"""", File(dir, "config.conf").readLines().first { it.trim().startsWith("mode =") }.trim())
        assertEquals(upstream, configManager.config.effectiveFrontend.upstreamUrl)
    }

    @Test
    fun `external reaches an https upstream`() {
        val certificate = io.vertx.core.net.SelfSignedCertificate.create("127.0.0.1")

        val secure = vertx.createHttpServer(
            io.vertx.core.http.HttpServerOptions().setSsl(true).setKeyCertOptions(certificate.keyCertOptions())
        ).requestHandler { it.response().end("tls:${it.path()}") }.listen(0, "127.0.0.1").blockingGet()

        servers += secure

        runBlocking { controller.apply(FrontendMode.EXTERNAL, upstreamUrl = "https://127.0.0.1:${secure.actualPort()}") }

        assertEquals("tls:/account", get("/account").body)
    }

    @Test
    fun `external carries a websocket through`() {
        val wsServer = vertx.createHttpServer()
            .webSocketHandler { socket -> socket.textMessageHandler { socket.writeTextMessage("echo:$it") } }
            .listen(0, "127.0.0.1").blockingGet()

        servers += wsServer

        runBlocking { controller.apply(FrontendMode.EXTERNAL, upstreamUrl = "http://127.0.0.1:${wsServer.actualPort()}") }

        val reply = java.util.concurrent.CompletableFuture<String>()
        val webSocketClient = vertx.createWebSocketClient(WebSocketClientOptions())

        webSocketClient.connect(panoPort, "127.0.0.1", "/socket").onComplete { connected ->
            if (connected.failed()) {
                reply.completeExceptionally(connected.cause())

                return@onComplete
            }

            val socket = connected.result()

            socket.textMessageHandler { reply.complete(it) }
            socket.writeTextMessage("hi")
        }

        val echoed = try {
            reply.get(10, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            val rejected = e.cause as? io.vertx.core.http.UpgradeRejectedException

            throw AssertionError("upgrade rejected: ${rejected?.status} ${rejected?.headers} ${rejected?.body}", e)
        }

        assertEquals("echo:hi", echoed)
    }

    @Test
    fun `external refuses a bad address, this Pano itself, and reports an unreachable one`() {
        for (bad in listOf("", "ftp://example.com", "not a url", "http://", "http://host/with/path", "http://user@host:80")) {
            assertThrows<UpstreamInvalidUrl>(bad) { runBlocking { controller.apply(FrontendMode.EXTERNAL, upstreamUrl = bad) } }
        }

        val port = configManager.config.server.httpPort

        for (self in listOf("http://127.0.0.1:$port", "http://localhost:$port", "http://0.0.0.0:$port")) {
            assertEquals("UPSTREAM_IS_PANO", assertThrows<UpstreamIsPano>(self) {
                runBlocking { controller.apply(FrontendMode.EXTERNAL, upstreamUrl = self) }
            }.code)
        }

        // A port that is not Pano's is fine even on the same host.
        assertFalse(UpstreamTarget.parse("http://127.0.0.1:${port + 1}")!!.pointsAtPano(configManager.config))

        assertTrue(UpstreamTarget.parse(stub("x"))!!.probe(client).blockingGet())
        assertFalse(UpstreamTarget.parse("http://127.0.0.1:${freePort()}")!!.probe(client).blockingGet())

        assertEquals(FrontendMode.THEME, uiManager.frontendMode)
        assertEquals("THEME", configManager.config.effectiveFrontend.mode)
    }

    // --- dev-url -------------------------------------------------------------------------------

    @Test
    fun `dev-url proxies the root while the mode is THEME and Development Mode is on`() {
        val dev = stub("dev")

        val frontend = configManager.config.effectiveFrontend

        frontend.devUrl = dev

        // Development Mode off: the field is ignored.
        configManager.config.developmentMode = false
        assertNull(uiManager.devServerTarget())

        // Another mode: ignored as well.
        configManager.config.developmentMode = true
        frontend.mode = "NONE"
        assertNull(uiManager.devServerTarget())

        frontend.mode = "THEME"
        assertEquals(dev, uiManager.devServerTarget()!!.origin)

        val result = runBlocking { controller.apply(FrontendMode.THEME) }

        assertFalse(result.alreadyRunning)
        assertEquals(0, uiManager.getStartedUIs().size, "no theme process may be started")
        assertEquals("dev:/", get("/").body)
        assertEquals("dev:/some/page", get("/some/page").body)
        assertEquals("panel", get("/panel/x").body)
        assertEquals(1, panelRequests)
        assertEquals(UIManager.DEV_SERVER_FRONTEND_ID, uiManager.siteBinding!!.id)
        assertEquals(FrontendMode.THEME, uiManager.frontendMode)

        // Same again: nothing to do.
        assertTrue(runBlocking { controller.apply(FrontendMode.THEME) }.alreadyRunning)
    }

    @Test
    fun `dev-url pointing at this Pano is not a theme dev server`() {
        val port = configManager.config.server.httpPort

        assertTrue(UpstreamTarget.parse("http://localhost:$port")!!.pointsAtPano(configManager.config))
    }

    // --- NONE ----------------------------------------------------------------------------------

    @Test
    fun `none sends the root to the panel and answers 404 elsewhere`() {
        val result = runBlocking { controller.apply(FrontendMode.NONE) }

        assertEquals(FrontendMode.NONE, result.mode)
        assertFalse(controller.isRunning)

        val root = get("/")

        assertEquals(302, root.status)
        assertEquals("/panel", root.headers["location"])

        assertEquals(404, get("/blog/post").status)
        assertEquals(404, get("/_pano/auth.login").status)
        assertEquals("panel", get("/panel/x").body)

        // site-url on another host is where visitors are.
        configManager.config.effectiveFrontend.siteUrl = "https://www.example.org"

        assertEquals("https://www.example.org", get("/").headers["location"])

        // ... but not when it is the host the request came to.
        configManager.config.effectiveFrontend.siteUrl = "http://127.0.0.1:$panoPort"

        assertEquals("/panel", get("/").headers["location"])

        assertTrue(runBlocking { controller.apply(FrontendMode.NONE) }.alreadyRunning)
    }

    @Test
    fun `the none root rule only claims what it should`() {
        fun decide(
            frontendMode: FrontendMode = FrontendMode.NONE,
            usageMode: com.panomc.platform.util.UsageMode = com.panomc.platform.util.UsageMode.BOTH,
            setupDone: Boolean = true,
            path: String = "/",
            method: HttpMethod = HttpMethod.GET
        ) = ServersModeRootHandler.decideNone(frontendMode, usageMode, setupDone, path, method, "", "example.com", 80, "http")

        assertTrue(decide() is ServersModeRootHandler.NoneDecision.Redirect)
        assertTrue(decide(method = HttpMethod.HEAD) is ServersModeRootHandler.NoneDecision.Redirect)
        assertEquals(ServersModeRootHandler.NoneDecision.NotFound, decide(method = HttpMethod.POST))
        assertEquals(ServersModeRootHandler.NoneDecision.NotFound, decide(path = "/anything"))
        assertEquals(ServersModeRootHandler.NoneDecision.Pass, decide(path = "/panel"))
        assertEquals(ServersModeRootHandler.NoneDecision.Pass, decide(path = "/panel/login"))
        assertEquals(ServersModeRootHandler.NoneDecision.Pass, decide(frontendMode = FrontendMode.THEME))
        assertEquals(ServersModeRootHandler.NoneDecision.Pass, decide(frontendMode = FrontendMode.EXTERNAL))
        assertEquals(ServersModeRootHandler.NoneDecision.Pass, decide(setupDone = false))
        assertEquals(
            ServersModeRootHandler.NoneDecision.Pass,
            decide(usageMode = com.panomc.platform.util.UsageMode.SERVERS)
        )
    }

    // --- config --------------------------------------------------------------------------------

    @Test
    fun `migration 38 to 39 adds the frontend block and keeps an existing one`() {
        val migration = ConfigMigration38To39()

        assertEquals(38, migration.from)
        assertEquals(39, migration.to)
        assertTrue(migration.isMigratable(38))
        assertFalse(migration.isMigratable(37))

        val config = JsonObject().put("config-version", 38)

        migration.migrate(config)

        val block = config.getJsonObject("frontend")

        assertEquals("THEME", block.getString("mode"))

        for (key in listOf("custom-app", "upstream-url", "site-url", "descriptor-url", "dev-url")) {
            assertEquals("", block.getString(key), key)
        }

        val kept = JsonObject().put("frontend", JsonObject().put("mode", "NONE"))

        migration.migrate(kept)

        assertEquals("NONE", kept.getJsonObject("frontend").getString("mode"))

        // It reads back, and the block is written with its comment next to the other top-level keys.
        assertEquals(FrontendMode.THEME, PanoConfig.from(config).effectiveFrontend.parsedMode)

        val text = HoconWriter.render(JsonObject(PanoConfig(39, releaseChannel = com.panomc.platform.ReleaseStage.ALPHA).toString()), PanoConfig::class.java)

        assertTrue(text.contains("# Front-end"), text)
        assertTrue(text.contains("dev-url = \"\""), text)
    }

    @Test
    fun `the mode is read leniently and a missing block means THEME`() {
        assertEquals(FrontendMode.CUSTOM_APP, FrontendMode.parse(" custom-app "))
        assertEquals(FrontendMode.EXTERNAL, FrontendMode.parse("external"))
        assertEquals(FrontendMode.THEME, FrontendMode.parse("nonsense"))
        assertEquals(FrontendMode.THEME, FrontendMode.parse(null))
        assertNull(FrontendMode.parseOrNull("nonsense"))
        assertNull(FrontendMode.parseOrNull(""))

        assertEquals(FrontendMode.THEME, PanoConfig.from(JsonObject().put("config-version", 38)).effectiveFrontend.parsedMode)
        assertEquals(
            FrontendMode.NONE,
            PanoConfig.from(JsonObject().put("frontend", JsonObject().put("mode", "none"))).effectiveFrontend.parsedMode
        )
    }

    @Test
    fun `the active front-end id follows the mode`() {
        assertEquals("", uiManager.activeFrontendId())

        runBlocking { controller.apply(FrontendMode.EXTERNAL, upstreamUrl = stub("x")) }

        assertEquals("external", uiManager.activeFrontendId())

        uiManager.descriptorId = "my-descriptor"

        assertEquals("my-descriptor", uiManager.activeFrontendId())
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun <T> Future<T>.blockingGet(): T = toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** A stub upstream answering `<name>:<path>` and recording what it saw; returns its address. */
    private fun stub(name: String): String {
        val server = vertx.createHttpServer().requestHandler { request ->
            upstreamSeen += "${request.method()} ${request.path()} proto=${request.getHeader("X-Forwarded-Proto")}"

            request.response().end("$name:${request.path()}")
        }.listen(0, "127.0.0.1").blockingGet()

        servers += server

        return "http://127.0.0.1:${server.actualPort()}"
    }

    private fun get(path: String, headers: Map<String, String> = emptyMap()): Response =
        client.request(HttpMethod.GET, panoPort, "127.0.0.1", path)
            .compose { request ->
                headers.forEach { (name, value) -> request.putHeader(name, value) }

                request.send()
            }
            .compose { response ->
                response.body().map { body ->
                    Response(response.statusCode(), response.headers().associate { it.key.lowercase() to it.value }, body.toString())
                }
            }
            .blockingGet()

    private fun zip(vararg files: Pair<String, String>): File {
        val file = File.createTempFile("app", ".zip", dir)

        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, content) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }

        return file
    }

    /** The runtime UIManager launches is `libraries/bun-v1.4.2`; the test points it at the Bun on this machine. */
    private fun installBun() {
        val bun = listOf(System.getenv("BUN_BIN"), System.getProperty("user.home") + "/.bun/bin/bun")
            .filterNotNull()
            .map(::File)
            .firstOrNull { it.canExecute() }

        assumeTrue(bun != null, "no Bun runtime on this machine")

        val libraries = File(dir, "libraries").apply { mkdirs() }

        Files.createSymbolicLink(File(libraries, "bun-v1.4.2").toPath(), bun!!.toPath())
        assertNotNull(File(libraries, "bun-v1.4.2"))
    }
}
