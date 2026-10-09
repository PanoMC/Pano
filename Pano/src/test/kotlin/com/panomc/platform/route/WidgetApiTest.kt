package com.panomc.platform.route

import com.panomc.platform.Main
import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.PluginUiManager
import com.panomc.platform.ReleaseStage
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.model.Api
import com.panomc.platform.route.api.widget.GetWidgetIndexAPI
import com.panomc.platform.route.api.widget.GetWidgetLoaderAPI
import com.panomc.platform.route.api.widget.GetWidgetRuntimeFileAPI
import com.panomc.platform.ui.WidgetRuntime
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import org.junit.jupiter.api.AfterEach
import com.panomc.platform.PluginPackage
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** PF-28: the widget endpoints and the runtime lookup (doc 06 section 3.3). */
class WidgetApiTest {
    @TempDir
    lateinit var tempDir: Path

    private class TestPlugin : PanoPlugin()

    private lateinit var vertx: Vertx
    private lateinit var panoManager: PluginManager
    private val loaders = mutableListOf<URLClassLoader>()

    private data class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private val svelte = "5.55.9"
    private val chunk = "chunks/chunk-9EZydhXt.js"

    private val runtimeFiles = mapOf(
        "loader.js" to "export const loader = 1",
        "host/index.js" to "export const host = 1",
        chunk to "export const shared = 1",
        "css/pano-fallback-icons.css" to ".fa{}",
        "webfonts/fa-solid-900.woff2" to "font-bytes",
        "svelte/index.js" to "export const s = 1"
    )

    private fun runtimeJson(hash: String = "5de9949dc45bc067") = JsonObject()
        .put("format", 1)
        .put("svelte", svelte)
        .put("hash", hash)
        .put("files", JsonArray(runtimeFiles.keys.toList()))
        .encode()

    private fun writeRuntimeFolder(name: String = "widget-runtime", hash: String = "5de9949dc45bc067"): File {
        val dir = tempDir.resolve(name).toFile()

        (runtimeFiles + ("runtime.json" to runtimeJson(hash))).forEach { (entry, content) ->
            File(dir, entry).also { it.parentFile.mkdirs() }.writeText(content)
        }

        // Present on disk, absent from runtime.json: must not be served.
        File(dir, "secret.txt").writeText("not listed")

        return dir
    }

    private fun runtimeZip(): ByteArray {
        val out = ByteArrayOutputStream()

        ZipOutputStream(out).use { zip ->
            (runtimeFiles + ("runtime.json" to runtimeJson("zip-hash"))).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }

        return out.toByteArray()
    }

    private fun runtime(folder: File? = null, zip: ByteArray? = null) =
        WidgetRuntime({ folder }, { zip?.let { ByteArrayInputStream(it) } })

    private fun manager() = PluginUiManager(
        configProvider = { PanoConfig(1, developmentMode = false, releaseChannel = ReleaseStage.ALPHA) },
        uiSourceDir = { null },
        environment = { Main.Companion.EnvironmentType.RELEASE }
    )

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

    private fun widgetsJson(pin: String, vararg tags: String) = JsonObject()
        .put("format", 1)
        .put("svelte", pin)
        .put(
            "widgets",
            JsonArray(
                tags.map {
                    JsonObject().put("view", "${it.replaceFirstChar(Char::uppercase)}Widget").put("tag", it)
                        .put("module", "${it.replaceFirstChar(Char::uppercase)}Widget-abc123.js")
                        .put("attrs", JsonArray().add("limit")).put("session", if (it == "goal") "optional" else "none")
                }
            )
        ).encode()

    private fun packageEntries(
        id: String,
        pin: String,
        tags: List<String> = listOf("goal"),
        namespace: String? = null,
        icons: Boolean = false,
        withStyles: Boolean = true,
        controllers: Boolean = true,
        ownSheet: Boolean = false
    ): Map<String, String> {
        val manifest = JsonObject().put("format", 1).put("pluginId", id)

        namespace?.let { manifest.put("namespace", it) }
        if (controllers) manifest.put("controllers", "contract/controllers.json")
        if (withStyles) manifest.put("styles", JsonObject().put("fallback", "client/fallback.css").put("hash", "9f2c1a7e").put("icons", icons))

        val files = mapOf(
            "pano-plugin.json" to manifest.encode(),
            "widgets/widgets.json" to widgetsJson(pin, *tags.toTypedArray()),
            "widgets/GoalWidget-abc123.js" to "export default {}"
        )

        return if (ownSheet) files + mapOf("client/plugin.css" to ":host{display:block}", "client/other.css" to "x") else files
    }

    private fun classLoader(name: String, zip: ByteArray): ClassLoader {
        val jar = tempDir.resolve("$name.jar").toFile()

        ZipOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("plugin-ui.zip"))
            out.write(zip)
            out.closeEntry()
        }

        return URLClassLoader(arrayOf(jar.toURI().toURL()), null).also { loaders.add(it) }
    }

    private fun register(
        ui: PluginUiManager,
        id: String,
        entries: Map<String, String>,
        state: PluginState = PluginState.STARTED
    ): TestPlugin {
        val plugin = TestPlugin().also { it.pluginId = id }

        ui.calculatePluginUiHash(plugin, classLoader(id, uiZip(entries)), isDevelopment = false)
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

    private fun serve(runtime: WidgetRuntime, ui: PluginUiManager = manager()): Int {
        val loader = GetWidgetLoaderAPI(runtime)
        val files = GetWidgetRuntimeFileAPI(runtime)
        val index = GetWidgetIndexAPI(runtime, ui, panoManager) {
            mapOf("name" to "Test Site", "url" to "https://site.example", "locale" to "en-US")
        }
        val router = Router.router(vertx)

        // The setup / maintenance checks need the running application; the handlers under test do not.
        router.route().handler { it.put(Api.BEFORE_HANDLE_DONE, true); it.next() }
        router.route(HttpMethod.GET, "/widgets/loader.js").handler(loader.getHandler()).failureHandler(loader.getFailureHandler())
        router.route(HttpMethod.GET, "/widgets/runtime/*").handler(files.getHandler()).failureHandler(files.getFailureHandler())
        router.route(HttpMethod.GET, "/widgets/index.json").handler(index.getHandler()).failureHandler(index.getFailureHandler())

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
        assertTrue(reply.body.startsWith("{"), "$what answered '${reply.body}'")
        assertEquals("NOT_FOUND", JsonObject(reply.body).getJsonObject("error").getString("code"), what)
    }

    // ---- the index -----------------------------------------------------------------------------

    @Test
    fun `index lists only active plugins whose widgets are built for the runtime's svelte`() {
        val ui = manager()

        register(ui, "pano-plugin-market", packageEntries("pano-plugin-market", svelte, listOf("goal", "stats"), icons = true))
        register(ui, "pano-plugin-stopped", packageEntries("pano-plugin-stopped", svelte), PluginState.STOPPED)
        register(ui, "pano-plugin-old", packageEntries("pano-plugin-old", "5.1.0"))
        register(ui, "pano-plugin-nowidgets", mapOf("pano-plugin.json" to """{"format":1}"""))

        val reply = get(serve(runtime(writeRuntimeFolder()), ui), "/widgets/index.json")

        assertEquals(200, reply.status)
        assertEquals("application/json", reply.headers["content-type"])
        assertEquals("public, max-age=300", reply.headers["cache-control"])

        val index = JsonObject(reply.body)

        assertEquals(JsonObject().put("svelte", svelte).put("hash", "5de9949dc45bc067"), index.getJsonObject("runtime"))
        assertEquals(JsonObject().put("name", "Test Site").put("url", "https://site.example").put("locale", "en-US"), index.getJsonObject("site"))
        assertEquals(setOf("pano-market-goal", "pano-market-stats"), index.getJsonObject("widgets").fieldNames())

        val goal = index.getJsonObject("widgets").getJsonObject("pano-market-goal")

        assertEquals("pano-plugin-market", goal.getString("pluginId"))
        assertEquals("market", goal.getString("ns"))
        assertEquals("GoalWidget-abc123.js", goal.getString("module"))
        assertEquals(JsonArray().add("limit"), goal.getJsonArray("attrs"))
        assertEquals("optional", goal.getString("session"))
        assertEquals(true, goal.getBoolean("controllers"))
        assertEquals("client/fallback.css?v=9f2c1a7e", goal.getJsonObject("styles").getString("fallback"))
        assertEquals("9f2c1a7e", goal.getJsonObject("styles").getString("hash"))
        assertEquals("../../../../widgets/runtime/css/pano-fallback-icons.css", goal.getJsonObject("styles").getString("icons"))
        assertEquals("none", index.getJsonObject("widgets").getJsonObject("pano-market-stats").getString("session"))
    }

    @Test
    fun `index carries styles own when the package has client plugin css, and only that sheet of client is served`() {
        val ui = manager()
        val plugin = register(ui, "pano-plugin-market", packageEntries("pano-plugin-market", svelte, ownSheet = true))

        val goal = JsonObject(get(serve(runtime(writeRuntimeFolder()), ui), "/widgets/index.json").body)
            .getJsonObject("widgets").getJsonObject("pano-market-goal")

        assertEquals("client/plugin.css", goal.getJsonObject("styles").getString("own"))
        assertNotNull(ui.readPackageEntry(plugin, "client/plugin.css"))
        assertNull(ui.readPackageEntry(plugin, "client/other.css"))
        assertTrue(PluginPackage.isServable("client/fallback.css"))
        assertFalse(PluginPackage.isServable("client/fallback.css.map"))
        assertFalse(PluginPackage.isServable("client/sub/plugin.css"))

        val without = manager()

        register(without, "pano-plugin-market", packageEntries("pano-plugin-market", svelte))

        val plain = JsonObject(get(serve(runtime(writeRuntimeFolder()), without), "/widgets/index.json").body)
            .getJsonObject("widgets").getJsonObject("pano-market-goal")

        assertFalse(plain.getJsonObject("styles").containsKey("own"))
    }

    @Test
    fun `index uses the written namespace, leaves out a clashing plugin and a package without styles has no style urls`() {
        val ui = manager()

        register(ui, "pano-plugin-shop", packageEntries("pano-plugin-shop", svelte, namespace = "market", withStyles = false, controllers = false))
        register(ui, "pano-plugin-market", packageEntries("pano-plugin-market", svelte))

        val widgets = JsonObject(get(serve(runtime(writeRuntimeFolder()), ui), "/widgets/index.json").body).getJsonObject("widgets")

        // Both want "market"; the smaller plugin id wins (doc 01), the other one is absent.
        assertEquals(setOf("pano-market-goal"), widgets.fieldNames())
        assertEquals("pano-plugin-market", widgets.getJsonObject("pano-market-goal").getString("pluginId"))

        val alone = manager()

        register(alone, "pano-plugin-shop", packageEntries("pano-plugin-shop", svelte, namespace = "market", withStyles = false, controllers = false))

        val goal = JsonObject(get(serve(runtime(writeRuntimeFolder()), alone), "/widgets/index.json").body)
            .getJsonObject("widgets").getJsonObject("pano-market-goal")

        assertEquals("pano-plugin-shop", goal.getString("pluginId"))
        assertEquals("market", goal.getString("ns"))
        assertEquals(false, goal.getBoolean("controllers"))
        assertEquals(JsonObject(), goal.getJsonObject("styles"))
    }

    @Test
    fun `an unusable widget entry is left out without hiding the others`() {
        val ui = manager()
        val entries = packageEntries("pano-plugin-market", svelte).toMutableMap()
        val broken = JsonObject(entries.getValue("widgets/widgets.json"))

        broken.getJsonArray("widgets")
            .add(JsonObject().put("tag", "Bad Tag").put("module", "x.js"))
            .add(JsonObject().put("tag", "evil").put("module", "../../x.js"))

        entries["widgets/widgets.json"] = broken.encode()
        register(ui, "pano-plugin-market", entries)

        val widgets = JsonObject(get(serve(runtime(writeRuntimeFolder()), ui), "/widgets/index.json").body).getJsonObject("widgets")

        assertEquals(setOf("pano-market-goal"), widgets.fieldNames())
    }

    @Test
    fun `index is built from the classpath zip when there is no folder`() {
        val ui = manager()

        register(ui, "pano-plugin-market", packageEntries("pano-plugin-market", svelte))

        val index = JsonObject(get(serve(runtime(zip = runtimeZip()), ui), "/widgets/index.json").body)

        assertEquals("zip-hash", index.getJsonObject("runtime").getString("hash"))
        assertEquals(setOf("pano-market-goal"), index.getJsonObject("widgets").fieldNames())
    }

    // ---- the runtime files ---------------------------------------------------------------------

    @Test
    fun `the runtime is served from the folder with its types and cache rules`() {
        val port = serve(runtime(writeRuntimeFolder()))

        val loader = get(port, "/widgets/loader.js")

        assertEquals(200, loader.status)
        assertEquals("export const loader = 1", loader.body)
        assertEquals("text/javascript; charset=utf-8", loader.headers["content-type"])
        assertEquals("public, max-age=300", loader.headers["cache-control"])
        assertEquals("nosniff", loader.headers["x-content-type-options"])
        assertEquals("\"5de9949dc45bc067\"", loader.headers["etag"])

        // The loader also answers under runtime/, like the build lays it out.
        assertEquals("export const loader = 1", get(port, "/widgets/runtime/loader.js").body)

        val hashed = get(port, "/widgets/runtime/$chunk")

        assertEquals(200, hashed.status)
        assertEquals("export const shared = 1", hashed.body)
        assertEquals("public, max-age=31536000, immutable", hashed.headers["cache-control"])

        val stable = get(port, "/widgets/runtime/host/index.js")

        assertEquals("export const host = 1", stable.body)
        assertEquals("public, max-age=300", stable.headers["cache-control"])

        assertEquals("text/css; charset=utf-8", get(port, "/widgets/runtime/css/pano-fallback-icons.css").headers["content-type"])
        assertEquals("font/woff2", get(port, "/widgets/runtime/webfonts/fa-solid-900.woff2").headers["content-type"])
        assertEquals("application/json", get(port, "/widgets/runtime/runtime.json").headers["content-type"])
        assertNull(stable.headers["access-control-allow-origin"], "CORS is the access plane's job")
    }

    @Test
    fun `a matching If-None-Match answers 304`() {
        val port = serve(runtime(writeRuntimeFolder()))
        val reply = get(port, "/widgets/runtime/host/index.js", mapOf("If-None-Match" to "\"5de9949dc45bc067\""))

        assertEquals(304, reply.status)
        assertEquals("", reply.body)
        assertEquals("\"5de9949dc45bc067\"", reply.headers["etag"])
    }

    @Test
    fun `the runtime is served from the classpath zip when the folder does not exist`() {
        val port = serve(runtime(folder = tempDir.resolve("nothing-here").toFile(), zip = runtimeZip()))

        assertEquals("export const loader = 1", get(port, "/widgets/loader.js").body)
        assertEquals("export const shared = 1", get(port, "/widgets/runtime/$chunk").body)
        assertEquals("\"zip-hash\"", get(port, "/widgets/loader.js").headers["etag"])
    }

    @Test
    fun `the folder wins over the classpath zip`() {
        val port = serve(runtime(writeRuntimeFolder(hash = "folder-hash"), runtimeZip()))

        assertEquals("\"folder-hash\"", get(port, "/widgets/loader.js").headers["etag"])
    }

    @Test
    fun `without a runtime all three endpoints answer 404 in the envelope`() {
        val ui = manager()

        register(ui, "pano-plugin-market", packageEntries("pano-plugin-market", svelte))

        val port = serve(runtime(folder = tempDir.resolve("nothing-here").toFile(), zip = null), ui)

        assertNotFoundEnvelope(get(port, "/widgets/loader.js"), "loader")
        assertNotFoundEnvelope(get(port, "/widgets/runtime/host/index.js"), "runtime file")
        assertNotFoundEnvelope(get(port, "/widgets/index.json"), "index")
    }

    @Test
    fun `a folder without a usable runtime json is no runtime`() {
        val dir = writeRuntimeFolder()

        File(dir, "runtime.json").writeText("""{"format":1}""")

        val port = serve(runtime(dir))

        assertNotFoundEnvelope(get(port, "/widgets/loader.js"), "no svelte, no hash")

        File(dir, "runtime.json").writeText("not json")

        assertNotFoundEnvelope(get(port, "/widgets/index.json"), "broken json")
    }

    @Test
    fun `unlisted files, traversal and directories answer 404`() {
        val dir = writeRuntimeFolder()

        File(tempDir.toFile(), "outside.js").writeText("outside")

        val port = serve(runtime(dir))

        listOf("secret.txt", "host", "host/", "chunks/missing.js", "", "host/..%2f..%2foutside.js")
            .forEach { assertNotFoundEnvelope(get(port, "/widgets/runtime/$it"), "'$it'") }

        // The router resolves dot segments before the handler; whatever it makes of them, the file is not served.
        listOf("../outside.js", "%2e%2e/outside.js", "host/../../outside.js").forEach {
            val reply = get(port, "/widgets/runtime/$it")

            assertEquals(404, reply.status, "'$it'")
            assertFalse(reply.body.contains("outside"), "'$it'")
        }
    }

    @Test
    fun `a new runtime in the folder applies without a restart`() {
        val dir = writeRuntimeFolder()
        val port = serve(runtime(dir))

        assertEquals("\"5de9949dc45bc067\"", get(port, "/widgets/loader.js").headers["etag"])

        File(dir, "runtime.json").writeText(runtimeJson("second"))
        File(dir, "loader.js").writeText("export const loader = 2")

        val reply = get(port, "/widgets/loader.js")

        assertEquals("\"second\"", reply.headers["etag"])
        assertEquals("export const loader = 2", reply.body)
        assertTrue(reply.body.isNotEmpty())
        assertFalse(reply.body.contains("= 1"))
    }
}
