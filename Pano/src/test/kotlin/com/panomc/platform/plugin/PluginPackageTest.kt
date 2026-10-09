package com.panomc.platform.plugin

import com.panomc.platform.Main
import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.PluginPackage
import com.panomc.platform.PluginUiManager
import com.panomc.platform.ReleaseStage
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.model.Api
import com.panomc.platform.route.api.plugins.GetPluginPackagesAPI
import com.panomc.platform.route.api.plugins.GetPluginUiFileAPI
import com.panomc.platform.route.api.plugins.GetPluginUiZipAPI
import com.panomc.platform.util.DevMode
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pf4j.AbstractPluginManager
import org.pf4j.DefaultPluginDescriptor
import org.pf4j.PluginState
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** PF-14: package files over the API, the plugin namespace and Development Mode (doc 02 §6, §8; doc 01 §1). */
class PluginPackageTest {
    @TempDir
    lateinit var tempDir: Path

    private class TestPlugin : PanoPlugin()

    private lateinit var vertx: Vertx
    private lateinit var panoManager: PluginManager
    private val loaders = mutableListOf<URLClassLoader>()

    private data class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun config(developmentMode: Boolean) =
        PanoConfig(1, developmentMode = developmentMode, releaseChannel = ReleaseStage.ALPHA)

    private fun manager(
        developmentMode: Boolean = false,
        environment: Main.Companion.EnvironmentType = Main.Companion.EnvironmentType.RELEASE,
        sourceDir: (String) -> File? = { null }
    ) = PluginUiManager(
        configProvider = { config(developmentMode) },
        uiSourceDir = sourceDir,
        environment = { environment }
    )

    private val manifestOf = { id: String, ns: String? ->
        JsonObject().put("format", 1).put("pluginId", id).also { if (ns != null) it.put("namespace", ns) }.encode()
    }

    private fun uiZip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()

        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }

        return out.toByteArray()
    }

    private fun fixtureEntries(id: String, namespace: String? = null) = mapOf(
        "pano-plugin.json" to manifestOf(id, namespace),
        "contract/views.json" to """{"ProductCard":{"contract":1}}""",
        "contract/src/ProductCard.svelte" to "<div class=\"market-product-card\">hi</div>",
        "contract/src/_lib/sale.js" to "export const sale = 1",
        "controllers/controllers.mjs" to "export const create = () => ({})",
        "samples/samples.mjs" to "export const samples = {}",
        "widgets/goal.js" to "customElements",
        "client/entry.js" to "export default 1",
        "server/entry.mjs" to "export default 2"
    )

    private fun classLoader(name: String, zip: ByteArray?): ClassLoader {
        val jar = tempDir.resolve("$name.jar").toFile()

        ZipOutputStream(jar.outputStream()).use { out ->
            if (zip != null) {
                out.putNextEntry(ZipEntry("plugin-ui.zip"))
                out.write(zip)
                out.closeEntry()
            }
            out.putNextEntry(ZipEntry("marker.txt"))
            out.write(name.toByteArray())
            out.closeEntry()
        }

        return URLClassLoader(arrayOf(jar.toURI().toURL()), null).also { loaders.add(it) }
    }

    /** Registers a plugin with a built package, started in the fixture [PluginManager]. */
    private fun register(
        ui: PluginUiManager,
        id: String,
        namespace: String? = null,
        state: PluginState = PluginState.STARTED
    ): PanoPlugin {
        val plugin = TestPlugin().also { it.pluginId = id }

        ui.calculatePluginUiHash(plugin, classLoader(id, uiZip(fixtureEntries(id, namespace))), isDevelopment = false)
        start(id, state)

        return plugin
    }

    @Suppress("UNCHECKED_CAST")
    private fun start(id: String, state: PluginState) {
        val wrapper = PanoPluginWrapper(
            panoManager,
            DefaultPluginDescriptor(id, "", "", "1.0.0", "", "", ""),
            tempDir.resolve("$id.jar"),
            javaClass.classLoader
        )
        wrapper.pluginState = state

        val field = AbstractPluginManager::class.java.getDeclaredField("plugins").also { it.isAccessible = true }
        (field.get(panoManager) as MutableMap<String, org.pf4j.PluginWrapper>)[id] = wrapper
    }

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        panoManager = PluginManager(listOf(tempDir.resolve("plugins")))
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
        loaders.forEach { it.close() }
    }

    private fun <T> io.vertx.core.Future<T>.blockingGet(): T =
        toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private fun serve(ui: PluginUiManager): Int {
        val file = GetPluginUiFileAPI(ui, panoManager)
        val packages = GetPluginPackagesAPI(ui, panoManager)
        val router = Router.router(vertx)

        // The setup / maintenance checks need the running application; the handlers under test do not.
        router.route().handler { it.put(Api.BEFORE_HANDLE_DONE, true); it.next() }
        router.route(HttpMethod.GET, "/plugins/:pluginId/_/ui/*").handler(file.getHandler()).failureHandler(file.getFailureHandler())
        router.route(HttpMethod.GET, "/plugin-packages").handler(packages.getHandler()).failureHandler(packages.getFailureHandler())

        return vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private fun get(port: Int, path: String, headers: Map<String, String> = emptyMap()): Reply {
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))

        return client.request(HttpMethod.GET, port, "127.0.0.1", path).compose { request ->
            headers.forEach { (k, v) -> request.putHeader(k, v) }
            request.send()
        }.compose { response ->
            response.body().map {
                Reply(
                    response.statusCode(),
                    response.headers().associate { e -> e.key.lowercase() to e.value },
                    it.toString()
                )
            }
        }.blockingGet()
    }

    private fun assertNotFoundEnvelope(reply: Reply, what: String) {
        assertEquals(404, reply.status, what)
        assertEquals("NOT_FOUND", JsonObject(reply.body).getJsonObject("error").getString("code"), what)
    }

    // ---- package files ------------------------------------------------------------------------

    @Test
    fun `serves the package entries with the content type, nosniff, the uiHash as ETag and no CORS header`() {
        val ui = manager()
        val plugin = register(ui, "pano-plugin-market")
        val hash = ui.getRegisteredPlugin(plugin)!!
        val port = serve(ui)
        val base = "/plugins/pano-plugin-market/_/ui/"

        val cases = mapOf(
            "pano-plugin.json" to "application/json",
            "contract/views.json" to "application/json",
            "contract/src/ProductCard.svelte" to "text/plain; charset=utf-8",
            "contract/src/_lib/sale.js" to "text/javascript",
            "controllers/controllers.mjs" to "text/javascript",
            "samples/samples.mjs" to "text/javascript",
            "widgets/goal.js" to "text/javascript"
        )

        cases.forEach { (entry, type) ->
            val reply = get(port, base + entry, mapOf("Origin" to "http://localhost:3000"))

            assertEquals(200, reply.status, entry)
            assertEquals(type, reply.headers["content-type"], entry)
            assertEquals("nosniff", reply.headers["x-content-type-options"], entry)
            assertEquals("\"$hash\"", reply.headers["etag"], entry)
            assertEquals("no-cache", reply.headers["cache-control"], entry)
            assertNull(reply.headers["access-control-allow-origin"], "$entry must not set CORS itself")
        }

        assertEquals("export const create = () => ({})", get(port, base + "controllers/controllers.mjs").body)
        assertEquals("pano-plugin-market", JsonObject(get(port, base + "pano-plugin.json").body).getString("pluginId"))
    }

    @Test
    fun `a matching If-None-Match answers 304 with the validators`() {
        val ui = manager()
        val plugin = register(ui, "pano-plugin-market")
        val etag = "\"${ui.getRegisteredPlugin(plugin)}\""
        val port = serve(ui)

        val reply = get(port, "/plugins/pano-plugin-market/_/ui/contract/views.json", mapOf("If-None-Match" to etag))

        assertEquals(304, reply.status)
        assertEquals(etag, reply.headers["etag"])
        assertEquals("nosniff", reply.headers["x-content-type-options"])
        assertEquals("", reply.body)
    }

    @Test
    fun `client, server, unknown entries, directories and unknown or stopped plugins answer 404 in the envelope`() {
        val ui = manager()
        register(ui, "pano-plugin-market")
        register(ui, "pano-plugin-stopped", state = PluginState.STOPPED)
        val port = serve(ui)
        val base = "/plugins/pano-plugin-market/_/ui/"

        listOf(
            "client/entry.js", "server/entry.mjs", "plugin-ui.zip", "contract", "contract/", "contract/missing.json",
            "controllers", "other.json", "Pano-plugin.json", "pano-plugin.json/x"
        ).forEach { assertNotFoundEnvelope(get(port, base + it), it) }

        assertNotFoundEnvelope(get(port, "/plugins/unknown/_/ui/pano-plugin.json"), "unknown plugin")
        assertNotFoundEnvelope(get(port, "/plugins/pano-plugin-stopped/_/ui/pano-plugin.json"), "stopped plugin")
    }

    @Test
    fun `traversal never reaches client, server or a file outside the package`() {
        val ui = manager()
        register(ui, "pano-plugin-market")
        val port = serve(ui)
        val base = "/plugins/pano-plugin-market/_/ui/"

        listOf(
            "contract/../client/entry.js",
            "contract%2f..%2fclient%2fentry.js",
            "contract/%2e%2e/client/entry.js",
            "contract/%2e%2e%2fclient%2fentry.js",
            "contract%2F..%2F..%2F..%2Fetc%2Fpasswd",
            "contract/..%5cclient%5centry.js",
            "contract/./views.json/../../server/entry.mjs",
            "..%2f..%2fpano-plugin.json",
            "contract/views.json%00.png"
        ).forEach { entry ->
            val reply = get(port, base + entry)

            assertTrue(reply.status == 404 || reply.status == 400, "$entry answered ${reply.status}")
            assertFalse(reply.body.contains("export default"), entry)
            assertFalse(reply.body.contains("root:"), entry)
        }
    }

    @Test
    fun `normalize and isServable follow the allow-list`() {
        assertEquals("contract/views.json", PluginPackage.normalize("contract/views.json"))
        listOf("", "/x", "a//b", "a/./b", "a/../b", "..", "a\\b", "a\u0000b", "a/").forEach {
            assertNull(PluginPackage.normalize(it), it)
        }
        assertNull(PluginPackage.normalize(null))

        listOf("pano-plugin.json", "contract/a", "controllers/a/b.mjs", "samples/x", "widgets/y").forEach {
            assertTrue(PluginPackage.isServable(it), it)
        }
        listOf("contract", "client/a.js", "server/a.mjs", "plugin-ui.zip", "src/a", "pano-plugin.json/a", "x/contract/a").forEach {
            assertFalse(PluginPackage.isServable(it), it)
        }
    }

    @Test
    fun `plugin-packages lists the manifest of every served plugin`() {
        val ui = manager()
        register(ui, "pano-plugin-market")
        register(ui, "pano-plugin-wiki", namespace = "docs")
        register(ui, "pano-plugin-stopped", state = PluginState.STOPPED)
        val noManifest = TestPlugin().also { it.pluginId = "pano-plugin-plain" }
        ui.calculatePluginUiHash(noManifest, classLoader("plain", uiZip(mapOf("client/entry.js" to "x"))), isDevelopment = false)
        start("pano-plugin-plain", PluginState.STARTED)
        val port = serve(ui)

        val reply = get(port, "/plugin-packages")
        val plugins = JsonObject(reply.body).getJsonObject("plugins")

        assertEquals(200, reply.status)
        assertEquals(setOf("pano-plugin-market", "pano-plugin-wiki"), plugins.fieldNames())
        assertEquals("docs", plugins.getJsonObject("pano-plugin-wiki").getString("namespace"))
        assertNull(reply.headers["access-control-allow-origin"])
    }

    // ---- namespace ----------------------------------------------------------------------------

    @Test
    fun `namespace is the written one, else the id minus a leading pano-plugin-`() {
        assertEquals("market", PluginNamespace.fromId("pano-plugin-market"))
        assertEquals("pano-plugin-x", PluginNamespace.fromId("pano-plugin-pano-plugin-x"))
        assertEquals("my-market-pano-plugin-", PluginNamespace.fromId("my-market-pano-plugin-"))
        assertEquals("shop", PluginNamespace.fromId("shop"))
        assertEquals("pano-plugin-", PluginNamespace.fromId("pano-plugin-"))
        assertEquals("docs", PluginNamespace.of("pano-plugin-wiki", "docs"))
        assertEquals("wiki", PluginNamespace.of("pano-plugin-wiki", null))
        assertEquals("wiki", PluginNamespace.of("pano-plugin-wiki", "  "))
        assertEquals("wiki", PluginNamespace.ofManifest("pano-plugin-wiki", JsonObject().put("namespace", 5)))
    }

    @Test
    fun `of(plugin) reads the written namespace from the package`() {
        val ui = manager()
        val written = register(ui, "pano-plugin-wiki", namespace = "docs").also { it.pluginUiManager = ui }
        val derived = register(ui, "pano-plugin-market").also { it.pluginUiManager = ui }

        assertEquals("docs", PluginNamespace.of(written))
        assertEquals("market", PluginNamespace.of(derived))
    }

    // ---- clash --------------------------------------------------------------------------------

    @Test
    fun `on boot the smaller plugin id keeps the namespace and the other is left out of site info`() {
        val ui = manager()
        register(ui, "pano-plugin-market")
        register(ui, "market")
        register(ui, "pano-plugin-wiki")

        val served = ui.getActiveRegisteredPlugins(panoManager).map { it.first.pluginId }.sorted()
        val clashes = ui.getNamespaceClashes(panoManager)

        assertEquals(listOf("market", "pano-plugin-wiki"), served)
        assertEquals(listOf(NamespaceClashView("market", "pano-plugin-market", "market")), clashes.map(::NamespaceClashView))
    }

    private data class NamespaceClashView(val namespace: String, val pluginId: String, val heldBy: String) {
        constructor(c: com.panomc.platform.NamespaceClash) : this(c.namespace, c.pluginId, c.heldBy)
    }

    @Test
    fun `after boot the plugin enabled first keeps the namespace, whatever its id`() {
        val ui = manager()
        register(ui, "zzz-plugin", namespace = "market")
        assertEquals(listOf("zzz-plugin"), ui.getActiveRegisteredPlugins(panoManager).map { it.first.pluginId })

        // Enabled later, with the smaller id: still second.
        register(ui, "aaa-plugin", namespace = "market")

        assertEquals(listOf("zzz-plugin"), ui.getActiveRegisteredPlugins(panoManager).map { it.first.pluginId })
        assertEquals("aaa-plugin", ui.getNamespaceClashes(panoManager).single().pluginId)
        assertEquals("zzz-plugin", ui.getNamespaceClashes(panoManager).single().heldBy)
    }

    @Test
    fun `the loser is served again once the holder is gone, and its files answer 404 while it is left out`() {
        val ui = manager()
        val holder = register(ui, "market")
        register(ui, "pano-plugin-market")
        val port = serve(ui)

        assertEquals(200, get(port, "/plugins/market/_/ui/pano-plugin.json").status)
        assertNotFoundEnvelope(get(port, "/plugins/pano-plugin-market/_/ui/pano-plugin.json"), "loser")

        ui.unRegisterPlugin(holder)

        assertEquals(200, get(port, "/plugins/pano-plugin-market/_/ui/pano-plugin.json").status)
        assertTrue(ui.getNamespaceClashes(panoManager).isEmpty())
    }

    @Test
    fun `a stopped plugin does not hold a namespace`() {
        val ui = manager()
        register(ui, "market", state = PluginState.FAILED)
        register(ui, "pano-plugin-market")

        assertEquals(listOf("pano-plugin-market"), ui.getActiveRegisteredPlugins(panoManager).map { it.first.pluginId })
        assertTrue(ui.getNamespaceClashes(panoManager).isEmpty())
    }

    // ---- Development Mode ---------------------------------------------------------------------

    @Test
    fun `DevMode is the dev environment or the panel switch`() {
        val dev = Main.Companion.EnvironmentType.DEVELOPMENT
        val release = Main.Companion.EnvironmentType.RELEASE

        assertTrue(DevMode.isActive(config(false), dev))
        assertTrue(DevMode.isActive(null, dev))
        assertTrue(DevMode.isActive(config(true), release))
        assertFalse(DevMode.isActive(config(false), release))
        assertFalse(DevMode.isActive(null, release))
    }

    @Test
    fun `release environment with developmentMode registers dev-build for a plugin with a UI source dir`() {
        val loader = classLoader("devplugin", null)
        val plugin = TestPlugin().also { it.pluginId = "devplugin" }
        val withSource = File(tempDir.toFile(), "src-devplugin").also { it.mkdirs() }

        val on = manager(developmentMode = true, sourceDir = { if (it == "devplugin") withSource else null })
        on.calculatePluginUiHash(plugin, loader, hasUiSourceDir = { true })
        assertEquals("dev-build", on.getRegisteredPlugin(plugin))

        val off = manager(developmentMode = false)
        off.calculatePluginUiHash(plugin, loader, hasUiSourceDir = { true })
        assertNull(off.getRegisteredPlugin(plugin))

        val noSource = manager(developmentMode = true)
        noSource.calculatePluginUiHash(plugin, loader, hasUiSourceDir = { false })
        assertNull(noSource.getRegisteredPlugin(plugin))
    }

    @Test
    fun `with Development Mode package files are served from the source folder with a content ETag`() {
        val sourceDir = tempDir.resolve("plugin-ui").toFile().also { it.mkdirs() }
        File(sourceDir, "pano-plugin.json").writeText(manifestOf("devplugin", "dev"))
        File(sourceDir, "contract").mkdirs()
        File(sourceDir, "contract/views.json").writeText("""{"a":1}""")
        File(sourceDir, "client").mkdirs()
        File(sourceDir, "client/entry.js").writeText("secret")
        File(tempDir.toFile(), "outside.json").writeText("""{"outside":true}""")
        Files.createSymbolicLink(File(sourceDir, "contract/link.json").toPath(), File(tempDir.toFile(), "outside.json").toPath())

        val ui = manager(developmentMode = true, sourceDir = { if (it == "devplugin") sourceDir else null })
        val plugin = TestPlugin().also { it.pluginId = "devplugin" }
        ui.calculatePluginUiHash(plugin, classLoader("devplugin", null), hasUiSourceDir = { true })
        start("devplugin", PluginState.STARTED)
        val port = serve(ui)
        val base = "/plugins/devplugin/_/ui/"

        val first = get(port, base + "contract/views.json")
        assertEquals(200, first.status)
        assertEquals("""{"a":1}""", first.body)
        assertNotNull(first.headers["etag"])
        assertFalse(first.headers["etag"]!!.contains("dev-build"))

        File(sourceDir, "contract/views.json").writeText("""{"a":2}""")
        val second = get(port, base + "contract/views.json", mapOf("If-None-Match" to first.headers["etag"]!!))
        assertEquals(200, second.status)
        assertEquals("""{"a":2}""", second.body)

        assertEquals("dev", JsonObject(get(port, base + "pano-plugin.json").body).getString("namespace"))
        assertNotFoundEnvelope(get(port, base + "client/entry.js"), "client")
        assertNotFoundEnvelope(get(port, base + "contract/link.json"), "symlink out of the folder")
        assertEquals("dev", PluginNamespace.ofManifest("devplugin", ui.manifest(plugin)))
    }

    @Test
    fun `without Development Mode the source folder is ignored`() {
        val sourceDir = tempDir.resolve("plugin-ui").toFile().also { it.mkdirs() }
        File(sourceDir, "pano-plugin.json").writeText("""{"from":"source"}""")

        val ui = manager(developmentMode = false, sourceDir = { sourceDir })
        register(ui, "pano-plugin-market")
        val port = serve(ui)

        assertEquals("pano-plugin-market", JsonObject(get(port, "/plugins/pano-plugin-market/_/ui/pano-plugin.json").body).getString("pluginId"))
    }

    // ---- dev zip ------------------------------------------------------------------------------

    private fun zipNames(bytes: ByteArray): Set<String> {
        val names = mutableSetOf<String>()

        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                names.add((zip.nextEntry ?: break).name)
            }
        }

        return names
    }

    @Test
    fun `the dev zip packs the package files next to the manifest-listed client and server files`() {
        val dir = tempDir.resolve("ui").toFile().also { it.mkdirs() }
        fun write(path: String, text: String = "x") = File(dir, path).also { it.parentFile.mkdirs() }.writeText(text)

        write("client/manifest.json", """["entry.js"]""")
        write("client/entry.js")
        write("client/unlisted.js")
        write("server/manifest.json", """["entry.mjs"]""")
        write("server/entry.mjs")
        write("pano-plugin.json", "{}")
        write("contract/views.json")
        write("contract/src/_lib/sale.js")
        write("controllers/controllers.mjs")
        write("samples/samples.mjs")
        write("widgets/goal.js")
        write("stray.txt")

        val names = zipNames(GetPluginUiZipAPI.devZipBytes("p", dir, LoggerFactory.getLogger("test")))

        assertEquals(
            setOf(
                "client/entry.js", "server/entry.mjs", "pano-plugin.json", "contract/views.json",
                "contract/src/_lib/sale.js", "controllers/controllers.mjs", "samples/samples.mjs", "widgets/goal.js"
            ),
            names
        )
    }

    @Test
    fun `the dev zip of a folder without manifests is the whole folder`() {
        val dir = tempDir.resolve("ui2").toFile().also { it.mkdirs() }
        File(dir, "contract").mkdirs()
        File(dir, "contract/views.json").writeText("{}")
        File(dir, "pano-plugin.json").writeText("{}")

        assertEquals(
            setOf("contract/views.json", "pano-plugin.json"),
            zipNames(GetPluginUiZipAPI.devZipBytes("p", dir, LoggerFactory.getLogger("test")))
        )
    }
}
