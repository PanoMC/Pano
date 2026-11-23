package com.panomc.platform.plugin

import com.panomc.platform.Main
import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginEventManager
import com.panomc.platform.PluginManager
import com.panomc.platform.SpringConfig
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginEventListener
import com.panomc.platform.api.event.PluginLifecycleListener
import com.panomc.platform.license.LicenseDeniedReason
import com.panomc.platform.license.LicenseRequiredException
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pf4j.PluginState
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * A plugin and the plugins that require it (the market and its payment / shipping add-ons) follow each other: an
 * add-on waits for a market the admin switched off and starts with it, a plugin whose license failed starts once a
 * license is there, and the add-ons unloaded with a market that is updated come back. None of this is written to
 * disabled.txt, which stays the admin's own list.
 *
 * Fixture jars carry a manifest only; the plugin class comes from the test classpath.
 */
class PluginDependencyLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    /** A plugin whose onStart fails its license while its id is in [unlicensed]. */
    class LicensedPlugin : PanoPlugin() {
        override suspend fun onStart() {
            if (pluginId in unlicensed) throw LicenseRequiredException(pluginId, LicenseDeniedReason.NOT_CONNECTED)

            // What the payment add-ons do in onStart: needs the event listener entry load() creates.
            register(object : PluginEventListener {})
            registered.merge(pluginId, 1, Int::plus)
        }

        override suspend fun onEnable() {
            enables.merge(pluginId, 1, Int::plus)
        }

        override suspend fun onDisable() {
            disables.merge(pluginId, 1, Int::plus)
        }

        companion object {
            val unlicensed: MutableSet<String> = ConcurrentHashMap.newKeySet()
            val registered = ConcurrentHashMap<String, Int>()
            val enables = ConcurrentHashMap<String, Int>()
            val disables = ConcurrentHashMap<String, Int>()
        }
    }

    /** Counts load() runs per plugin id (onPluginLoad fires once per load()). */
    private val loads = ConcurrentHashMap<String, Int>()
    private val loadCounter = object : PluginLifecycleListener {
        override suspend fun onPluginLoad(plugin: PanoPlugin) {
            loads.merge(plugin.pluginId, 1, Int::plus)
        }
    }

    private lateinit var vertx: Vertx
    private val managers = mutableListOf<PluginManager>()
    private val pluginsDir: Path get() = tempDir.resolve("plugins")

    @BeforeEach
    fun setUp() {
        Files.createDirectories(pluginsDir)
        LicensedPlugin.unlicensed.clear()
        LicensedPlugin.registered.clear()
        LicensedPlugin.enables.clear()
        LicensedPlugin.disables.clear()
        PluginManager.lifecycleListeners.add(loadCounter)

        vertx = Vertx.vertx()

        SpringConfig.setDefaults(vertx, LoggerFactory.getLogger("plugin-dependency-lifecycle-test"))
        Main.applicationContext = AnnotationConfigApplicationContext()
    }

    @AfterEach
    fun tearDown() {
        // Unloading closes the plugin class loaders: Windows keeps the fixture jars locked (and @TempDir undeletable)
        // while one is open.
        for (manager in managers) {
            runCatching { manager.stopPlugins() }
            for (id in manager.plugins.map { it.pluginId }) runCatching { manager.unloadPlugin(id) }
        }
        managers.clear()
        PluginManager.lifecycleListeners.remove(loadCounter)
        System.gc()

        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    private fun writeJar(id: String, dependencies: String? = null, version: String = "1.0.0"): Path {
        val manifest = Manifest()
        val attributes = manifest.mainAttributes

        attributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        attributes[Attributes.Name("id")] = id
        attributes[Attributes.Name("name")] = id
        attributes[Attributes.Name("description")] = "fixture"
        attributes[Attributes.Name("pano-version")] = "1.0.0"
        attributes[Attributes.Name("main-class")] = LicensedPlugin::class.java.name
        attributes[Attributes.Name("version")] = version
        attributes[Attributes.Name("developer")] = "test"
        attributes[Attributes.Name("license")] = "MIT"
        attributes[Attributes.Name("api-level")] = "1"

        if (dependencies != null) {
            attributes[Attributes.Name("dependencies")] = dependencies
        }

        val path = pluginsDir.resolve("$id.jar")

        JarOutputStream(Files.newOutputStream(path), manifest).close()

        return path
    }

    private fun disabledIds(): List<String> {
        val file = pluginsDir.resolve("disabled.txt")

        return if (Files.exists(file)) Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") } else emptyList()
    }

    private fun boot(): PluginManager = PluginManager(listOf(pluginsDir)).also {
        managers += it
        it.loadPlugins()
        it.startPlugins()
    }

    private fun PluginManager.state(id: String) = getPlugin(id)?.pluginState

    /** The market with a payment add-on and a carrier add-on that requires the shipping add-on. */
    private fun marketWithAddons() {
        writeJar("market")
        writeJar("stripe", "market")
        writeJar("shipping", "market")
        writeJar("carrier", "shipping")
    }

    @Test
    fun `switching the market off and on again brings its add-ons back`() {
        marketWithAddons()
        val manager = boot()

        listOf("market", "stripe", "shipping", "carrier").forEach { assertEquals(PluginState.STARTED, manager.state(it), it) }

        manager.disableWithDependents("market")

        listOf("market", "stripe", "shipping", "carrier").forEach { assertEquals(PluginState.DISABLED, manager.state(it), it) }
        assertEquals(listOf("market"), disabledIds(), "only the admin's choice is written")
        assertEquals("market", manager.waitingFor("stripe"))
        assertEquals("market", manager.waitingFor("shipping"))
        assertEquals("shipping", manager.waitingFor("carrier"))

        assertTrue(manager.enablePlugin("market"))
        assertEquals(PluginState.STARTED, manager.startPlugin("market"))

        listOf("market", "stripe", "shipping", "carrier").forEach { assertEquals(PluginState.STARTED, manager.state(it), it) }
        assertEquals(emptyList<String>(), disabledIds())
        assertNull(manager.waitingFor("stripe"))
        assertNull(manager.waitingFor("carrier"))
    }

    @Test
    fun `an add-on the admin switched off himself stays off when the market comes back`() {
        marketWithAddons()
        val manager = boot()

        manager.disableWithDependents("stripe")
        manager.disableWithDependents("market")
        assertTrue(manager.enablePlugin("market"))
        manager.startPlugin("market")

        assertEquals(PluginState.DISABLED, manager.state("stripe"))
        assertEquals(PluginState.STARTED, manager.state("shipping"))
        assertEquals(listOf("stripe"), disabledIds())
    }

    @Test
    fun `at boot an add-on of a switched-off market waits for it instead of starting without it`() {
        marketWithAddons()
        Files.writeString(pluginsDir.resolve("disabled.txt"), "market\n")

        val manager = boot()

        assertEquals(PluginState.DISABLED, manager.state("market"))
        listOf("stripe", "shipping", "carrier").forEach { assertEquals(PluginState.DISABLED, manager.state(it), it) }
        assertEquals("market", manager.waitingFor("stripe"))

        assertTrue(manager.enablePlugin("market"))
        manager.startPlugin("market")

        listOf("market", "stripe", "shipping", "carrier").forEach { assertEquals(PluginState.STARTED, manager.state(it), it) }
    }

    @Test
    fun `starting an add-on does not switch on a market the admin switched off, it waits`() {
        marketWithAddons()
        val manager = boot()

        manager.disableWithDependents("market")

        assertEquals(PluginState.DISABLED, manager.startPlugin("stripe"))
        assertEquals(PluginState.DISABLED, manager.state("market"))
        assertEquals(listOf("market"), disabledIds())
        assertEquals("market", manager.waitingFor("stripe"))
    }

    @Test
    fun `an add-on without a license waits for one, outside disabled txt, and starts when it arrives`() {
        marketWithAddons()
        LicensedPlugin.unlicensed += "stripe"

        val manager = boot()

        assertEquals(PluginState.STARTED, manager.state("market"))
        assertEquals(PluginState.DISABLED, manager.state("stripe"))
        assertTrue(manager.isLicenseBlocked("stripe"))
        assertEquals(emptyList<String>(), disabledIds())

        assertEquals(emptyList<String>(), manager.retryLicenseBlocked(), "still no license")
        assertTrue(manager.isLicenseBlocked("stripe"))

        LicensedPlugin.unlicensed -= "stripe"

        assertEquals(listOf("stripe"), manager.retryLicenseBlocked())
        assertEquals(PluginState.STARTED, manager.state("stripe"))
        assertFalse(manager.isLicenseBlocked("stripe"))
    }

    @Test
    fun `a license lost while running takes the plugin and what requires it off until it is back`() {
        marketWithAddons()
        val manager = boot()

        LicensedPlugin.unlicensed += "shipping"
        manager.blockForLicense("shipping")

        assertEquals(PluginState.DISABLED, manager.state("shipping"))
        assertEquals(PluginState.DISABLED, manager.state("carrier"))
        assertEquals("shipping", manager.waitingFor("carrier"))
        assertEquals(PluginState.STARTED, manager.state("stripe"))
        assertEquals(emptyList<String>(), disabledIds())

        LicensedPlugin.unlicensed -= "shipping"
        manager.retryLicenseBlocked()

        assertEquals(PluginState.STARTED, manager.state("shipping"))
        assertEquals(PluginState.STARTED, manager.state("carrier"))
    }

    @Test
    fun `a market without a license holds its add-ons until its license arrives`() {
        marketWithAddons()
        LicensedPlugin.unlicensed += "market"

        val manager = boot()

        listOf("market", "stripe", "shipping", "carrier").forEach { assertEquals(PluginState.DISABLED, manager.state(it), it) }
        assertTrue(manager.isLicenseBlocked("market"))

        LicensedPlugin.unlicensed -= "market"
        manager.retryLicenseBlocked()

        listOf("market", "stripe", "shipping", "carrier").forEach { assertEquals(PluginState.STARTED, manager.state(it), it) }
    }

    @Test
    fun `the add-ons unloaded with an updated market are loaded and started again`() {
        marketWithAddons()
        val manager = boot()

        manager.disableWithDependents("carrier")

        val path = manager.getPlugin("market").pluginPath
        assertTrue(manager.unloadPlugin("market"))
        assertNull(manager.getPlugin("stripe"))

        writeJar("market", version = "1.1.0")
        manager.loadPlugin(path)
        manager.enablePlugin("market")
        assertEquals(PluginState.STARTED, manager.startPlugin("market"))
        manager.liftHolds()

        assertEquals("1.1.0", manager.getPlugin("market").descriptor.version)
        assertEquals(PluginState.STARTED, manager.state("stripe"))
        assertEquals(PluginState.STARTED, manager.state("shipping"))
        assertEquals(PluginState.DISABLED, manager.state("carrier"), "the admin's choice survives the update")
    }

    @Test
    fun `the add-ons stopped before an update of the market start again after it, as the panel update does`() {
        // InstallManager.unloadPluginForInstall: stop, disable, unload. PF4J stops the add-ons with the market, so at unload
        // time they are STOPPED, not STARTED; they were running and must come back (seen on a hosted Pano, 2026-11-22).
        marketWithAddons()
        val manager = boot()
        assertEquals(PluginState.STARTED, manager.state("stripe"))

        val path = manager.getPlugin("market").pluginPath
        manager.stopPlugin("market")
        manager.disablePlugin("market")
        assertTrue(manager.unloadPlugin("market"))

        writeJar("market", version = "1.1.0")
        manager.loadPlugin(path)
        manager.enablePlugin("market")
        assertEquals(PluginState.STARTED, manager.startPlugin("market"))
        manager.liftHolds()

        assertEquals("1.1.0", manager.getPlugin("market").descriptor.version)
        assertEquals(PluginState.STARTED, manager.state("stripe"))
        assertEquals(PluginState.STARTED, manager.state("shipping"))
    }

    private fun PluginManager.instance(id: String) = (getPlugin(id) as PanoPluginWrapper).instance!!

    private fun registeredIds() = PluginEventManager.getEventListeners().keys.map { it.pluginId }

    @Test
    fun `an update of the market runs the disable hooks of its add-ons once and leaves nothing of the old ones behind`() {
        marketWithAddons()
        val manager = boot()
        val addons = listOf("stripe", "shipping", "carrier")
        val oldObjects = addons.associateWith { manager.instance(it) }

        // As InstallManager.unloadPluginForInstall does.
        val path = manager.getPlugin("market").pluginPath
        manager.stopPlugin("market")
        manager.disablePlugin("market")
        assertTrue(manager.unloadPlugin("market"))

        (addons + "market").forEach { assertEquals(1, LicensedPlugin.disables[it], it) }
        oldObjects.values.forEach { assertFalse(it.hostLoaded, it.pluginId) }
        assertTrue(registeredIds().none { it in addons + "market" }, "old objects are dropped: ${registeredIds()}")

        writeJar("market", version = "1.1.0")
        manager.loadPlugin(path)
        manager.enablePlugin("market")
        assertEquals(PluginState.STARTED, manager.startPlugin("market"))
        manager.liftHolds()

        for (id in addons) {
            assertEquals(PluginState.STARTED, manager.state(id), id)
            assertTrue(manager.instance(id) !== oldObjects[id], id)
            assertTrue(manager.instance(id).hostLoaded, id)
            // onStart called register() on the new object: its event listener entry was there.
            assertEquals(2, LicensedPlugin.registered[id], id)
        }

        assertEquals(1, registeredIds().count { it == "stripe" }, "one object of the add-on is registered")
        (addons + "market").forEach { assertEquals(1, LicensedPlugin.disables[it], "hooks ran once: $it") }
    }

    @Test
    fun `disabling a plugin that was already taken off does not run its hooks again`() {
        marketWithAddons()
        val manager = boot()

        manager.disableWithDependents("market")
        assertEquals(1, LicensedPlugin.disables["stripe"])

        manager.disablePlugin("stripe")
        assertEquals(1, LicensedPlugin.disables["stripe"])
        assertTrue(manager.unloadPlugin("market"))
        assertEquals(1, LicensedPlugin.disables["stripe"])
        assertEquals(1, LicensedPlugin.disables["market"])
    }

    @Test
    fun `a disabled plugin's own bean context is closed, the shared one stays, and an enable builds a new one`() {
        marketWithAddons()
        val manager = boot()
        val stripe = manager.instance("stripe")
        val firstContext = stripe.pluginBeanContext

        manager.disableWithDependents("stripe")

        assertFalse(firstContext.isActive)
        assertTrue(PluginManager.pluginGlobalBeanContext.isActive)
        assertTrue(manager.instance("market").pluginBeanContext.isActive)

        // A second unload (the delete of a disabled plugin does one) is harmless.
        stripe.unload()
        assertFalse(stripe.hostLoaded)

        assertTrue(manager.enablePlugin("stripe"))
        assertEquals(PluginState.STARTED, manager.startPlugin("stripe"))
        assertTrue(stripe.pluginBeanContext.isActive)
        assertTrue(stripe.pluginBeanContext !== firstContext)
    }

    @Test
    fun `an add-on installed while the market runs starts and is seen at once`() {
        writeJar("market")
        val manager = boot()

        val path = writeJar("stripe", "market")
        manager.loadPlugin(path)
        manager.enablePlugin("stripe")

        assertEquals(PluginState.STARTED, manager.startPlugin("stripe"))
    }
}
