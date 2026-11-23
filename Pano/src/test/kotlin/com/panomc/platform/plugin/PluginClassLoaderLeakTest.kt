package com.panomc.platform.plugin

import com.panomc.platform.Main
import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.SpringConfig
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginLifecycleListener
import com.panomc.platform.db.DBEntity
import com.panomc.platform.plugin.leakfixture.LeakFixturePlugin
import com.panomc.platform.route.RouteRemoval
import com.google.gson.Gson
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pf4j.PluginState
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * After an update of a plugin (as the panel does it: stop, disable, unload with its dependents, load the new jar,
 * enable, start, dependents loaded again) nothing keeps the old plugin objects and class loaders alive: a hosted Pano
 * ran out of memory after market updates because they were pinned (2026-11-22).
 *
 * The fixture jars carry the fixture plugin's classes, so each plugin gets its own copies, defined by its own loader.
 */
class PluginClassLoaderLeakTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var vertx: Vertx
    private lateinit var router: Router
    private var manager: PluginManager? = null
    private var previousGson: Gson? = null
    private val pluginsDir: Path get() = tempDir.resolve("plugins")

    /**
     * Mounts and drops the fixture plugins' endpoints as RouterProvider does for @Endpoint APIs (its onPluginLoad /
     * onPluginUnload): order 1 like every endpoint, in a router that already has core routes of order 1, removed with
     * [RouteRemoval]. RouterProvider itself needs the whole platform context, so it is mirrored here.
     */
    private val routeMounter = object : PluginLifecycleListener {
        val routes = ConcurrentHashMap<PanoPlugin, List<io.vertx.ext.web.Route>>()

        override suspend fun onPluginLoad(plugin: PanoPlugin) {
            @Suppress("UNCHECKED_CAST")
            val handler = plugin.javaClass.getMethod("routeHandler").invoke(plugin) as Handler<RoutingContext>

            routes[plugin] = (1..3).map { index ->
                router.route(HttpMethod.GET, "/api/plugins/${plugin.pluginId}/endpoint$index").order(1).handler(handler)
            }
        }

        override suspend fun onPluginUnload(plugin: PanoPlugin) {
            routes.remove(plugin)?.let { RouteRemoval.remove(router, it) }
        }
    }

    @BeforeEach
    fun setUp() {
        Files.createDirectories(pluginsDir)

        vertx = Vertx.vertx()
        router = Router.router(vertx)

        // Core endpoints share order 1 with the plugins' (model/Route.kt).
        (1..5).forEach { index -> router.route(HttpMethod.GET, "/api/v1/core$index").order(1).handler { it.end() } }

        previousGson = runCatching { DBEntity.gson }.getOrNull()
        DBEntity.gson = Gson()

        SpringConfig.setDefaults(vertx, LoggerFactory.getLogger("plugin-class-loader-leak-test"))
        Main.applicationContext = AnnotationConfigApplicationContext()
        PluginManager.lifecycleListeners.add(routeMounter)
    }

    @AfterEach
    fun tearDown() {
        manager?.let { manager ->
            runCatching { manager.stopPlugins() }
            for (id in manager.plugins.map { it.pluginId }) runCatching { manager.unloadPlugin(id) }
        }
        manager = null
        PluginManager.lifecycleListeners.remove(routeMounter)
        previousGson?.let { DBEntity.gson = it }
        System.gc()

        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    /** The class files of the fixture package, by their path inside a jar. */
    private fun fixtureClasses(): Map<String, ByteArray> {
        val packagePath = LeakFixturePlugin::class.java.`package`.name.replace('.', '/')
        val root = Paths.get(LeakFixturePlugin::class.java.protectionDomain.codeSource.location.toURI())
        val dir = root.resolve(packagePath)

        return dir.toFile().listFiles { file -> file.name.endsWith(".class") }!!
            .associate { file -> "$packagePath/${file.name}" to file.readBytes() }
    }

    private fun writeJar(id: String, dependencies: String? = null, version: String = "1.0.0"): Path {
        val manifest = Manifest()
        val attributes = manifest.mainAttributes

        attributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        attributes[Attributes.Name("id")] = id
        attributes[Attributes.Name("name")] = id
        attributes[Attributes.Name("description")] = "fixture"
        attributes[Attributes.Name("pano-version")] = "1.0.0"
        attributes[Attributes.Name("main-class")] = LeakFixturePlugin::class.java.name
        attributes[Attributes.Name("version")] = version
        attributes[Attributes.Name("developer")] = "test"
        attributes[Attributes.Name("license")] = "MIT"
        attributes[Attributes.Name("api-level")] = "1"

        if (dependencies != null) {
            attributes[Attributes.Name("dependencies")] = dependencies
        }

        val path = pluginsDir.resolve("$id.jar")

        JarOutputStream(Files.newOutputStream(path), manifest).use { jar ->
            for ((name, bytes) in fixtureClasses()) {
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }

        return path
    }

    /** Weak references to the class loader and the plugin object of [id] as they are now. */
    private fun PluginManager.weakRefsOf(id: String): List<Pair<String, WeakReference<Any>>> {
        val wrapper = getPlugin(id) as PanoPluginWrapper
        val plugin = wrapper.instance!!

        // The object really comes from the plugin's own loader, not from the test classpath.
        assertTrue(plugin.javaClass.classLoader === wrapper.pluginClassLoader, "$id is defined by its plugin loader")

        return listOf(
            "$id class loader" to WeakReference<Any>(wrapper.pluginClassLoader),
            "$id plugin object" to WeakReference<Any>(plugin)
        )
    }

    /** One update of [id], as InstallManager does it, with its dependents unloaded and loaded again. */
    private fun PluginManager.update(id: String, version: String) {
        val path = getPlugin(id).pluginPath

        stopPlugin(id)
        disablePlugin(id)
        assertTrue(unloadPlugin(id))

        writeJar(id, version = version)
        loadPlugin(path)
        enablePlugin(id)
        assertEquals(PluginState.STARTED, startPlugin(id))
        liftHolds()
    }

    private fun updateFiveTimes(): List<Pair<String, WeakReference<Any>>> {
        writeJar("market")
        writeJar("stripe", "market")

        val manager = PluginManager(listOf(pluginsDir)).also { this.manager = it }
        manager.loadPlugins()
        manager.startPlugins()

        val refs = mutableListOf<Pair<String, WeakReference<Any>>>()

        repeat(5) { cycle ->
            refs += manager.weakRefsOf("market")
            refs += manager.weakRefsOf("stripe")

            manager.update("market", "1.${cycle + 1}.0")

            assertEquals(PluginState.STARTED, manager.getPlugin("stripe")?.pluginState, "cycle $cycle")
            assertEquals("1.${cycle + 1}.0", manager.getPlugin("market").descriptor.version)
        }

        return refs
    }

    @Test
    fun `the class loaders and plugin objects of an updated plugin and its add-on can be collected`() {
        val refs = updateFiveTimes()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var garbage: Any? = null

        while (refs.any { it.second.get() != null } && System.nanoTime() < deadline) {
            garbage = ByteArray(1 shl 20)
            System.gc()
            Thread.sleep(50)
        }

        garbage.hashCode()

        val alive = refs.filter { it.second.get() != null }.map { it.first }

        assertEquals(emptyList<String>(), alive, "still reachable after the updates")

        // The router holds the core routes and the current plugins' routes, nothing of the old ones, in order.
        val paths: List<String?> = router.routes.map<io.vertx.ext.web.Route, String?> { it.path }
        assertEquals((1..5).map { "/api/v1/core$it" }, paths.take(5))
        assertEquals(5 + 2 * 3, paths.size, "$paths")
    }
}
